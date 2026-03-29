package com.x3player.glasses.data

interface PlaybackProgressStore {
    suspend fun load(videoId: Long): PlaybackProgress?
    suspend fun save(
        videoId: Long,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
        uri: String = "",
    )
    suspend fun clear(videoId: Long)
}
