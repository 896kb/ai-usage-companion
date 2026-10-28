package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellSheetTest {

    @Test fun `the blur is the control centre's at full, none shut, in half pixels`() {
        assertEquals(22f, ShellSheet.blurRadius(1f), 0f)
        assertEquals(0f, ShellSheet.blurRadius(0f), 0f)
        assertEquals(11f, ShellSheet.blurRadius(0.5f), 0f)
        // Nearby progress asks for the same effect, so a settling sheet does not mint one a frame.
        assertEquals(ShellSheet.blurRadius(0.5f), ShellSheet.blurRadius(0.505f), 0f)
        assertEquals(22f, ShellSheet.blurRadius(3f), 0f)
    }

    @Test fun `the dim follows the pull and the ground, a quarter at most`() {
        assertEquals(0, ShellSheet.dimColor(0f, 1f))
        assertEquals(0, ShellSheet.dimColor(1f, 0f))
        assertEquals((0.25f * 255).toInt() shl 24, ShellSheet.dimColor(1f, 1f))
        assertEquals(0, ShellSheet.dimColor(1f, 1f) and 0xFFFFFF)
    }
}
