package com.x3player.glasses.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.x3player.glasses.util.resolveSupportedSubtitleMimeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class SubtitleRepository(
    private val context: Context,
    private val videoSubtitleDao: VideoSubtitleDao,
) {
    fun observeVideoSubtitles(videoId: Long): Flow<VideoSubtitleState> {
        return combine(
            videoSubtitleDao.observeSubtitles(videoId),
            videoSubtitleDao.observeSelectedSubtitleId(videoId),
        ) { subtitles, selectedSubtitleId ->
            val mapped = subtitles.map { it.toModel() }
            val normalizedSelection = selectedSubtitleId?.takeIf { id ->
                mapped.any { subtitle -> subtitle.id == id }
            }
            VideoSubtitleState(
                subtitles = mapped,
                selectedSubtitleId = normalizedSelection,
            )
        }
    }

    suspend fun addSubtitle(videoId: Long, contentUri: Uri): UploadedSubtitle {
        val displayName = queryDisplayName(contentUri) ?: contentUri.lastPathSegment ?: "Subtitle"
        val rawMimeType = context.contentResolver.getType(contentUri)
        val mimeType = resolveSupportedSubtitleMimeType(rawMimeType, displayName)
            ?: throw IllegalArgumentException("Unsupported subtitle format.")
        val now = System.currentTimeMillis()
        val existing = videoSubtitleDao.findByVideoIdAndUri(videoId, contentUri.toString())
        val subtitleId = if (existing == null) {
            videoSubtitleDao.insertSubtitle(
                UploadedSubtitleEntity(
                    videoId = videoId,
                    subtitleUri = contentUri.toString(),
                    displayName = displayName,
                    mimeType = mimeType,
                    createdAtEpochMs = now,
                )
            )
        } else {
            videoSubtitleDao.updateSubtitle(
                existing.copy(
                    displayName = displayName,
                    mimeType = mimeType,
                    createdAtEpochMs = now,
                )
            )
            existing.id
        }
        videoSubtitleDao.upsertSelection(
            SubtitleSelectionEntity(
                videoId = videoId,
                selectedSubtitleId = subtitleId,
            )
        )
        return requireNotNull(videoSubtitleDao.getById(subtitleId)?.toModel())
    }

    suspend fun selectSubtitle(videoId: Long, subtitleId: Long?) {
        if (subtitleId == null) {
            videoSubtitleDao.clearSelection(videoId)
        } else {
            videoSubtitleDao.upsertSelection(
                SubtitleSelectionEntity(
                    videoId = videoId,
                    selectedSubtitleId = subtitleId,
                )
            )
        }
    }

    private fun queryDisplayName(contentUri: Uri): String? {
        return context.contentResolver.query(
            contentUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }
    }
}

private fun UploadedSubtitleEntity.toModel(): UploadedSubtitle {
    return UploadedSubtitle(
        id = id,
        videoId = videoId,
        contentUri = Uri.parse(subtitleUri),
        displayName = displayName,
        mimeType = mimeType,
        createdAtEpochMs = createdAtEpochMs,
    )
}
