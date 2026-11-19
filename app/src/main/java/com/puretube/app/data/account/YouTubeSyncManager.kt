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
import kotlinx.coroutines.withContext

/** Counts from one sync run, plus per-section notes for failures. */
data class SyncReport(
    val playlistsSynced: Int = 0,
    val playlistVideos: Int = 0,
    val historyAdded: Int = 0,
    val subscriptionsAdded: Int = 0,
    val notes: List<String> = emptyList()
) {
    fun summary(): String {
        val base = "Synced $playlistsSynced playlists ($playlistVideos videos), " +
            "$historyAdded history entries, $subscriptionsAdded new subscriptions."
        return if (notes.isEmpty()) base else base + " Notes: " + notes.joinToString(" ")
    }
}

/**
 * Pull-only account sync (v2.0): the account's playlists, watch history,
 * subscriptions and liked videos are mirrored INTO the local library.
 * Nothing local is ever pushed back by sync — the only writes to the
 * account are the direct like / subscribe / comment actions. Each
 * section fails soft: a broken section lands in the report's notes and
 * the rest still sync. Local rows always win over synced ones (a synced
 * history entry never overwrites a local resume position; local-only
 * subscriptions and playlists are never deleted).
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

            SyncReport(
                playlistsSynced = playlistsSynced,
                playlistVideos = playlistVideos,
                historyAdded = historyAdded,
                subscriptionsAdded = subscriptionsAdded,
                notes = notes
            )
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
