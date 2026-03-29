package com.x3player.glasses.util

import com.x3player.glasses.data.PlaybackProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    @Test
    fun `resumes only in supported window`() {
        val progress = PlaybackProgress(
            videoId = 1L,
            uri = "content://videos/1",
            resumePositionMs = 45_000L,
            durationMs = 100_000L,
            completed = false,
            lastPlayedAt = 0L,
        )

        assertEquals(45_000L, resolveResumePosition(progress, autoResumeEnabled = true))
        assertEquals(0L, resolveResumePosition(progress.copy(resumePositionMs = 10_000L), autoResumeEnabled = true))
        assertEquals(0L, resolveResumePosition(progress.copy(resumePositionMs = 96_000L), autoResumeEnabled = true))
        assertEquals(0L, resolveResumePosition(progress, autoResumeEnabled = false))
    }

    @Test
    fun `marks playback complete near end`() {
        assertTrue(shouldMarkCompleted(95_000L, 100_000L))
        assertFalse(shouldMarkCompleted(50_000L, 100_000L))
    }
}
