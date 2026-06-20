package com.x3player.glasses.util

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleFilesTest {
    @Test
    fun `prefers supported mime type when available`() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            resolveSupportedSubtitleMimeType("application/x-subrip", "movie.txt"),
        )
        assertEquals(
            MimeTypes.TEXT_VTT,
            resolveSupportedSubtitleMimeType(MimeTypes.TEXT_VTT, "movie.srt"),
        )
    }

    @Test
    fun `falls back to subtitle extension when mime type is generic`() {
        assertEquals(
            MimeTypes.TEXT_SSA,
            resolveSupportedSubtitleMimeType("application/octet-stream", "movie.ass"),
        )
        assertEquals(
            MimeTypes.APPLICATION_TTML,
            resolveSupportedSubtitleMimeType("text/plain", "movie.ttml"),
        )
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            resolveSupportedSubtitleMimeType("text/plain", "movie.sub"),
        )
        assertEquals(
            MimeTypes.TEXT_VTT,
            resolveSupportedSubtitleMimeType("application/octet-stream", "movie.vvt"),
        )
    }

    @Test
    fun `rejects unsupported files`() {
        assertNull(resolveSupportedSubtitleMimeType(null, null))
    }

    @Test
    fun `txt files are accepted for content inspection`() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            resolveSupportedSubtitleMimeType("text/plain", "movie.txt"),
        )
    }
}
