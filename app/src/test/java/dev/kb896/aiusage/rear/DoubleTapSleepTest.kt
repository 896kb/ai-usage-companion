package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapSleepTest {
    @Test
    fun `a double tap dims, a second one during the dim changes nothing, reset ends it`() {
        val s = DoubleTapSleep()
        assertFalse(s.dimming)
        assertTrue(s.doubleTap(1_000))
        assertTrue(s.dimming)
        assertFalse(s.doubleTap(3_000))
        assertEquals(1_000L, s.dimmedAt)
        s.reset()
        assertFalse(s.dimming)
        assertTrue(s.doubleTap(5_000))
    }

    @Test
    fun `put to sleep by the double tap, every wake raises the slider until a swipe`() {
        val s = DoubleTapSleep()
        assertFalse(s.guardsWake)
        s.doubleTap(1_000)
        s.wentOff()
        assertFalse(s.dimming)
        assertTrue(s.guardsWake)
        // woken, nobody swiped, the lock ran out and handed back behind a dark screen:
        // the tap-to-wake in a pocket after it still finds the slider up
        s.unlocked(bySwipe = false)
        assertTrue(s.guardsWake)
        s.unlocked(bySwipe = true)
        assertFalse(s.guardsWake)
    }

    @Test
    fun `a dim called off is no sleep - nothing to guard`() {
        val s = DoubleTapSleep()
        s.doubleTap(1_000)
        s.reset()
        assertFalse(s.guardsWake)
    }
}
