package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetPagerTest {

    private val stride = PortalSheet.PAGE_STRIDE

    private fun pager(pages: Int = 3) = SheetPager().apply { this.pages = pages }

    /** A slow drag from x 600 by [dx], let go well after its last move. */
    private fun SheetPager.slowDrag(dx: Float, at: Long = 0): Int {
        grab(600f, at)
        drag(600f + dx / 2, at + 300)
        drag(600f + dx, at + 600)
        return release(600f + dx, at + 600 + SheetMotion.STILL_MS + 50)
    }

    @Test fun `the pages follow the finger, left bringing the next one in`() {
        val p = pager()
        p.grab(600f, 0)
        assertEquals(0.5f, p.drag(600f - stride / 2, 100), 1e-4f)
    }

    @Test fun `a drag past a fifth of a page turns it, short of that it goes back`() {
        assertEquals(1, pager().slowDrag(-stride * 0.25f))
        assertEquals(0, pager().slowDrag(-stride * 0.15f))
    }

    @Test fun `a flick turns it however short`() {
        val p = pager()
        p.grab(600f, 0)
        p.drag(590f, 10)
        p.drag(560f, 20)
        assertEquals(1, p.release(560f, 25))
    }

    @Test fun `one page at a time, even dragged across two`() {
        assertEquals(1, pager(4).slowDrag(-stride * 1.8f))
    }

    @Test fun `past the ends the pages give a third, and come back`() {
        val p = pager()
        p.grab(600f, 0)
        assertEquals(-0.1f, p.drag(600f + stride * 0.3f, 100), 1e-4f)
        assertEquals(0, p.release(600f + stride * 0.3f, 400))
        val last = pager(2)
        last.slowDrag(-stride * 0.5f)
        last.at(10_000)
        assertEquals(1, last.slowDrag(-stride * 0.9f, 20_000))
    }

    @Test fun `let go, it settles onto the page and comes to rest`() {
        val p = pager()
        p.slowDrag(-stride * 0.4f)
        assertTrue(p.settling)
        val end = 650 + SheetMotion.STILL_MS + ControlCenter.SETTLE_MAX_MS
        assertEquals(1f, p.at(end + 1), 1e-4f)
        assertFalse(p.settling)
        assertEquals(1, p.page)
    }

    @Test fun `caught mid-settle it is held where it stands`() {
        val p = pager()
        p.slowDrag(-stride * 0.4f)
        val t = 750L
        val mid = p.position(t)
        p.grab(500f, t)
        assertEquals(mid, p.position(t), 1e-4f)
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

    @Test fun `one page does not turn`() {
        assertEquals(0, pager(1).slowDrag(-stride * 0.6f))
    }
}
