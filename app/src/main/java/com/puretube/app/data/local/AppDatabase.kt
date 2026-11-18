package com.puretube.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.puretube.app.data.local.dao.BlocklistDao
import com.puretube.app.data.local.dao.PlaylistDao
import com.puretube.app.data.local.dao.SubscriptionDao
import com.puretube.app.data.local.dao.WatchProgressDao
import com.puretube.app.data.local.entity.BlockedFilterEntity
import com.puretube.app.data.local.entity.FilterType
import com.puretube.app.data.local.entity.LocalPlaylistEntity
import com.puretube.app.data.local.entity.PlaylistVideoCrossRef
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.local.entity.WatchProgressEntity

class FilterTypeConverter {
    @TypeConverter
    fun fromFilterType(value: FilterType): String = value.name

    @TypeConverter
    fun toFilterType(value: String): FilterType = FilterType.valueOf(value)
}

@Database(
    entities = [
        SubscriptionEntity::class,
        WatchProgressEntity::class,
        LocalPlaylistEntity::class,
        PlaylistVideoCrossRef::class,
        BlockedFilterEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(FilterTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun blocklistDao(): BlocklistDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "puretube.db"
            ).build().also { instance = it }
        }
    }
}
