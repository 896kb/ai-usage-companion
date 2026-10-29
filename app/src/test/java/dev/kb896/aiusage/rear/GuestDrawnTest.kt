package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDrawnTest {

    /** One window of `dumpsys window <pkg>`, trimmed from the phone (2026-10-28), with its parts made to vary. */
    private fun window(
        display: Int = 1, task: Int = 75481, type: String = "BASE_APPLICATION",
        w: Int = 904, h: Int = 572, surface: Boolean = true, state: String = "HAS_DRAWN",
    ) = """
          Window #0 Window{ad14fd3 u0 com.brave.browser/org.chromium.chrome.browser.ChromeTabbedActivity}:
            mDisplayId=$display taskId=$task mSession=Session{56053e9 21677:u0a10388} mClient=android.os.BinderProxy@ecdd5c2
            mOwnerUid=10388 showForAllUsers=false package=com.brave.browser appop=NONE
            mAttrs={(0,0)(fillxfill) sim={state=always_hidden adjust=resize forwardNavigation} layoutInDisplayCutoutMode=always pkg = com.brave.browser adpkg = null ty=$type fmt=TRANSLUCENT wanim=0x103030d sysuil=true
              fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
            Requested w=$w h=$h mLayoutSeq=45972
            mHasSurface=$surface isReadyForDisplay()=$surface mWindowRemovalAllowed=false
            Frames: parent=[0,0][904,572] display=[0,0][904,572] frame=[0,0][904,572] last=[0,0][904,572] insetsChanged=false
            WindowStateAnimator{1ce0e2e com.brave.browser/org.chromium.chrome.browser.ChromeTabbedActivity}:
              mDrawState=$state       mLastHidden=false
            isOnScreen=true
    """.trimIndent()

    private fun drawn(dump: String) = GuestDrawn.drawn(dump, task = 75481, display = 1, width = 904, height = 572)

    @Test fun `its app window on the rear, laid out at the rear's size and drawn`() {
        assertTrue(drawn(window()))
    }

    @Test fun `not while it still asks for the front's size - it has not redrawn since the move`() {
        assertFalse(drawn(window(w = 1220, h = 2656)))
    }

    @Test fun `not until its draw has reached the screen`() {
        for (s in listOf("DRAW_PENDING", "COMMIT_DRAW_PENDING", "READY_TO_SHOW", "NO_SURFACE")) assertFalse(s, drawn(window(state = s)))
        assertFalse(drawn(window(surface = false)))
    }

    @Test fun `not on the front, nor another task's, nor a window that is not the app's own`() {
        assertFalse(drawn(window(display = 0)))
        assertFalse(drawn(window(task = 1)))
        assertFalse(drawn(window(type = "APPLICATION_STARTING")))
    }

    @Test fun `any one of its windows will do, each read on its own`() {
        // A stale front-sized window first, a drawn one after: the second counts, and the
        // first's lines never lend themselves to it.
        assertTrue(drawn(window(w = 1220, h = 2656, state = "DRAW_PENDING") + "\n" + window()))
        assertFalse(drawn(window(state = "DRAW_PENDING") + "\n" + window(display = 0)))
    }

    @Test fun `nothing read, nothing drawn`() {
        assertFalse(drawn(""))
        assertFalse(drawn("Can't find service: window"))
    }
    @Test fun `a guest kept right of the cameras is drawn at its own size, not the display's`() {
        val confined = window(w = 608, h = 572)
        assertTrue(GuestDrawn.drawn(confined, task = 75481, display = 1, width = 608, height = 572))
        assertFalse(GuestDrawn.drawn(window(), task = 75481, display = 1, width = 608, height = 572))
    }

    @Test fun `a guest that does not fill its shape is drawn all the same`() {
        // Letterboxed — portrait only, size compat after a DPI change, no multi-window — it asks
        // for less than the rear and never for the rear's size: it is drawn, not still coming.
        assertTrue(drawn(window(w = 322, h = 572)))
        assertTrue(drawn(window(w = 904, h = 500)))
        assertTrue(GuestDrawn.drawn(window(w = 600, h = 572), task = 75481, display = 1, width = 608, height = 572))
        // Bigger than its shape either way is still the front's size, or not yet laid out for it.
        assertFalse(drawn(window(w = 905, h = 572)))
        assertFalse(drawn(window(w = 2656, h = 1220)))
    }

    @Test fun `a window that has asked for no size yet is not drawn`() {
        assertFalse(drawn(window(w = 0, h = 0)))
        assertFalse(drawn(window(w = 904, h = 0)))
    }
}
