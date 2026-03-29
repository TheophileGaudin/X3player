package com.x3player.glasses.data

data class PlaybackProgress(
    val videoId: Long,
    val uri: String,
    val resumePositionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val lastPlayedAt: Long,
)
