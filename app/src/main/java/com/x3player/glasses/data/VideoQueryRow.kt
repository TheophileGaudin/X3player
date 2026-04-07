package com.x3player.glasses.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import java.util.Locale

data class VideoQueryRow(
    val id: Long,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModifiedSeconds: Long,
    val bucketName: String?,
    val relativePath: String?,
)

fun mapVideoQueryRow(row: VideoQueryRow): VideoItem {
    val normalizedBucketName = row.bucketName?.takeIf { it.isNotBlank() }
        ?: inferBucketName(row.relativePath)
        ?: "Unknown"
    return VideoItem(
        id = row.id,
        contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, row.id),
        displayName = row.displayName.ifBlank { "Video ${row.id}" },
        durationMs = row.durationMs.coerceAtLeast(0L),
        sizeBytes = row.sizeBytes.coerceAtLeast(0L),
        dateModified = row.dateModifiedSeconds.coerceAtLeast(0L) * 1000L,
        bucketName = normalizedBucketName,
    )
}

fun inferBucketName(relativePath: String?): String? {
    if (relativePath.isNullOrBlank()) return null
    val segments = relativePath
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() }
    return segments.lastOrNull()?.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }
}

fun matchesFilter(filter: LibraryFilter, bucketName: String?, relativePath: String?): Boolean {
    if (filter == LibraryFilter.ALL) return true
    val normalizedBucket = bucketName.orEmpty().trim().lowercase(Locale.US)
    val normalizedPath = relativePath.orEmpty().lowercase(Locale.US)
    return when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.MOVIES -> {
            normalizedBucket == "movies" || normalizedPath.contains("/movies/")
        }
        LibraryFilter.DOWNLOADS -> {
            normalizedBucket == "downloads" ||
                normalizedBucket == "download" ||
                normalizedPath.contains("/downloads/") ||
                normalizedPath.contains("/download/")
        }
    }
}

fun sortVideos(items: List<VideoItem>, sort: LibrarySort): List<VideoItem> = when (sort) {
    LibrarySort.DATE_DESC -> items.sortedWith(
        compareByDescending<VideoItem> { it.dateModified }.thenBy { it.displayName.lowercase(Locale.US) }
    )
    LibrarySort.NAME_ASC -> items.sortedBy { it.displayName.lowercase(Locale.US) }
    LibrarySort.DURATION_DESC -> items.sortedWith(
        compareByDescending<VideoItem> { it.durationMs }.thenBy { it.displayName.lowercase(Locale.US) }
    )
}

fun buildStandardScanDirectories(rootDirectories: List<java.io.File>): List<java.io.File> =
    rootDirectories.filter { it.exists() && it.isDirectory }

fun collectVideoCandidates(
    rootDirectories: List<java.io.File>,
    allowedExtensions: Set<String> = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi")
): List<java.io.File> {
    if (rootDirectories.isEmpty()) return emptyList()
    return rootDirectories
        .asSequence()
        .flatMap { directory ->
            directory.walkTopDown()
                .onFail { _, _ -> }
                .maxDepth(5)
                .filter { it.isFile }
                .filter { file ->
                    allowedExtensions.contains(file.extension.lowercase(Locale.US))
                }
        }
        .distinctBy { it.absolutePath }
        .toList()
}
