package dev.kb896.aiusage.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Always-on after the lock: whether the back is lit again once the power button took it dark
 * with the front. Decided from what was on the back AT the lock.
 */
class LockRelightTest {

    @Test fun `the panel, awake, dark with the front - lit again into always-on`() {
        assertTrue(LockRelight.relight(wentDarkTogether = true, dozingAtLock = false, guestOnTopAtLock = false))
    }

    @Test fun `a back that went dark on its own, earlier, stays dark`() {
        assertFalse(LockRelight.relight(wentDarkTogether = false, dozingAtLock = false, guestOnTopAtLock = false))
    }

    @Test fun `a guest on top at the lock is never shown at full brightness on a locked phone`() {
        assertFalse(LockRelight.relight(wentDarkTogether = true, dozingAtLock = false, guestOnTopAtLock = true))
    }

    @Test fun `a panel already letting go - double tap, pocket - stays asleep`() {
        assertFalse(LockRelight.relight(wentDarkTogether = true, dozingAtLock = true, guestOnTopAtLock = false))
    }

    @Test fun `not knowing what was on the back is no relight`() {
        assertFalse(LockRelight.relight(wentDarkTogether = true, dozingAtLock = null, guestOnTopAtLock = false))
        assertFalse(LockRelight.relight(wentDarkTogether = true, dozingAtLock = false, guestOnTopAtLock = null))
    }
}
