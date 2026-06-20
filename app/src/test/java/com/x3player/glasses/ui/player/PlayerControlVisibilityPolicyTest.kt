package com.x3player.glasses.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlVisibilityPolicyTest {
    @Test
    fun `playing controls auto hide only when no overlay or fatal error is visible`() {
        assertTrue(
            shouldAutoHidePlayerControls(
                controlsVisible = true,
                isPlaying = true,
                hasOpenOverlay = false,
                hasFatalError = false,
            ),
        )
        assertFalse(
            shouldAutoHidePlayerControls(
                controlsVisible = true,
                isPlaying = false,
                hasOpenOverlay = false,
                hasFatalError = false,
            ),
        )
        assertFalse(
            shouldAutoHidePlayerControls(
                controlsVisible = true,
                isPlaying = true,
                hasOpenOverlay = true,
                hasFatalError = false,
            ),
        )
        assertFalse(
            shouldAutoHidePlayerControls(
                controlsVisible = true,
                isPlaying = true,
                hasOpenOverlay = false,
                hasFatalError = true,
            ),
        )
    }

    @Test
    fun `repeated seeks accumulate and remain inside media bounds`() {
        var position = 5_000L
        repeat(4) {
            position = resolveSeekTarget(position, 30_000L, 10_000L)
        }
        assertEquals(30_000L, position)

        repeat(4) {
            position = resolveSeekTarget(position, 30_000L, -10_000L)
        }
        assertEquals(0L, position)
    }

    @Test
    fun `only supported forced or default embedded subtitles auto select`() {
        assertTrue(
            shouldAutoSelectEmbeddedSubtitle(
                isEmbedded = true,
                isSupported = true,
                isForced = true,
                isDefault = false,
            ),
        )
        assertTrue(
            shouldAutoSelectEmbeddedSubtitle(
                isEmbedded = true,
                isSupported = true,
                isForced = false,
                isDefault = true,
            ),
        )
        assertFalse(
            shouldAutoSelectEmbeddedSubtitle(
                isEmbedded = true,
                isSupported = true,
                isForced = false,
                isDefault = false,
            ),
        )
        assertFalse(
            shouldAutoSelectEmbeddedSubtitle(
                isEmbedded = false,
                isSupported = true,
                isForced = true,
                isDefault = true,
            ),
        )
    }
}
