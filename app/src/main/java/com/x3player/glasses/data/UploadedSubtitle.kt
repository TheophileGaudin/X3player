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

enum class SubtitleSelectionMode {
    NONE,
    EMBEDDED,
    EXTERNAL,
}

data class SubtitleSelection(
    val mode: SubtitleSelectionMode,
    val externalSubtitleId: Long? = null,
    val embeddedTrackKey: String? = null,
)

data class VideoSubtitleState(
    val subtitles: List<UploadedSubtitle> = emptyList(),
    val selection: SubtitleSelection? = null,
) {
    val selectedSubtitle: UploadedSubtitle?
        get() = selection
            ?.takeIf { it.mode == SubtitleSelectionMode.EXTERNAL }
            ?.externalSubtitleId
            ?.let { selectedId -> subtitles.firstOrNull { it.id == selectedId } }
}
