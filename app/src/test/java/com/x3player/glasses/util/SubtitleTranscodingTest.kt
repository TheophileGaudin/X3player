package com.x3player.glasses.util

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTranscodingTest {
    @Test
    fun `transcodes microdvd sub files to subrip`() {
        val normalized = normalizeTextSubtitleForPlayback(
            importType = SubtitleImportType.SUB,
            sourceText = """
                {1}{1}25.000
                {25}{50}Hello from X3player|MicroDVD sample
                {60}{85}Second cue
            """.trimIndent(),
        )

        assertNotNull(normalized)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, normalized?.mimeType)
        assertEquals("srt", normalized?.fileExtension)
        assertEquals(
            """
            1
            00:00:01,000 --> 00:00:02,000
            Hello from X3player
            MicroDVD sample

            2
            00:00:02,400 --> 00:00:03,400
            Second cue
            """.trimIndent(),
            normalized?.content,
        )
    }

    @Test
    fun `transcodes subviewer sub files to subrip`() {
        val normalized = normalizeTextSubtitleForPlayback(
            importType = SubtitleImportType.SUB,
            sourceText = """
                [INFORMATION]
                [TITLE]Sample
                [END INFORMATION]
                [SUBTITLE]
                00:00:01.00,00:00:02.50
                First line
                Second line

                00:00:03.00,00:00:04.00
                Third line
            """.trimIndent(),
        )

        assertNotNull(normalized)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, normalized?.mimeType)
        assertEquals(
            """
            1
            00:00:01,000 --> 00:00:02,500
            First line
            Second line

            2
            00:00:03,000 --> 00:00:04,000
            Third line
            """.trimIndent(),
            normalized?.content,
        )
    }

    @Test
    fun `accepts webvtt content inside sub files`() {
        val normalized = normalizeTextSubtitleForPlayback(
            importType = SubtitleImportType.SUB,
            sourceText = """
                WEBVTT

                00:00:01.000 --> 00:00:02.000
                Alias content
            """.trimIndent(),
        )

        assertNotNull(normalized)
        assertEquals(MimeTypes.TEXT_VTT, normalized?.mimeType)
        assertEquals("vtt", normalized?.fileExtension)
    }

    @Test
    fun `detects timed subtitle formats inside txt files`() {
        val normalized = normalizeTextSubtitleForPlayback(
            importType = SubtitleImportType.TEXT_AUTO,
            sourceText = """
                1
                00:04:55,000 --> 00:04:59,000
                Final five-minute cue
            """.trimIndent(),
        )

        assertEquals(MimeTypes.APPLICATION_SUBRIP, normalized?.mimeType)
    }

    @Test
    fun `rejects untimed txt files`() {
        assertNull(
            normalizeTextSubtitleForPlayback(
                importType = SubtitleImportType.TEXT_AUTO,
                sourceText = "These are ordinary notes without subtitle timing.",
            ),
        )
        assertNull(
            normalizeTextSubtitleForPlayback(
                importType = SubtitleImportType.TEXT_AUTO,
                sourceText = "WEBVTT\n\nA header without any cues",
            ),
        )
    }
}
