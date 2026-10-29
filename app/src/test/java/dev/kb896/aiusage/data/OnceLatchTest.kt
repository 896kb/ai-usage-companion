package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnceLatchTest {

    @Test fun `it sends the first time, and never again once sent`() {
        val latch = OnceLatch()
        var sent = 0
        repeat(3) { latch.fire { sent++; true } }
        assertEquals(1, sent)
        assertTrue(latch.fired)
    }

    @Test fun `a send with nobody to take it is tried again next time`() {
        val latch = OnceLatch()
        var tries = 0
        latch.fire { tries++; false }
        assertFalse(latch.fired)
        latch.fire { tries++; true }
        latch.fire { tries++; true }
        assertEquals(2, tries)
        assertTrue(latch.fired)
    }

    @Test fun `a send that throws is not sent, and does not throw out of the latch`() {
        val latch = OnceLatch()
        latch.fire { throw IllegalStateException("the listener died") }
        assertFalse(latch.fired)
        latch.fire { true }
        assertTrue(latch.fired)
    }
}
