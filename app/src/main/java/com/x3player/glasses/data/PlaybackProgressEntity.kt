package com.x3player.glasses.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_progress")
data class PlaybackProgressEntity(
    @PrimaryKey val videoId: Long,
    val uri: String,
    val resumePositionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val lastPlayedAt: Long,
)
