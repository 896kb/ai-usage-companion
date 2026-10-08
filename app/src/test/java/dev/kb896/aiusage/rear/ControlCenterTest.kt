package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The control centre: where it is pulled from, how it opens, and the brightness cell's numbers. */
class ControlCenterTest {

    @Test fun `it is pulled from the very top edge, over the left half of the usable area`() {
        assertTrue(ControlCenter.startsPull(x = 320f, y = 4f))
        assertTrue(ControlCenter.startsPull(x = 590f, y = 40f))
        assertFalse("below the edge", ControlCenter.startsPull(x = 320f, y = 70f))
        assertFalse("over the cameras", ControlCenter.startsPull(x = 200f, y = 4f))
        assertFalse("the right half", ControlCenter.startsPull(x = 800f, y = 4f))
    }

    @Test fun `it follows the finger and opens past halfway`() {
        assertEquals(0.5f, ControlCenter.progress(open = false, dy = ControlCenter.TRAVEL / 2f), 0.001f)
        assertTrue(ControlCenter.release(open = false, dy = ControlCenter.TRAVEL * 0.6f))
        assertFalse(ControlCenter.release(open = false, dy = ControlCenter.TRAVEL * 0.3f))
    }

    @Test fun `open, a drag up closes it past halfway`() {
        assertEquals(0.75f, ControlCenter.progress(open = true, dy = -ControlCenter.TRAVEL / 4f), 0.001f)
        assertFalse(ControlCenter.release(open = true, dy = -ControlCenter.TRAVEL * 0.6f))
        assertTrue(ControlCenter.release(open = true, dy = -ControlCenter.TRAVEL * 0.3f))
        assertEquals(1f, ControlCenter.progress(open = true, dy = 300f), 0f)
    }

    @Test fun `brightness is HyperOS's 1 to 255, shown from 1 per cent to full`() {
        assertEquals(1f, ControlCenter.level(255), 0f)
        assertEquals(ControlCenter.MIN_LEVEL, ControlCenter.level(1), 0f)
        assertEquals(255, ControlCenter.setting(1f))
        assertEquals(128, ControlCenter.setting(ControlCenter.level(128)))
    }

    @Test fun `a drag on the cell moves the level from where it was, up is brighter`() {
        assertEquals(0.75f, ControlCenter.drag(from = 0.5f, dy = -0.25f * ControlCenter.CELL_TRAVEL), 0.001f)
        assertEquals("never below one per cent", ControlCenter.MIN_LEVEL, ControlCenter.drag(from = 0.1f, dy = 2f * ControlCenter.CELL_TRAVEL), 0f)
    }

    @Test fun `a flick decides, whatever the distance`() {
        assertTrue(ControlCenter.release(open = false, dy = 20f, velocity = 1.2f))
        assertFalse(ControlCenter.release(open = true, dy = -20f, velocity = -1.2f))
        // Slow: halfway decides, as before.
        assertTrue(ControlCenter.release(open = false, dy = ControlCenter.TRAVEL * 0.6f, velocity = 0.05f))
    }

    @Test fun `the rest of the way goes at the finger's speed, slowing to a stop`() {
        // Ease-out starts at twice the average speed: the time is 2 * distance / speed.
        assertEquals(200L, ControlCenter.settleMs(distance = 100f, speed = 1f))
        assertEquals(ControlCenter.SETTLE_MAX_MS, ControlCenter.settleMs(distance = 200f, speed = 0.01f))
        assertEquals(ControlCenter.SETTLE_MIN_MS, ControlCenter.settleMs(distance = 5f, speed = 3f))
        assertEquals("no speed toward it", ControlCenter.SETTLE_MAX_MS, ControlCenter.settleMs(distance = 100f, speed = 0f))
    }

    @Test fun `the figure stays two seconds after the last change`() {
        assertTrue(ControlCenter.figureShown(nowMs = 10_000L, changedAtMs = 8_500L))
        assertFalse(ControlCenter.figureShown(nowMs = 10_000L, changedAtMs = 7_900L))
        assertFalse(ControlCenter.figureShown(nowMs = 10_000L, changedAtMs = 0L))
    }
}
