package com.x3player.glasses.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class MediaStoreVideoRepositoryTest {
    @Test
    fun `filters MediaStore rows whose content can no longer be opened`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = VideoTestProvider()
        ShadowContentResolver.registerProviderInternal(
            MediaStore.AUTHORITY,
            provider,
        )
        val repository = MediaStoreVideoRepository(
            context = context,
            videoUriReadable = { it.lastPathSegment == "1" },
        )

        val items = repository.observeLibrary(LibraryFilter.ALL, LibrarySort.DATE_DESC)

        runTest {
            assertEquals(listOf("readable.mp4"), items.first().map { it.displayName })
        }
    }
}

private class VideoTestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        return MatrixCursor(projection).apply {
            addRow(videoRow(projection.orEmpty(), 1L, "readable.mp4"))
            addRow(videoRow(projection.orEmpty(), 2L, "missing.mp4"))
        }
    }

    override fun getType(uri: Uri): String = "video/mp4"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun videoRow(
        projection: Array<out String>,
        id: Long,
        displayName: String,
    ): Array<Any?> {
        return projection.map { column ->
            when (column) {
                MediaStore.Video.Media._ID -> id
                MediaStore.Video.Media.DISPLAY_NAME -> displayName
                MediaStore.Video.Media.DURATION -> 60_000L
                MediaStore.Video.Media.SIZE -> 1_000L
                MediaStore.Video.Media.DATE_MODIFIED -> 1_700_000_000L
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME -> "Movies"
                MediaStore.Video.Media.RELATIVE_PATH -> "Movies/"
                else -> null
            }
        }.toTypedArray()
    }
}
