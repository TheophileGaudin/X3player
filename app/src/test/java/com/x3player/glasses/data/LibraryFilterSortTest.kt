package com.x3player.glasses.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryFilterSortTest {
    private val items = listOf(
        VideoItem(1, Uri.parse("content://videos/1"), "zeta.mp4", 60_000L, 1L, 10L, "Movies"),
        VideoItem(2, Uri.parse("content://videos/2"), "alpha.mp4", 180_000L, 1L, 30L, "Downloads"),
        VideoItem(3, Uri.parse("content://videos/3"), "bravo.mp4", 120_000L, 1L, 20L, "Movies"),
    )

    @Test
    fun `matches folder filters`() {
        assertTrue(matchesFilter(LibraryFilter.MOVIES, "Movies", "Movies/"))
        assertTrue(matchesFilter(LibraryFilter.DOWNLOADS, "Download", "Download/"))
        assertFalse(matchesFilter(LibraryFilter.MOVIES, "Downloads", "Download/"))
    }

    @Test
    fun `sorts by newest and name`() {
        assertEquals(listOf(2L, 3L, 1L), sortVideos(items, LibrarySort.DATE_DESC).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), sortVideos(items, LibrarySort.NAME_ASC).map { it.id })
    }

    @Test
    fun `sorts by duration descending`() {
        assertEquals(listOf(2L, 3L, 1L), sortVideos(items, LibrarySort.DURATION_DESC).map { it.id })
    }
}
