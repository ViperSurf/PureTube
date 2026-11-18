package com.puretube.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.puretube.app.data.local.entity.BlockedFilterEntity
import com.puretube.app.data.local.entity.LocalPlaylistEntity
import com.puretube.app.data.local.entity.PlaylistVideoCrossRef
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.local.entity.WatchProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY channelName ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY channelName ASC")
    suspend fun getAll(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: SubscriptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(subscriptions: List<SubscriptionEntity>)

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    suspend fun delete(channelId: String)
}

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress ORDER BY lastUpdatedEpochMs DESC")
    fun observeHistory(): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE videoId = :videoId")
    suspend fun get(videoId: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE isCompleted = 0 ORDER BY lastUpdatedEpochMs DESC")
    fun observeUnwatchedOrInProgress(): Flow<List<WatchProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM watch_progress")
    suspend fun clearAll()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM local_playlists ORDER BY createdAtEpochMs DESC")
    fun observePlaylists(): Flow<List<LocalPlaylistEntity>>

    @Insert
    suspend fun createPlaylist(playlist: LocalPlaylistEntity): Long

    @Query("SELECT * FROM playlist_videos WHERE playlistId = :playlistId ORDER BY positionIndex ASC")
    fun observeVideos(playlistId: Long): Flow<List<PlaylistVideoCrossRef>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addVideo(ref: PlaylistVideoCrossRef)

    @Query("DELETE FROM playlist_videos WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeVideo(playlistId: Long, videoId: String)
}

@Dao
interface BlocklistDao {
    @Query("SELECT * FROM blocked_filters ORDER BY pattern ASC")
    fun observeAll(): Flow<List<BlockedFilterEntity>>

    @Query("SELECT * FROM blocked_filters")
    suspend fun getAll(): List<BlockedFilterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(filter: BlockedFilterEntity)

    @Query("DELETE FROM blocked_filters WHERE id = :id")
    suspend fun delete(id: Long)
}
