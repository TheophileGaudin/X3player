package com.x3player.glasses.data

import android.net.Uri

data class VideoItem(
    val id: Long,
    val contentUri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModified: Long,
    val bucketName: String,
)
