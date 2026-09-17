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
}
