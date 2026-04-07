package com.x3player.glasses.data

import android.net.Uri

data class UploadedSubtitle(
    val id: Long,
    val videoId: Long,
    val contentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val createdAtEpochMs: Long,
)

data class VideoSubtitleState(
    val subtitles: List<UploadedSubtitle> = emptyList(),
    val selectedSubtitleId: Long? = null,
) {
    val selectedSubtitle: UploadedSubtitle?
        get() = subtitles.firstOrNull { it.id == selectedSubtitleId }
}
