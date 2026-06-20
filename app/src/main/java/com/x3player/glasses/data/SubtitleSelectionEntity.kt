package com.x3player.glasses.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subtitle_selection")
data class SubtitleSelectionEntity(
    @PrimaryKey val videoId: Long,
    val selectionMode: String,
    val selectedSubtitleId: Long?,
    val embeddedTrackKey: String?,
)
