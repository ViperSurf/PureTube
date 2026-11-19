package com.puretube.app.data.account

import com.puretube.app.data.local.AppDatabase
import com.puretube.app.data.local.entity.LocalPlaylistEntity
import com.puretube.app.data.local.entity.PlaylistVideoCrossRef
import com.puretube.app.data.local.entity.PlaylistVisibility
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.local.entity.WatchProgressEntity
import com.puretube.app.data.remote.innertube.InnertubeClient
import com.puretube.app.domain.InnertubeParse
import com.puretube.app.domain.ParsedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Counts from one sync run, plus per-section notes for failures. */
data class SyncReport(
    val playlistsSynced: Int = 0,
    val playlistVideos: Int = 0,
    val historyAdded: Int = 0,
    val subscriptionsAdded: Int = 0,
    val playlistsPushed: Int = 0,
    val videosPushed: Int = 0,
    val notes: List<String> = emptyList()
) {
    fun summary(): String {
        val base = "Synced $playlistsSynced playlists ($playlistVideos videos), " +
            "$historyAdded history entries, $subscriptionsAdded new subscriptions. " +
            "Pushed $playlistsPushed playlists ($videosPushed videos added)."
        return if (notes.isEmpty()) base else base + " Notes: " + notes.joinToString(" ")
    }
}

/** Outcome of pushing one local playlist to the account (v2.1). */
data class PushOutcome(
    val created: Boolean,
    val videosAdded: Int,
    val message: String
)

/**
 * Two-way account sync (v2.1; pull-only in v2.0): PULL mirrors the
 * account's playlists, watch history, subscriptions and liked videos
 * INTO the local library; PUSH then uploads local playlists — creating
 * the ones YouTube doesn't have yet and adding only the videos the
 * account copy is missing. Likes / subscribes / comments post live at
 * tap time (not via sync). Watch history push is a best-effort beacon
 * from the player, not part of sync. Each section fails soft: a broken
 * section lands in the report's notes and the rest still sync. Local
 * rows always win over synced ones (a synced history entry never
 * overwrites a local resume position; local-only subscriptions and
 * playlists are never deleted).
 */
class YouTubeSyncManager(
    private val database: AppDatabase,
    private val innertubeClient: InnertubeClient
) {
    suspend fun syncNow(): Result<SyncReport> = withContext(Dispatchers.IO) {
        runCatching {
            val notes = mutableListOf<String>()
            var playlistsSynced = 0
            var playlistVideos = 0
            var historyAdded = 0
            var subscriptionsAdded = 0

            // ---- Playlists (guide -> per-playlist browse) ----
            val guideRefs = innertubeClient.guide()
                .map { InnertubeParse.parseGuidePlaylists(it) }
                .getOrElse { e ->
                    notes.add("Playlists skipped: ${e.message ?: "guide failed"}.")
                    emptyList()
                }
            val refs = guideRefs.toMutableList()
            // Liked videos may not appear in the guide; always try it.
            if (refs.none { it.browseId == "VLLL" }) {
                refs.add(
                    com.puretube.app.domain.ParsedPlaylistRef(
                        title = "Liked videos", browseId = "VLLL"
                    )
                )
            }
            for (ref in refs) {
                val videos = innertubeClient.browse(ref.browseId)
                    .map { InnertubeParse.parseVideos(it) }
                    .getOrElse { e ->
                        notes.add("\"${ref.title}\" skipped: ${e.message ?: "browse failed"}.")
                        null
                    } ?: continue
                val name = when {
                    ref.browseId == "VLLL" -> "Liked videos"
                    ref.title.isNotBlank() -> ref.title
                    else -> "YouTube playlist"
                }
                replaceSyncedPlaylist(ref.browseId, name, videos)
                playlistsSynced++
                playlistVideos += videos.size
            }

            // ---- Watch history (merge: local rows win) ----
            innertubeClient.browse("FEhistory")
                .map { InnertubeParse.parseVideos(it) }
                .onSuccess { videos ->
                    val now = System.currentTimeMillis()
                    videos.forEachIndexed { index, video ->
                        val existing = database.watchProgressDao().get(video.videoId)
                        if (existing == null) {
                            database.watchProgressDao().upsert(
                                WatchProgressEntity(
                                    videoId = video.videoId,
                                    title = video.title,
                                    channelName = video.channelName,
                                    channelId = video.channelId,
                                    thumbnailUrl = video.thumbnailUrl,
                                    positionMs = 0L,
                                    durationMs = video.durationSeconds * 1000L,
                                    isCompleted = false,
                                    // Preserve the account's ordering within today.
                                    lastUpdatedEpochMs = now - index * 1000L
                                )
                            )
                            historyAdded++
                        }
                    }
                }
                .onFailure { e ->
                    notes.add("History skipped: ${e.message ?: "browse failed"}.")
                }

            // ---- Subscriptions (merge: add missing only) ----
            innertubeClient.browse("FEsubscriptions")
                .map { InnertubeParse.parseChannels(it) }
                .onSuccess { channels ->
                    val now = System.currentTimeMillis()
                    for (channel in channels) {
                        if (database.subscriptionDao().get(channel.channelId) == null) {
                            database.subscriptionDao().upsert(
                                SubscriptionEntity(
                                    channelId = channel.channelId,
                                    channelName = channel.name,
                                    avatarUrl = channel.avatarUrl,
                                    subscriberCountText = null,
                                    addedAtEpochMs = now
                                )
                            )
                            subscriptionsAdded++
                        }
                    }
                }
                .onFailure { e ->
                    notes.add("Subscriptions skipped: ${e.message ?: "browse failed"}.")
                }

            // ---- Playlist PUSH (v2.1): local playlists YouTube lacks ----
            var playlistsPushed = 0
            var videosPushed = 0
            val localPlaylists = runCatching {
                database.playlistDao().observePlaylists().first()
            }.getOrDefault(emptyList())
            for (playlist in localPlaylists) {
                val storedId = playlist.youtubePlaylistId
                // Mirrors of account playlists carry "VL…" browse ids —
                // they already live on YouTube; skip them here.
                if (storedId != null && storedId.startsWith("VL")) continue
                pushPlaylist(playlist)
                    .onSuccess { outcome ->
                        playlistsPushed++
                        videosPushed += outcome.videosAdded
                    }
                    .onFailure { e ->
                        notes.add(
                            "Push \"${playlist.name}\" failed: ${e.message ?: "unknown error"}."
                        )
                    }
            }

            SyncReport(
                playlistsSynced = playlistsSynced,
                playlistVideos = playlistVideos,
                historyAdded = historyAdded,
                subscriptionsAdded = subscriptionsAdded,
                playlistsPushed = playlistsPushed,
                videosPushed = videosPushed,
                notes = notes
            )
        }
    }

    /**
     * Pushes ONE local playlist to the account (v2.1): creates it on
     * YouTube when it has no YouTube id yet (storing the returned id),
     * otherwise adds only the videos the account copy is missing.
     * Synced mirrors ("VL…" ids) and system playlists (LL/WL) are
     * refused with a message.
     */
    suspend fun pushPlaylist(playlist: LocalPlaylistEntity): Result<PushOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dao = database.playlistDao()
                val storedId = playlist.youtubePlaylistId
                if (storedId != null && storedId.startsWith("VL")) {
                    throw IllegalStateException(
                        "This playlist is a synced mirror — it already lives on YouTube."
                    )
                }
                if (!com.puretube.app.domain.PlaylistPushPlanner
                        .isEditablePlaylist(storedId)
                ) {
                    throw IllegalStateException(
                        "That playlist can't be pushed (system playlist)."
                    )
                }
                val localIds = dao.getVideos(playlist.playlistId).map { it.videoId }
                if (storedId == null) {
                    val privacy = when (playlist.visibility) {
                        PlaylistVisibility.PUBLIC -> "PUBLIC"
                        PlaylistVisibility.UNLISTED -> "UNLISTED"
                        PlaylistVisibility.PRIVATE -> "PRIVATE"
                    }
                    val newId = innertubeClient.createPlaylist(playlist.name, privacy)
                        .getOrThrow()
                    dao.setYoutubeId(playlist.playlistId, newId)
                    if (localIds.isNotEmpty()) {
                        innertubeClient.addVideosToPlaylist(newId, localIds).getOrThrow()
                    }
                    PushOutcome(
                        created = true,
                        videosAdded = localIds.size,
                        message = "Created \"${playlist.name}\" on YouTube " +
                            "with ${localIds.size} videos."
                    )
                } else {
                    val remoteIds = innertubeClient.playlistVideoIds(storedId)
                        .getOrThrow()
                    val missing = com.puretube.app.domain.PlaylistPushPlanner
                        .missingVideos(localIds, remoteIds.toSet())
                    if (missing.isNotEmpty()) {
                        innertubeClient.addVideosToPlaylist(storedId, missing)
                            .getOrThrow()
                    }
                    PushOutcome(
                        created = false,
                        videosAdded = missing.size,
                        message = if (missing.isEmpty()) {
                            "\"${playlist.name}\" is already up to date on YouTube."
                        } else {
                            "Added ${missing.size} missing videos to " +
                                "\"${playlist.name}\" on YouTube."
                        }
                    )
                }
            }
        }

    /** Creates or refreshes the local mirror of one account playlist. */
    private suspend fun replaceSyncedPlaylist(
        browseId: String,
        name: String,
        videos: List<ParsedVideo>
    ) {
        val dao = database.playlistDao()
        val existing = dao.getByYoutubeId(browseId)
        val playlistId = existing?.playlistId ?: dao.createPlaylist(
            LocalPlaylistEntity(
                name = name,
                createdAtEpochMs = System.currentTimeMillis(),
                visibility = PlaylistVisibility.PRIVATE,
                youtubePlaylistId = browseId
            )
        )
        if (existing != null && existing.name != name) {
            dao.rename(playlistId, name)
        }
        dao.clearVideos(playlistId)
        videos.forEachIndexed { index, video ->
            dao.addVideo(
                PlaylistVideoCrossRef(
                    playlistId = playlistId,
                    videoId = video.videoId,
                    title = video.title,
                    channelName = video.channelName,
                    thumbnailUrl = video.thumbnailUrl,
                    durationSeconds = video.durationSeconds,
                    positionIndex = index
                )
            )
        }
    }
}
