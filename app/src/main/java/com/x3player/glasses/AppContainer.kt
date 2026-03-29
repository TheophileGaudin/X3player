package com.x3player.glasses

import android.app.Application
import androidx.room.Room
import com.x3player.glasses.data.MediaStoreVideoRepository
import com.x3player.glasses.data.PlaybackDatabase
import com.x3player.glasses.data.PlaybackProgressStore
import com.x3player.glasses.data.RoomPlaybackProgressStore
import com.x3player.glasses.data.SettingsRepository
import com.x3player.glasses.data.VideoRepository

class AppContainer(application: Application) {
    private val appContext = application.applicationContext

    private val database: PlaybackDatabase by lazy {
        Room.databaseBuilder(appContext, PlaybackDatabase::class.java, "x3player.db").build()
    }

    val videoRepository: VideoRepository by lazy {
        MediaStoreVideoRepository(appContext)
    }

    val playbackProgressStore: PlaybackProgressStore by lazy {
        RoomPlaybackProgressStore(database.playbackProgressDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }
}
