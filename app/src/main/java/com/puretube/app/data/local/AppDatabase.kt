package com.puretube.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.puretube.app.data.local.dao.BlocklistDao
import com.puretube.app.data.local.dao.BookmarkedPlaylistDao
import com.puretube.app.data.local.dao.PlaylistDao
import com.puretube.app.data.local.dao.SearchHistoryDao
import com.puretube.app.data.local.dao.SubscriptionDao
import com.puretube.app.data.local.dao.WatchProgressDao
import com.puretube.app.data.local.entity.BlockedFilterEntity
import com.puretube.app.data.local.entity.BookmarkedPlaylistEntity
import com.puretube.app.data.local.entity.FilterType
import com.puretube.app.data.local.entity.LocalPlaylistEntity
import com.puretube.app.data.local.entity.PlaylistVideoCrossRef
import com.puretube.app.data.local.entity.PlaylistVisibility
import com.puretube.app.data.local.entity.SearchHistoryEntity
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.local.entity.WatchProgressEntity

class FilterTypeConverter {
    @TypeConverter
    fun fromFilterType(value: FilterType): String = value.name

    @TypeConverter
    fun toFilterType(value: String): FilterType =
        runCatching { FilterType.valueOf(value) }.getOrDefault(FilterType.KEYWORD)
}

class PlaylistVisibilityConverter {
    @TypeConverter
    fun fromVisibility(value: PlaylistVisibility): String = value.name

    @TypeConverter
    fun toVisibility(value: String): PlaylistVisibility =
        runCatching { PlaylistVisibility.valueOf(value) }.getOrDefault(PlaylistVisibility.PRIVATE)
}

/** v1 -> v2: playlist visibility column + bookmarked playlists + search history tables. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE local_playlists ADD COLUMN visibility TEXT NOT NULL DEFAULT 'PRIVATE'"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bookmarked_playlists (
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                channelName TEXT NOT NULL,
                thumbnailUrl TEXT NOT NULL,
                savedAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(url)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS search_history (
                query TEXT NOT NULL,
                searchedAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(query)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_search_history_searchedAtEpochMs ON search_history (searchedAtEpochMs)"
        )
    }
}

/** v2 -> v3: local_playlists gains the YouTube browse id of synced playlists. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_playlists ADD COLUMN youtubePlaylistId TEXT")
    }
}

@Database(
    entities = [
        SubscriptionEntity::class,
        WatchProgressEntity::class,
        LocalPlaylistEntity::class,
        PlaylistVideoCrossRef::class,
        BlockedFilterEntity::class,
        BookmarkedPlaylistEntity::class,
        SearchHistoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(FilterTypeConverter::class, PlaylistVisibilityConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun blocklistDao(): BlocklistDao
    abstract fun bookmarkedPlaylistDao(): BookmarkedPlaylistDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "puretube.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
