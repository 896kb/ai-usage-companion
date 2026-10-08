package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When "Keep panel awake" takes the screen hold back. The case that kept the back lit all
 * night: woken from the double tap's sleep by a notification, nobody swiped, the lock ran
 * out and let the display go — and the next display event on its way down re-took the hold
 * because the panel is meant to stay awake.
 */
class KeepAwakeHoldTest {

    @Test fun `keep awake takes a released hold back`() {
        assertTrue(KeepAwakeHold.retake(keepAwake = true, holding = false, lettingGo = false))
    }

    @Test fun `not while a spent lock is letting the display go`() {
        assertFalse(KeepAwakeHold.retake(keepAwake = true, holding = false, lettingGo = true))
    }

    @Test fun `nothing to take when it is already held`() {
        assertFalse(KeepAwakeHold.retake(keepAwake = true, holding = true, lettingGo = false))
    }

    @Test fun `without keep awake the sleep timer decides, not this`() {
        assertFalse(KeepAwakeHold.retake(keepAwake = false, holding = false, lettingGo = false))
    }
}
