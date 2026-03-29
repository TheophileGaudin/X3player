package com.x3player.glasses.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PlaybackProgressEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PlaybackDatabase : RoomDatabase() {
    abstract fun playbackProgressDao(): PlaybackProgressDao
}
