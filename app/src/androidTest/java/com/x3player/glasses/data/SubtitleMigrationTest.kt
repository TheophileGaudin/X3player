package com.x3player.glasses.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleMigrationTest {
    private lateinit var context: Context
    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DATABASE_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE `subtitle_selection` (
                                    `videoId` INTEGER NOT NULL,
                                    `selectedSubtitleId` INTEGER,
                                    PRIMARY KEY(`videoId`)
                                )
                                """.trimIndent()
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    }
                )
                .build()
        )
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationFromTwoPreservesExternalSelectionAndAddsTrackColumns() {
        val database = helper.writableDatabase
        database.execSQL(
            "INSERT INTO subtitle_selection(videoId, selectedSubtitleId) VALUES(7, 42)"
        )
        database.execSQL(
            "INSERT INTO subtitle_selection(videoId, selectedSubtitleId) VALUES(8, NULL)"
        )

        PlaybackDatabase.MIGRATION_2_3.migrate(database)

        database.query(
            """
            SELECT selectionMode, selectedSubtitleId, embeddedTrackKey
            FROM subtitle_selection
            WHERE videoId = 7
            """.trimIndent()
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("EXTERNAL", cursor.getString(0))
            assertEquals(42L, cursor.getLong(1))
            assertNull(cursor.getString(2))
        }
        database.query(
            """
            SELECT selectionMode, selectedSubtitleId
            FROM subtitle_selection
            WHERE videoId = 8
            """.trimIndent()
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("NONE", cursor.getString(0))
            assertNull(cursor.getString(1))
        }
    }

    private companion object {
        const val DATABASE_NAME = "subtitle-migration-test.db"
    }
}
