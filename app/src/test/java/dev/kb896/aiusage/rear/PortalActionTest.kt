package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.PortalAction.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalActionTest {

    private val locked = { true }
    private val unlocked = { false }
    private val notAsked = { throw AssertionError("the keyguard is only read for Panel") }

    @Test fun `Panel with the front locked hides the guest on the front`() {
        assertEquals(Step.HIDE_ON_FRONT, PortalAction.strip(0, guestOnTop = true, hiddenOnFront = false, frontLocked = locked))
    }

    @Test fun `Panel with the front unlocked starts the panel over the guest`() {
        assertEquals(Step.START_PANEL, PortalAction.strip(0, guestOnTop = true, hiddenOnFront = false, frontLocked = unlocked))
    }

    @Test fun `Resume of a hidden guest brings it straight back`() {
        assertEquals(Step.UNHIDE, PortalAction.strip(0, guestOnTop = false, hiddenOnFront = true, frontLocked = notAsked))
    }

    @Test fun `Resume of a guest behind the panel steps the panel back`() {
        assertEquals(Step.PANEL_STEPS_BACK, PortalAction.strip(0, guestOnTop = false, hiddenOnFront = false, frontLocked = notAsked))
    }

    @Test fun `Front and Close, whatever the guest's state`() {
        for (onTop in listOf(true, false)) for (hidden in listOf(true, false)) {
            assertEquals(Step.FRONT, PortalAction.strip(1, onTop, hidden, notAsked))
            assertEquals(Step.CLOSE, PortalAction.strip(2, onTop, hidden, notAsked))
        }
    }

    @Test fun `no step for a button the strip does not have`() {
        assertNull(PortalAction.strip(3, true, false, notAsked))
        assertNull(PortalAction.strip(-1, false, false, notAsked))
    }

    @Test fun `reads the focused display`() {
        assertEquals(1, PortalAction.topFocusedDisplay("    mTopFocusedDisplayId=1\n"))
        assertEquals(0, PortalAction.topFocusedDisplay("  mGlobalConfiguration=...\n  mTopFocusedDisplayId=0"))
        assertNull(PortalAction.topFocusedDisplay(""))
        assertNull(PortalAction.topFocusedDisplay("Can't find service: window"))
    }

    @Test fun `rear focused - Xiaomi's Back reaches the guest, ours is not sent`() {
        assertFalse(PortalAction.sendBack(guestOnTop = true, sheetUp = false, focusedDisplay = 1, rear = 1))
    }

    @Test fun `front focused - ours goes to the guest`() {
        assertTrue(PortalAction.sendBack(guestOnTop = true, sheetUp = false, focusedDisplay = 0, rear = 1))
    }

    @Test fun `focus unknown - ours goes, as before the rule`() {
        assertTrue(PortalAction.sendBack(guestOnTop = true, sheetUp = false, focusedDisplay = null, rear = 1))
    }

    @Test fun `no Back of ours over the panel or with the sheet up`() {
        assertFalse(PortalAction.sendBack(guestOnTop = false, sheetUp = false, focusedDisplay = 0, rear = 1))
        assertFalse(PortalAction.sendBack(guestOnTop = true, sheetUp = true, focusedDisplay = 0, rear = 1))
    }

    @Test fun `a guarded panel takes no pull, a guest on top always does`() {
        assertFalse(PortalAction.sheetOpens(panelGuarded = true, guestOnTop = false))
        assertTrue(PortalAction.sheetOpens(panelGuarded = true, guestOnTop = true))
        assertTrue(PortalAction.sheetOpens(panelGuarded = false, guestOnTop = false))
        assertTrue(PortalAction.sheetOpens(panelGuarded = false, guestOnTop = true))
    }
}
