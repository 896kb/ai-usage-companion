package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoticeWheelTest {
    @Test fun `on the newest, a new one comes in from above — the one in view moves down to make room`() {
        val a = NoticeWheel.afterArrival(at = 0f, held = false)
        assertEquals(1f, a.at, 0f)        // the same card, now one further back
        assertEquals(0f, a.settleTo!!, 0f) // and the wheel turns to the new one
    }

    @Test fun `part-way through turning to the newest, it keeps turning to the newer one`() {
        val a = NoticeWheel.afterArrival(at = 0.4f, held = false)
        assertEquals(1.4f, a.at, 1e-6f)
        assertEquals(0f, a.settleTo!!, 0f)
    }

    @Test fun `reading an older one, it stays on it`() {
        val a = NoticeWheel.afterArrival(at = 3f, held = false)
        assertEquals(4f, a.at, 0f)
        assertNull(a.settleTo)
    }

    @Test fun `under a finger, the wheel is left where the finger has it`() {
        val a = NoticeWheel.afterArrival(at = 0f, held = true)
        assertEquals(1f, a.at, 0f)
        assertNull(a.settleTo)
    }
}
