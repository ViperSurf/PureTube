package com.puretube.app.data

import com.puretube.app.data.local.AppDatabase
import com.puretube.app.data.local.entity.LocalPlaylistEntity
import com.puretube.app.data.local.entity.PlaylistVideoCrossRef
import com.puretube.app.data.local.entity.PlaylistVisibility
import com.puretube.app.data.remote.extractor.FeedItem

/** The built-in Watch Later playlist, created lazily on first use. */
object WatchLaterHelper {
    const val NAME = "Watch Later"

    suspend fun ensureId(db: AppDatabase): Long {
        val existing = runCatching { db.playlistDao().getByName(NAME) }.getOrNull()
        if (existing != null) return existing.playlistId
        return db.playlistDao().createPlaylist(
            LocalPlaylistEntity(
                name = NAME,
                createdAtEpochMs = System.currentTimeMillis(),
                visibility = PlaylistVisibility.PRIVATE
            )
        )
    }

    suspend fun contains(db: AppDatabase, videoId: String): Boolean {
        val id = runCatching { db.playlistDao().getByName(NAME)?.playlistId }.getOrNull() ?: return false
        return runCatching { db.playlistDao().getVideos(id).any { it.videoId == videoId } }
            .getOrDefault(false)
    }

    suspend fun toggle(db: AppDatabase, item: FeedItem): Boolean {
        val id = ensureId(db)
        val dao = db.playlistDao()
        return if (dao.getVideos(id).any { it.videoId == item.videoId }) {
            dao.removeVideo(id, item.videoId)
            false
        } else {
            dao.addVideo(
                PlaylistVideoCrossRef(
                    playlistId = id,
                    videoId = item.videoId,
                    title = item.title,
                    channelName = item.channelName,
                    thumbnailUrl = item.thumbnailUrl,
                    durationSeconds = item.durationSeconds,
                    positionIndex = dao.videoCount(id)
                )
            )
            true
        }
    }

    fun FeedItem.toCrossRef(playlistId: Long, positionIndex: Int) = PlaylistVideoCrossRef(
        playlistId = playlistId,
        videoId = videoId,
        title = title,
        channelName = channelName,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        positionIndex = positionIndex
    )
}
