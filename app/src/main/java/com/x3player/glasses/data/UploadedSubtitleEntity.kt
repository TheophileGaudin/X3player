package com.x3player.glasses.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "uploaded_subtitles",
    indices = [
        Index(value = ["videoId", "subtitleUri"], unique = true),
        Index(value = ["videoId", "createdAtEpochMs"]),
    ],
)
data class UploadedSubtitleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val videoId: Long,
    val subtitleUri: String,
    val displayName: String,
    val mimeType: String,
    val createdAtEpochMs: Long,
)
