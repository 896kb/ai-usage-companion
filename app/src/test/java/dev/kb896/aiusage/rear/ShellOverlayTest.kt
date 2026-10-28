package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellOverlayTest {

    @Test fun `the live blur follows the pull, none shut, BLUR_MAX open, in whole px`() {
        assertEquals(ShellOverlay.BLUR_MAX, ShellOverlay.blurPx(1f))
        assertEquals(0, ShellOverlay.blurPx(0f))
        assertEquals(ShellOverlay.BLUR_MAX / 2, ShellOverlay.blurPx(0.5f))
        // Nearby progress asks for the same radius, so a settling sheet sends no transaction a frame.
        assertEquals(ShellOverlay.blurPx(0.5f), ShellOverlay.blurPx(0.505f))
        assertEquals(ShellOverlay.BLUR_MAX, ShellOverlay.blurPx(3f))
        assertEquals(0, ShellOverlay.blurPx(-1f))
    }

    @Test fun `the dim follows the pull, a quarter at most, black`() {
        assertEquals(0, ShellOverlay.dimColor(0f))
        assertEquals((0.25f * 255).toInt() shl 24, ShellOverlay.dimColor(1f))
        assertEquals((0.25f * 255).toInt() shl 24, ShellOverlay.dimColor(2f))
        assertEquals(0, ShellOverlay.dimColor(1f) and 0xFFFFFF)
    }
}
