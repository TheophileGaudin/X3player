package com.x3player.glasses.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class SubtitleRepository(
    private val context: Context,
    private val videoSubtitleDao: VideoSubtitleDao,
) {
    private val subtitleImportPreparer = SubtitleImportPreparer(context)

    fun observeVideoSubtitles(videoId: Long): Flow<VideoSubtitleState> {
        return combine(
            videoSubtitleDao.observeSubtitles(videoId),
            videoSubtitleDao.observeSelection(videoId),
        ) { subtitles, selectionEntity ->
            val mapped = subtitles.map { it.toModel() }
            VideoSubtitleState(
                subtitles = mapped,
                selection = selectionEntity?.toModel(mapped),
            )
        }
    }

    suspend fun addSubtitle(videoId: Long, contentUri: Uri): UploadedSubtitle {
        val displayName = queryDisplayName(contentUri) ?: contentUri.lastPathSegment ?: "Subtitle"
        val rawMimeType = context.contentResolver.getType(contentUri)
        val preparedSubtitle = subtitleImportPreparer.prepare(
            videoId = videoId,
            contentUri = contentUri,
            displayName = displayName,
            rawMimeType = rawMimeType,
        )
        val now = System.currentTimeMillis()
        val storedSubtitleUri = preparedSubtitle.playbackUri.toString()
        val existing = videoSubtitleDao.findByVideoIdAndUri(videoId, storedSubtitleUri)
        val subtitleId = if (existing == null) {
            videoSubtitleDao.insertSubtitle(
                UploadedSubtitleEntity(
                    videoId = videoId,
                    subtitleUri = storedSubtitleUri,
                    displayName = displayName,
                    mimeType = preparedSubtitle.mimeType,
                    createdAtEpochMs = now,
                )
            )
        } else {
            videoSubtitleDao.updateSubtitle(
                existing.copy(
                    displayName = displayName,
                    mimeType = preparedSubtitle.mimeType,
                    createdAtEpochMs = now,
                )
            )
            existing.id
        }
        videoSubtitleDao.upsertSelection(
            SubtitleSelectionEntity(
                videoId = videoId,
                selectionMode = SubtitleSelectionMode.EXTERNAL.name,
                selectedSubtitleId = subtitleId,
                embeddedTrackKey = null,
            )
        )
        return requireNotNull(videoSubtitleDao.getById(subtitleId)?.toModel())
    }

    suspend fun selectNone(videoId: Long) {
        videoSubtitleDao.upsertSelection(
            SubtitleSelectionEntity(
                videoId = videoId,
                selectionMode = SubtitleSelectionMode.NONE.name,
                selectedSubtitleId = null,
                embeddedTrackKey = null,
            )
        )
    }

    suspend fun selectExternal(videoId: Long, subtitleId: Long) {
        videoSubtitleDao.upsertSelection(
            SubtitleSelectionEntity(
                videoId = videoId,
                selectionMode = SubtitleSelectionMode.EXTERNAL.name,
                selectedSubtitleId = subtitleId,
                embeddedTrackKey = null,
            )
        )
    }

    suspend fun selectEmbedded(videoId: Long, trackKey: String) {
        videoSubtitleDao.upsertSelection(
            SubtitleSelectionEntity(
                videoId = videoId,
                selectionMode = SubtitleSelectionMode.EMBEDDED.name,
                selectedSubtitleId = null,
                embeddedTrackKey = trackKey,
            )
        )
    }

    suspend fun clearPreference(videoId: Long) {
        videoSubtitleDao.clearPreference(videoId)
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

private fun SubtitleSelectionEntity.toModel(
    subtitles: List<UploadedSubtitle>,
): SubtitleSelection {
    val mode = runCatching { SubtitleSelectionMode.valueOf(selectionMode) }
        .getOrDefault(SubtitleSelectionMode.NONE)
    return when (mode) {
        SubtitleSelectionMode.NONE -> SubtitleSelection(mode)
        SubtitleSelectionMode.EMBEDDED -> SubtitleSelection(
            mode = mode,
            embeddedTrackKey = embeddedTrackKey,
        )
        SubtitleSelectionMode.EXTERNAL -> {
            val externalId = selectedSubtitleId?.takeIf { selectedId ->
                subtitles.any { it.id == selectedId }
            }
            if (externalId == null) {
                SubtitleSelection(SubtitleSelectionMode.NONE)
            } else {
                SubtitleSelection(
                    mode = mode,
                    externalSubtitleId = externalId,
                )
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
