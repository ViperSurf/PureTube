package com.puretube.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions", indices = [Index(value = ["channelId"], unique = true)])
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val channelName: String,
    val avatarUrl: String,
    val subscriberCountText: String?,
    val addedAtEpochMs: Long
)

@Entity(tableName = "watch_progress", indices = [Index(value = ["lastUpdatedEpochMs"])])
data class WatchProgressEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val thumbnailUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val isCompleted: Boolean,
    val lastUpdatedEpochMs: Long
) {
    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    companion object {
        /** A video counts as completed once 92% of it has been watched. */
        fun computeCompleted(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && positionMs >= (durationMs * 0.92).toLong()
    }
}

@Entity(tableName = "local_playlists")
data class LocalPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0,
    val name: String,
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "playlist_videos",
    primaryKeys = ["playlistId", "videoId"],
    indices = [Index("playlistId"), Index("positionIndex")]
)
data class PlaylistVideoCrossRef(
    val playlistId: Long,
    val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val positionIndex: Int
)

@Entity(tableName = "blocked_filters", indices = [Index(value = ["pattern", "filterType"], unique = true)])
data class BlockedFilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val filterType: FilterType
)

enum class FilterType { KEYWORD, CHANNEL_ID }
