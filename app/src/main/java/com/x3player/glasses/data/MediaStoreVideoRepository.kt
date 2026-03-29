package com.x3player.glasses.data

import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.coroutines.resume

@OptIn(ExperimentalCoroutinesApi::class)
class MediaStoreVideoRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VideoRepository {

    private val refreshVersion = MutableStateFlow(0)
    private val autoScanAttempted = AtomicBoolean(false)

    override fun observeLibrary(filter: LibraryFilter, sort: LibrarySort): Flow<List<VideoItem>> {
        return refreshVersion
            .mapLatest { loadVideos(filter, sort) }
            .distinctUntilChanged()
    }

    override suspend fun refreshLibrary() {
        withContext(ioDispatcher) {
            rescanStandardDirectories()
            refreshVersion.value += 1
        }
    }

    override suspend fun getByUri(uri: android.net.Uri): VideoItem? = withContext(ioDispatcher) {
        queryVideos()
            .firstOrNull { it.contentUri == uri }
    }

    private suspend fun loadVideos(filter: LibraryFilter, sort: LibrarySort): List<VideoItem> {
        val items = withContext(ioDispatcher) {
            queryVideos(filter, sort)
        }
        if (items.isNotEmpty()) {
            return items
        }
        if (!autoScanAttempted.compareAndSet(false, true)) {
            return items
        }
        return withContext(ioDispatcher) {
            rescanStandardDirectories()
            queryVideos(filter, sort)
        }
    }

    private fun queryVideos(
        filter: LibraryFilter = LibraryFilter.ALL,
        sort: LibrarySort = LibrarySort.DATE_DESC,
    ): List<VideoItem> {
        val projection = buildProjection()
        val selectionParts = mutableListOf("${MediaStore.Video.Media.SIZE} > 0")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selectionParts += "${MediaStore.Video.Media.IS_PENDING} = 0"
        }
        val selection = selectionParts.joinToString(" AND ")

        val items = mutableListOf<VideoItem>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            null,
        )?.use { cursor ->
            val indexes = VideoCursorIndexes.from(cursor)
            while (cursor.moveToNext()) {
                val row = VideoQueryRow(
                    id = cursor.getLong(indexes.id),
                    displayName = cursor.getString(indexes.displayName).orEmpty(),
                    durationMs = cursor.getLong(indexes.duration),
                    sizeBytes = cursor.getLong(indexes.size),
                    dateModifiedSeconds = cursor.getLong(indexes.dateModified),
                    bucketName = cursor.getNullableString(indexes.bucketName),
                    relativePath = indexes.relativePath?.let(cursor::getNullableString),
                )
                if (matchesFilter(filter, row.bucketName, row.relativePath)) {
                    items += mapVideoQueryRow(row)
                }
            }
        }
        return sortVideos(items, sort)
    }

    private fun buildProjection(): Array<String> {
        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.Video.Media.RELATIVE_PATH
        }
        return projection.toTypedArray()
    }

    private suspend fun rescanStandardDirectories() {
        val standardDirectories = buildStandardScanDirectories(
            listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            )
        )
        val candidates = collectVideoCandidates(standardDirectories)
        if (candidates.isEmpty()) {
            return
        }
        val paths = candidates.map(File::getAbsolutePath).toTypedArray()
        suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(paths.size)
            MediaScannerConnection.scanFile(context, paths, null) { _, _ ->
                if (remaining.decrementAndGet() == 0 && continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    private data class VideoCursorIndexes(
        val id: Int,
        val displayName: Int,
        val duration: Int,
        val size: Int,
        val dateModified: Int,
        val bucketName: Int,
        val relativePath: Int?,
    ) {
        companion object {
            fun from(cursor: Cursor): VideoCursorIndexes = VideoCursorIndexes(
                id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID),
                displayName = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME),
                duration = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION),
                size = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE),
                dateModified = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED),
                bucketName = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME),
                relativePath = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH).takeIf { it >= 0 },
            )
        }
    }
}

private fun Cursor.getNullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)
