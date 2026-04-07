package com.x3player.glasses.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaybackProgressEntity::class,
        UploadedSubtitleEntity::class,
        SubtitleSelectionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class PlaybackDatabase : RoomDatabase() {
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun videoSubtitleDao(): VideoSubtitleDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `uploaded_subtitles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `videoId` INTEGER NOT NULL,
                        `subtitleUri` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_uploaded_subtitles_videoId_subtitleUri`
                    ON `uploaded_subtitles` (`videoId`, `subtitleUri`)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_uploaded_subtitles_videoId_createdAtEpochMs`
                    ON `uploaded_subtitles` (`videoId`, `createdAtEpochMs`)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `subtitle_selection` (
                        `videoId` INTEGER NOT NULL,
                        `selectedSubtitleId` INTEGER,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
