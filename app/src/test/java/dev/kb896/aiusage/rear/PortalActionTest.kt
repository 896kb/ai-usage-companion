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

    @Test fun `a right-edge swipe is the guest's Back only while it is on top and the sheet is down`() {
        assertTrue(PortalAction.wantsBack(guestOnTop = true, sheetUp = false))
        assertFalse(PortalAction.wantsBack(guestOnTop = false, sheetUp = false))
        assertFalse(PortalAction.wantsBack(guestOnTop = true, sheetUp = true))
        assertFalse(PortalAction.wantsBack(guestOnTop = false, sheetUp = true))
    }

    @Test fun `the back navigation's callback is invoked only when it is the guest's`() {
        assertEquals(PortalAction.BackStep.INVOKE,
            PortalAction.backStep(navigation = true, focusedTask = 75481, guestTask = 75481, callback = true))
    }

    @Test fun `focus on another task - the front's app, say - nothing is invoked`() {
        assertEquals(PortalAction.BackStep.NOT_THE_GUEST,
            PortalAction.backStep(navigation = true, focusedTask = 75435, guestTask = 75481, callback = true))
        // Not even when it has no callback to speak of: it is still not the guest's.
        assertEquals(PortalAction.BackStep.NOT_THE_GUEST,
            PortalAction.backStep(navigation = true, focusedTask = -1, guestTask = 75481, callback = false))
    }

    @Test fun `no navigation, or the guest's with no callback, invokes nothing`() {
        assertEquals(PortalAction.BackStep.NO_NAVIGATION,
            PortalAction.backStep(navigation = false, focusedTask = 75481, guestTask = 75481, callback = true))
        assertEquals(PortalAction.BackStep.NO_CALLBACK,
            PortalAction.backStep(navigation = true, focusedTask = 75481, guestTask = 75481, callback = false))
    }

    @Test fun `a guarded panel takes no pull, a guest on top always does`() {
        assertFalse(PortalAction.sheetOpens(panelGuarded = true, guestOnTop = false))
        assertTrue(PortalAction.sheetOpens(panelGuarded = true, guestOnTop = true))
        assertTrue(PortalAction.sheetOpens(panelGuarded = false, guestOnTop = false))
        assertTrue(PortalAction.sheetOpens(panelGuarded = false, guestOnTop = true))
    }
    @Test fun `the sheet has a guest from the moment it lands, not only once it has survived its move`() {
        // Landed on the rear, the two-second survive wait still running: its buttons are there.
        assertEquals(true, PortalAction.sheetGuest(landed = true, placed = false, onTop = true))
        // Placed (survived), on top or behind the panel.
        assertEquals(true, PortalAction.sheetGuest(landed = true, placed = true, onTop = true))
        assertEquals(false, PortalAction.sheetGuest(landed = true, placed = true, onTop = false))
        // Sent to the back from the front: placed at once, never "landed" by a launch.
        assertEquals(true, PortalAction.sheetGuest(landed = false, placed = true, onTop = true))
        // Started on the front and not yet moved: nobody's guest on the rear yet.
        assertNull(PortalAction.sheetGuest(landed = false, placed = false, onTop = false))
        assertNull(PortalAction.sheetGuest(landed = false, placed = false, onTop = true))
    }
    @Test fun `an app's icon launches it, unless it is the guest already`() {
        // No guest, or another app the guest: launched.
        assertEquals(PortalAction.AppTap.LAUNCH, PortalAction.appTap("a.b", guestPkg = null, guestOnTop = null))
        assertEquals(PortalAction.AppTap.LAUNCH, PortalAction.appTap("a.b", guestPkg = "c.d", guestOnTop = true))
        assertEquals(PortalAction.AppTap.LAUNCH, PortalAction.appTap("a.b", guestPkg = "c.d", guestOnTop = false))
        // The guest itself, on top: nothing to open, the sheet only shuts.
        assertEquals(PortalAction.AppTap.SHUT, PortalAction.appTap("a.b", guestPkg = "a.b", guestOnTop = true))
        // The guest itself, behind the panel or hidden on the front: brought back, as Resume.
        assertEquals(PortalAction.AppTap.RESUME, PortalAction.appTap("a.b", guestPkg = "a.b", guestOnTop = false))
        // A guest the sheet does not show (none as far as it knows): launched as before.
        assertEquals(PortalAction.AppTap.LAUNCH, PortalAction.appTap("a.b", guestPkg = "a.b", guestOnTop = null))
    }
    @Test fun `a Back to home or to another task closes the guest's task - the fade starts at once`() {
        assertTrue(PortalAction.backMayClose(PortalAction.BACK_RETURN_TO_HOME))
        assertTrue(PortalAction.backMayClose(PortalAction.BACK_CROSS_TASK))
    }

    @Test fun `a Back within the app, or its own callback, says nothing in advance - only the checks tell`() {
        assertFalse(PortalAction.backMayClose(PortalAction.BACK_CALLBACK))
        assertFalse(PortalAction.backMayClose(PortalAction.BACK_CROSS_ACTIVITY))
        assertFalse(PortalAction.backMayClose(PortalAction.BACK_DIALOG_CLOSE))
        assertFalse(PortalAction.backMayClose(-1))
    }

    @Test fun `after a Back the guest is looked for soon, then less soon, within a second`() {
        val at = PortalAction.BACK_CHECKS_MS
        assertEquals(listOf(120L, 300L, 700L), at)
        assertTrue(at.zipWithNext().all { (a, b) -> b > a })
        assertTrue(at.last() < 1_000)
    }
}
