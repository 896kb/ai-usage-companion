package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellSheetTest {

    @Test fun `the live blur follows the ground, none shut, BLUR_MAX all in, in whole px`() {
        assertEquals(ShellSheet.BLUR_MAX, ShellSheet.blurPx(1f))
        assertEquals(0, ShellSheet.blurPx(0f))
        assertEquals(ShellSheet.BLUR_MAX / 2, ShellSheet.blurPx(0.5f))
        // Nearby progress asks for the same radius, so a settling sheet sends no transaction a frame.
        assertEquals(ShellSheet.blurPx(0.5f), ShellSheet.blurPx(0.505f))
        assertEquals(ShellSheet.BLUR_MAX, ShellSheet.blurPx(3f))
        assertEquals(0, ShellSheet.blurPx(-1f))
    }

    @Test fun `the dim follows the ground, a quarter at most, black`() {
        assertEquals(0, ShellSheet.dimColor(0f))
        assertEquals((0.25f * 255).toInt() shl 24, ShellSheet.dimColor(1f))
        assertEquals((0.25f * 255).toInt() shl 24, ShellSheet.dimColor(2f))
        assertEquals(0, ShellSheet.dimColor(1f) and 0xFFFFFF)
    }

    @Test fun `the ground is all in once the strip is, so the strip alone is blurred as the full sheet`() {
        assertEquals(0f, ShellSheet.ground(0f), 0f)
        assertEquals(0.5f, ShellSheet.ground(PortalSheet.PEEK / 2), 1e-4f)
        assertEquals(1f, ShellSheet.ground(PortalSheet.PEEK), 0f)
        assertEquals(1f, ShellSheet.ground(PortalSheet.FULL), 0f)
    }

    @Test fun `the crop is the sheet, from its top edge down`() {
        assertEquals(572, ShellSheet.cropTop(0f))
        assertEquals(572 - 88, ShellSheet.cropTop(PortalSheet.PEEK))
        assertEquals(0, ShellSheet.cropTop(PortalSheet.FULL))
        assertEquals(0, ShellSheet.cropTop(PortalSheet.FULL + 50f))
        assertEquals(472, ShellSheet.cropTop(100.4f))
    }
}
