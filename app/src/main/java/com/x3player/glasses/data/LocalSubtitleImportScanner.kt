package com.x3player.glasses.data

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.x3player.glasses.util.isSupportedSubtitleFileName
import com.x3player.glasses.util.normalizeTextSubtitleForPlayback
import com.x3player.glasses.util.resolveSupportedSubtitleMimeType
import com.x3player.glasses.util.SubtitleImportType
import com.x3player.glasses.util.subtitleExtension
import java.io.File
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
            .filter { row -> isUsableSubtitleCandidate(row) }
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
        return (queryMediaStoreSubtitleRows() + queryFileSystemSubtitleRows())
            .distinctBy { row -> row.fileIdentityKey() }
    }

    private fun queryMediaStoreSubtitleRows(): List<SubtitleMediaRow> {
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

    private fun queryFileSystemSubtitleRows(): List<SubtitleMediaRow> {
        val standardDirectories = buildStandardScanDirectories(
            listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            )
        )
        if (standardDirectories.isEmpty()) {
            return emptyList()
        }

        val externalStorageRoot = Environment.getExternalStorageDirectory()
        return standardDirectories
            .asSequence()
            .flatMap { directory ->
                directory.walkTopDown()
                    .onFail { _, _ -> }
                    .maxDepth(MAX_SCAN_DEPTH)
                    .filter { file -> file.isFile && isSupportedSubtitleFileName(file.name) }
                    .map { file ->
                        SubtitleMediaRow(
                            displayName = file.name,
                            contentUri = Uri.fromFile(file),
                            modifiedAtMs = file.lastModified().coerceAtLeast(0L),
                            mimeType = resolveSupportedSubtitleMimeType(null, file.name),
                            relativePath = resolveRelativePath(file, externalStorageRoot),
                        )
                    }
            }
            .toList()
    }

    private fun resolveRelativePath(file: File, externalStorageRoot: File): String? {
        val parent = file.parentFile ?: return null
        val rootPath = externalStorageRoot.absolutePath.trimEnd(File.separatorChar)
        val parentPath = parent.absolutePath
        if (!parentPath.startsWith(rootPath)) return null
        val normalizedPath = parentPath
            .removePrefix(rootPath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
        return if (normalizedPath.isBlank()) null else "$normalizedPath/"
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

    private fun isUsableSubtitleCandidate(row: SubtitleMediaRow): Boolean {
        if (subtitleExtension(row.displayName) != "txt") return true
        val probeBytes = runCatching {
            context.contentResolver.openInputStream(row.contentUri)?.use { inputStream ->
                val buffer = ByteArray(MAX_TEXT_PROBE_BYTES)
                val byteCount = inputStream.read(buffer)
                if (byteCount <= 0) ByteArray(0) else buffer.copyOf(byteCount)
            }
        }.getOrNull() ?: return false
        if (probeBytes.isEmpty()) return false

        val probeText = probeBytes.toString(Charsets.ISO_8859_1)
        return normalizeTextSubtitleForPlayback(SubtitleImportType.TEXT_AUTO, probeText) != null
    }

    private fun isAllowedImportPath(relativePath: String?): Boolean {
        if (relativePath.isNullOrBlank()) return false
        val normalized = relativePath.lowercase(Locale.US)
        return normalized.startsWith("documents/") ||
            normalized.startsWith("download/") ||
            normalized.startsWith("movies/")
    }

    private fun SubtitleMediaRow.fileIdentityKey(): String {
        val normalizedRelativePath = relativePath
            ?.replace('\\', '/')
            ?.trim()
            ?.trimStart('/')
            ?.trimEnd('/')
            ?.lowercase(Locale.US)
            .orEmpty()
        val normalizedName = displayName.lowercase(Locale.US)
        return if (normalizedRelativePath.isNotBlank()) {
            "$normalizedRelativePath/$normalizedName"
        } else {
            contentUri.toString()
        }
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
        private const val MAX_SCAN_DEPTH = 5
        private const val MAX_TEXT_PROBE_BYTES = 64 * 1024
    }
}
