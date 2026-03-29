package com.x3player.glasses.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoMappingTest {
    @Test
    fun `maps MediaStore row into VideoItem`() {
        val item = mapVideoQueryRow(
            VideoQueryRow(
                id = 42L,
                displayName = "demo.mp4",
                durationMs = 91_000L,
                sizeBytes = 10_000L,
                dateModifiedSeconds = 1_700_000_000L,
                bucketName = "Movies",
                relativePath = "Movies/",
            )
        )

        assertEquals(42L, item.id)
        assertEquals("demo.mp4", item.displayName)
        assertEquals(91_000L, item.durationMs)
        assertEquals("Movies", item.bucketName)
        assertTrue(item.contentUri.toString().endsWith("/42"))
    }
}
