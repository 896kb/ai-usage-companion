package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Info page's notifications, pulled down into view and pushed back up; one card swiped away. */
class NoticeRevealTest {
    private val h = 300f
    private val w = 904f

    @Test fun `hidden, they follow a finger down and no further up than hidden`() {
        assertEquals(100f, NoticeReveal.offset(shown = false, dy = 100f, height = h), 0f)
        assertEquals(0f, NoticeReveal.offset(shown = false, dy = -100f, height = h), 0f)
        assertEquals(h, NoticeReveal.offset(shown = false, dy = 2 * h, height = h), 0f)
    }

    @Test fun `shown, they go back up under a finger`() {
        assertEquals(h - 120f, NoticeReveal.offset(shown = true, dy = -120f, height = h), 0f)
        assertEquals(h, NoticeReveal.offset(shown = true, dy = 50f, height = h), 0f)
    }

    @Test fun `let go past a quarter of the way it goes, short of that it springs back`() {
        assertTrue(NoticeReveal.release(shown = false, dy = h * 0.3f, height = h))
        assertFalse(NoticeReveal.release(shown = false, dy = h * 0.1f, height = h))
        assertFalse(NoticeReveal.release(shown = true, dy = -h * 0.3f, height = h))
        assertTrue(NoticeReveal.release(shown = true, dy = -h * 0.1f, height = h))
    }

    @Test fun `a swipe the wrong way changes nothing`() {
        assertFalse(NoticeReveal.release(shown = false, dy = -h, height = h))
        assertTrue(NoticeReveal.release(shown = true, dy = h, height = h))
    }

    @Test fun `a card swiped right past a quarter goes, otherwise it springs back`() {
        assertTrue(NoticeReveal.dismissed(dx = w * 0.3f, width = w))
        assertFalse(NoticeReveal.dismissed(dx = w * 0.1f, width = w))
        assertFalse(NoticeReveal.dismissed(dx = -w, width = w))
    }

    @Test fun `a card follows the finger right only`() {
        assertEquals(80f, NoticeReveal.dismissOffset(dx = 80f, width = w), 0f)
        assertEquals(0f, NoticeReveal.dismissOffset(dx = -80f, width = w), 0f)
    }

    @Test fun `a flick decides whatever the distance`() {
        assertTrue(NoticeReveal.release(shown = false, dy = 10f, height = h, velocity = 1f))
        assertFalse(NoticeReveal.release(shown = true, dy = -10f, height = h, velocity = -1f))
    }
}
