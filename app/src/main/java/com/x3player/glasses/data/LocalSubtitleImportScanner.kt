package com.x3player.glasses.data

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.x3player.glasses.util.isSupportedSubtitleFileName
import com.x3player.glasses.util.resolveSupportedSubtitleMimeType
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalSubtitleCandidate(
    val displayName: String,
    val contentUri: Uri,
    val modifiedAtMs: Long,
    val relativePath: String?,
)

class LocalSubtitleImportScanner(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun findCandidatesFor(videoItem: VideoItem): List<LocalSubtitleCandidate> = withContext(ioDispatcher) {
        val normalizedVideoStem = videoItem.displayName
            .substringBeforeLast('.', videoItem.displayName)
            .lowercase(Locale.US)

        querySubtitleRows()
            .asSequence()
            .filter { row ->
                isSupportedSubtitleFileName(row.displayName) ||
                    resolveSupportedSubtitleMimeType(row.mimeType, row.displayName) != null
            }
            .filter { row -> isAllowedImportPath(row.relativePath) }
            .distinctBy { it.contentUri.toString() }
            .map { row ->
                LocalSubtitleCandidate(
                    displayName = row.displayName,
                    contentUri = row.contentUri,
                    modifiedAtMs = row.modifiedAtMs,
                    relativePath = row.relativePath,
                ) to subtitleMatchRank(normalizedVideoStem, row.displayName)
            }
            .sortedWith(
                compareBy<Pair<LocalSubtitleCandidate, Int>> { it.second }
                    .thenByDescending { it.first.modifiedAtMs }
                    .thenBy { it.first.displayName.lowercase(Locale.US) }
            )
            .map { it.first }
            .take(MAX_RESULTS)
            .toList()
    }

    private fun querySubtitleRows(): List<SubtitleMediaRow> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            add(MediaStore.Files.FileColumns.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Files.FileColumns.RELATIVE_PATH)
            }
        }.toTypedArray()

        val results = mutableListOf<SubtitleMediaRow>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Files.FileColumns.SIZE} > 0",
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateModifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val mimeTypeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val relativePathIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                results += SubtitleMediaRow(
                    displayName = cursor.getString(nameIndex).orEmpty(),
                    contentUri = ContentUris.withAppendedId(collection, id),
                    modifiedAtMs = cursor.getLong(dateModifiedIndex).coerceAtLeast(0L) * 1000L,
                    mimeType = if (cursor.isNull(mimeTypeIndex)) null else cursor.getString(mimeTypeIndex),
                    relativePath = relativePathIndex.takeIf { it >= 0 }?.let { index ->
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    },
                )
            }
        }
        return results
    }

    private fun subtitleMatchRank(normalizedVideoStem: String, subtitleName: String): Int {
        val normalizedSubtitleStem = subtitleName
            .substringBeforeLast('.', subtitleName)
            .lowercase(Locale.US)

        return when {
            normalizedSubtitleStem == normalizedVideoStem -> 0
            normalizedSubtitleStem.startsWith("$normalizedVideoStem.") -> 1
            normalizedSubtitleStem.startsWith("${normalizedVideoStem}_") -> 1
            normalizedSubtitleStem.startsWith("${normalizedVideoStem}-") -> 1
            normalizedSubtitleStem.contains(normalizedVideoStem) -> 2
            else -> 3
        }
    }

    private fun isAllowedImportPath(relativePath: String?): Boolean {
        if (relativePath.isNullOrBlank()) return false
        val normalized = relativePath.lowercase(Locale.US)
        return normalized.startsWith("documents/") ||
            normalized.startsWith("download/") ||
            normalized.startsWith("movies/")
    }

    private data class SubtitleMediaRow(
        val displayName: String,
        val contentUri: Uri,
        val modifiedAtMs: Long,
        val mimeType: String?,
        val relativePath: String?,
    )

    companion object {
        private const val MAX_RESULTS = 150
    }
}
