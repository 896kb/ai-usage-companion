package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeStashTest {
    private val w = 904f

    @Test fun `in view, the stack follows a finger to the right and no further left than home`() {
        assertEquals(100f, NoticeStash.offset(stashed = false, dx = 100f, width = w), 0f)
        assertEquals(0f, NoticeStash.offset(stashed = false, dx = -100f, width = w), 0f)
    }

    @Test fun `put away, it comes back under a finger from the right`() {
        assertEquals(w - 200f, NoticeStash.offset(stashed = true, dx = -200f, width = w), 0f)
        assertEquals(w, NoticeStash.offset(stashed = true, dx = 50f, width = w), 0f)
    }

    @Test fun `let go past a quarter of the way it goes, short of that it springs back`() {
        assertEquals(true, NoticeStash.release(stashed = false, dx = w * 0.3f, width = w))
        assertEquals(false, NoticeStash.release(stashed = false, dx = w * 0.1f, width = w))
        assertEquals(false, NoticeStash.release(stashed = true, dx = -w * 0.3f, width = w))
        assertEquals(true, NoticeStash.release(stashed = true, dx = -w * 0.1f, width = w))
    }

    @Test fun `a swipe the wrong way changes nothing`() {
        assertEquals(false, NoticeStash.release(stashed = false, dx = -w, width = w))
        assertEquals(true, NoticeStash.release(stashed = true, dx = w, width = w))
    }
}
