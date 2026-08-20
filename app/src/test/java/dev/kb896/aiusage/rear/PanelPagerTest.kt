package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapping scroller, driven through the gestures it exists to get right.
 *
 * All of its failure modes are sequences — a flick that should carry, a drag that should
 * fall back, a grab mid-animation — and none of them can be seen in a single frame on the
 * back of a phone. This is the whole reason it is a class with an injected clock rather
 * than state smeared through the view.
 */
class PanelPagerTest {

    private val span = 572f

    private fun pager(pages: Int = 2) = PanelPager(span, 300f).apply { this.pages = pages }

    /** A long drag upward lands on the next page. */
    @Test fun `dragging most of a page commits to it`() {
        val p = pager()
        p.onDown(500f, 0L)
        p.onMove(100f, 100L)
        p.onUp(100L)
        assertEquals(span, p.advance(10_000L), 0.01f)
        assertEquals(1, p.page)
    }

    /** A small drag falls back. */
    @Test fun `a short slow drag falls back`() {
        val p = pager()
        p.onDown(500f, 0L)
        // 40 units over a second: past the slop, nowhere near the commit threshold, and
        // far too slow to read as a flick.
        p.onMove(460f, 1_000L)
        p.onUp(1_000L)
        assertEquals(0f, p.advance(10_000L), 0.01f)
        assertEquals(0, p.page)
    }

    /**
     * The one that makes this usable. A panel 572 tall being held in one hand does not
     * get long deliberate drags, so a quick flick has to count on its own.
     */
    @Test fun `a short fast flick carries to the next page`() {
        val p = pager()
        p.onDown(500f, 0L)
        p.onMove(440f, 20L)
        p.onMove(380f, 40L)
        p.onMove(320f, 60L)
        p.onUp(60L)
        assertEquals("a flick did not carry", span, p.advance(10_000L), 0.01f)
    }

    @Test fun `a flick the other way comes back`() {
        val p = pager()
        p.goTo(1, 0L)
        p.advance(10_000L)
        p.onDown(100f, 10_000L)
        p.onMove(160f, 10_020L)
        p.onMove(220f, 10_040L)
        p.onMove(280f, 10_060L)
        p.onUp(10_060L)
        assertEquals(0f, p.advance(20_000L), 0.01f)
    }

    @Test fun `a tap is not a drag`() {
        val p = pager()
        p.onDown(300f, 0L)
        assertFalse("a 4-unit wobble counted as a drag", p.onMove(304f, 16L))
        assertFalse(p.dragging)
        p.onUp(16L)
        assertEquals(0f, p.advance(10_000L), 0.01f)
    }

    @Test fun `the scroll cannot run past either end`() {
        val p = pager()
        p.onDown(500f, 0L)
        p.onMove(-4_000f, 100L)
        assertEquals("scrolled past the last page", span, p.offset, 0.01f)
        p.onMove(4_000f, 200L)
        assertEquals("scrolled above the first page", 0f, p.offset, 0.01f)
    }

    /** Grabbing it mid-snap continues from where it had got to, rather than jumping. */
    @Test fun `a touch during the settle adopts the current position`() {
        val p = pager()
        p.onDown(500f, 0L)
        p.onMove(100f, 100L)
        p.onUp(100L)
        // Sampled inside the settle window. The duration shrinks with the distance left
        // — 172 units of a 572 page settles in the 90ms floor, not the full 300 — so
        // anything past 190 would already have arrived.
        val midway = p.advance(140L)
        assertTrue("nothing had moved yet", midway > 0f && midway < span)

        p.onDown(300f, 140L)
        assertEquals("the position jumped on touch", midway, p.offset, 0.01f)
        // And it stays: a finger down cancels the settle rather than letting it run on
        // underneath, which would drag the page out from under the person holding it.
        assertEquals("the settle carried on under the finger",
            midway, p.advance(10_000L), 0.01f)
    }

    /**
     * The media page disappears when the music stops, and it can do that while you are
     * looking at it. Without this the panel would be scrolled to a page that no longer
     * exists, showing nothing at all.
     */
    @Test fun `losing a page pulls the scroll back into range`() {
        val p = pager()
        p.goTo(1, 0L)
        p.advance(10_000L)
        assertEquals(span, p.offset, 0.01f)
        p.pages = 1
        assertEquals("left stranded past the end", 0f, p.offset, 0.01f)
        assertEquals(0f, p.advance(20_000L), 0.01f)
    }

    @Test fun `one page means paging is off`() {
        val p = pager(pages = 1)
        assertFalse(p.enabled())
        p.onDown(500f, 0L)
        p.onMove(100f, 100L)
        assertEquals("scrolled with nowhere to go", 0f, p.offset, 0.01f)
    }

    @Test fun `the settle is shorter for a shorter distance`() {
        val near = pager()
        near.onDown(500f, 0L)
        near.onMove(480f, 50L)   // barely moved, but past the slop
        near.onUp(50L)
        // A release this close to where it started must land within the minimum, not
        // take the full page duration to travel twenty units.
        assertEquals(0f, near.advance(200L), 0.01f)
    }
}
