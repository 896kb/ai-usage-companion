package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetPagerTest {

    private val stride = 528f

    private fun pager(pages: Int = 3) = SheetPager(stride).apply { this.pages = pages }

    /** A slow drag from x 600 by [dx], let go well after its last move: no speed left. */
    private fun SheetPager.slowDrag(dx: Float, at: Long = 0): Int {
        grab(600f, at)
        drag(600f + dx / 2, at + 300)
        drag(600f + dx, at + 600)
        return release(600f + dx, at + 600 + SheetMotion.STILL_MS + 50)
    }

    @Test fun `the apps follow the finger, left bringing the next ones in`() {
        val p = pager()
        p.grab(600f, 0)
        assertEquals(0.5f, p.drag(600f - stride / 2, 100), 1e-4f)
    }

    @Test fun `let go with no speed it stays where it was left, between pages`() {
        val p = pager()
        p.slowDrag(-stride * 0.37f)
        assertFalse(p.settling)
        assertEquals(0.37f, p.position(5_000), 1e-3f)
        val q = pager()
        q.slowDrag(-stride * 1.6f)
        assertEquals(1.6f, q.position(5_000), 1e-3f)
    }

    @Test fun `a flick glides on past where it was let go, and slows to a stop`() {
        val p = pager(4)
        p.grab(600f, 0)
        p.drag(560f, 10)
        p.drag(520f, 20)
        p.release(520f, 25)
        assertTrue(p.settling)
        val let = p.position(25)
        val soon = p.position(125)
        val later = p.position(425)
        assertTrue(soon > let)
        assertTrue(later > soon)
        // Slowing: less ground in the later, longer stretch than its share.
        assertTrue(later - soon < (soon - let) * 3f)
        val end = p.at(10_000)
        assertFalse(p.settling)
        assertEquals(end, p.position(20_000), 0f)
        assertTrue(end > later - 1e-3f)
    }

    @Test fun `a glide stops at the last page`() {
        val p = pager(2)
        p.grab(600f, 0)
        p.drag(400f, 10)
        p.drag(200f, 20)
        p.release(200f, 25)
        assertEquals(1f, p.at(10_000), 1e-3f)
    }

    @Test fun `past the ends the apps give a third, and come back`() {
        val p = pager()
        p.grab(600f, 0)
        assertEquals(-0.1f, p.drag(600f + stride * 0.3f, 100), 1e-4f)
        assertEquals(0, p.release(600f + stride * 0.3f, 400))
        assertTrue(p.settling)
        assertEquals(0f, p.at(400 + ControlCenter.SETTLE_MAX_MS + 1), 1e-4f)
        assertFalse(p.settling)
    }

    @Test fun `caught mid-glide it is held where it stands`() {
        val p = pager(4)
        p.grab(600f, 0)
        p.drag(560f, 10)
        p.drag(520f, 20)
        p.release(520f, 25)
        val t = 150L
        val mid = p.position(t)
        p.grab(500f, t)
        assertEquals(mid, p.position(t), 1e-4f)
        assertFalse(p.settling)
    }

    @Test fun `a tap on a dot goes to that page`() {
        val p = pager()
        p.show(2, 0)
        assertEquals(2, p.page)
        assertEquals(2f, p.at(10_000), 1e-4f)
    }

    @Test fun `fewer pages bring it back within them, and reset is the first page`() {
        val p = pager(3)
        p.show(2, 0); p.at(10_000)
        p.pages = 2
        assertEquals(1, p.page)
        assertEquals(1f, p.position(10_000), 0f)
        p.reset()
        assertEquals(0, p.page)
        assertEquals(0f, p.position(10_000), 0f)
    }

    @Test fun `one page does not scroll away`() {
        val p = pager(1)
        p.slowDrag(-stride * 0.6f)
        assertEquals(0f, p.at(10_000), 1e-4f)
    }
}
