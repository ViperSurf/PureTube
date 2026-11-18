package com.puretube.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.puretube.app.data.local.entity.BlockedFilterEntity
import com.puretube.app.data.local.entity.BookmarkedPlaylistEntity
import com.puretube.app.data.local.entity.LocalPlaylistEntity
import com.puretube.app.data.local.entity.PlaylistVideoCrossRef
import com.puretube.app.data.local.entity.PlaylistVisibility
import com.puretube.app.data.local.entity.SearchHistoryEntity
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.local.entity.WatchProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY channelName ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY channelName ASC")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE channelId = :channelId")
    suspend fun get(channelId: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: SubscriptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(subscriptions: List<SubscriptionEntity>)

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    suspend fun delete(channelId: String)

    @Query("DELETE FROM subscriptions")
    suspend fun clearAll()
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

    /** Keeps history rows but forgets every resume position. */
    @Query("UPDATE watch_progress SET positionMs = 0, isCompleted = 0")
    suspend fun resetAllPositions()

    @Query("DELETE FROM watch_progress WHERE isCompleted = 1")
    suspend fun deleteCompleted()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM local_playlists ORDER BY createdAtEpochMs DESC")
    fun observePlaylists(): Flow<List<LocalPlaylistEntity>>

    @Query("SELECT * FROM local_playlists WHERE playlistId = :playlistId")
    suspend fun getPlaylist(playlistId: Long): LocalPlaylistEntity?

    @Query("SELECT * FROM local_playlists WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): LocalPlaylistEntity?

    @Insert
    suspend fun createPlaylist(playlist: LocalPlaylistEntity): Long

    @Query("DELETE FROM local_playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("UPDATE local_playlists SET name = :name WHERE playlistId = :playlistId")
    suspend fun rename(playlistId: Long, name: String)

    @Query("UPDATE local_playlists SET visibility = :visibility WHERE playlistId = :playlistId")
    suspend fun setVisibility(playlistId: Long, visibility: PlaylistVisibility)

    @Query("SELECT * FROM playlist_videos WHERE playlistId = :playlistId ORDER BY positionIndex ASC")
    fun observeVideos(playlistId: Long): Flow<List<PlaylistVideoCrossRef>>

    @Query("SELECT * FROM playlist_videos WHERE playlistId = :playlistId ORDER BY positionIndex ASC")
    suspend fun getVideos(playlistId: Long): List<PlaylistVideoCrossRef>

    @Query("SELECT COUNT(*) FROM playlist_videos WHERE playlistId = :playlistId")
    fun observeVideoCount(playlistId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM playlist_videos WHERE playlistId = :playlistId")
    suspend fun videoCount(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addVideo(ref: PlaylistVideoCrossRef)

    @Query("DELETE FROM playlist_videos WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeVideo(playlistId: Long, videoId: String)

    @Query("DELETE FROM playlist_videos WHERE playlistId = :playlistId")
    suspend fun clearVideos(playlistId: Long)
}

@Dao
interface BookmarkedPlaylistDao {
    @Query("SELECT * FROM bookmarked_playlists ORDER BY savedAtEpochMs DESC")
    fun observeAll(): Flow<List<BookmarkedPlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkedPlaylistEntity)

    @Query("DELETE FROM bookmarked_playlists WHERE url = :url")
    suspend fun delete(url: String)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAtEpochMs DESC")
    fun observeAll(): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searchedAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun delete(query: String)
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
