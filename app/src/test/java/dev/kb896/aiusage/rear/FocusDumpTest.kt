package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusDumpTest {

    // Trimmed from a live dump with the camera on the rear display (17 Pro, 2026-10-05).
    private val dump = """
          Display: mDisplayId=1 (organized)
          mCurrentFocus=null
          mFocusedApp=ActivityRecord{210822154 u0 com.android.camera/.Camera t72093}
          mInputMethodWindow=null  Display: mDisplayId=0 (organized)
          mCurrentFocus=Window{e383b23 u0 com.android.camera/com.android.camera.fragment.presentation.MainScreenSelfieActivity}
          mFocusedApp=ActivityRecord{229004283 u0 dev.kb896.aiusage/.ui.MainActivity t72094}
    """.trimIndent()

    @Test fun `reads the rear display's focused app`() {
        assertEquals("com.android.camera", FocusDump.focusedPackage(dump, 1))
    }

    @Test fun `never takes another display's focus`() {
        assertEquals("dev.kb896.aiusage", FocusDump.focusedPackage(dump, 0))
    }

    @Test fun `null for a display the dump does not have`() {
        assertNull(FocusDump.focusedPackage(dump, 2))
    }

    @Test fun `reads the rear display's focused task`() {
        assertEquals(72093, FocusDump.focusedTask(dump, 1))
        assertEquals(72094, FocusDump.focusedTask(dump, 0))
        assertNull(FocusDump.focusedTask(dump, 2))
    }

    @Test fun `null when the display has no focused app`() {
        assertNull(FocusDump.focusedPackage("Display: mDisplayId=1 (organized)\n  mCurrentFocus=null\n", 1))
    }
}
