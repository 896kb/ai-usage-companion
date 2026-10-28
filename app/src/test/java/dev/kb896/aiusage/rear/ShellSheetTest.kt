package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellSheetTest {

    @Test fun `the live blur follows the pull, none shut, BLUR_MAX open, in whole px`() {
        assertEquals(ShellSheet.BLUR_MAX, ShellSheet.blurPx(1f))
        assertEquals(0, ShellSheet.blurPx(0f))
        assertEquals(ShellSheet.BLUR_MAX / 2, ShellSheet.blurPx(0.5f))
        // Nearby progress asks for the same radius, so a settling sheet sends no transaction a frame.
        assertEquals(ShellSheet.blurPx(0.5f), ShellSheet.blurPx(0.505f))
        assertEquals(ShellSheet.BLUR_MAX, ShellSheet.blurPx(3f))
        assertEquals(0, ShellSheet.blurPx(-1f))
    }

    @Test fun `the dim follows the pull, a quarter at most, black`() {
        assertEquals(0, ShellSheet.dimColor(0f))
        assertEquals((0.25f * 255).toInt() shl 24, ShellSheet.dimColor(1f))
        assertEquals((0.25f * 255).toInt() shl 24, ShellSheet.dimColor(2f))
        assertEquals(0, ShellSheet.dimColor(1f) and 0xFFFFFF)
    }
}
