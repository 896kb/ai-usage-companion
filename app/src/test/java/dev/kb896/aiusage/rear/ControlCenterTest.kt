package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The control centre: where it is pulled from, how it opens, and the brightness cell's numbers. */
class ControlCenterTest {

    @Test fun `it is pulled from the very top edge, over the left half of the usable area`() {
        assertTrue(ControlCenter.startsPull(x = 320f, y = 4f, left = 296f))
        assertTrue(ControlCenter.startsPull(x = 590f, y = 40f, left = 296f))
        assertFalse("below the edge", ControlCenter.startsPull(x = 320f, y = 70f, left = 296f))
        assertFalse("over the cameras", ControlCenter.startsPull(x = 200f, y = 4f, left = 296f))
        assertFalse("the right half", ControlCenter.startsPull(x = 800f, y = 4f, left = 296f))
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
        // Every setting comes back as itself through the slider.
        for (v in 1..255) assertEquals(v, ControlCenter.setting(ControlCenter.level(v)))
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
        assertEquals(ControlCenter.SETTLE_MIN_MS, ControlCenter.settleMs(distance = 100f, speed = 2f))
        assertEquals(ControlCenter.SETTLE_MAX_MS, ControlCenter.settleMs(distance = 200f, speed = 0.01f))
        assertEquals(ControlCenter.SETTLE_MIN_MS, ControlCenter.settleMs(distance = 5f, speed = 3f))
        // A flick faster than SPEED_MAX goes no faster: 200 at 0.9 a ms (444 ms), not at 20.
        assertEquals(444L, ControlCenter.settleMs(distance = 200f, speed = 20f))
        assertEquals("no speed toward it", ControlCenter.SETTLE_MAX_MS, ControlCenter.settleMs(distance = 100f, speed = 0f))
    }

    @Test fun `the figure stays two seconds after the last change`() {
        assertTrue(ControlCenter.figureShown(nowMs = 10_000L, changedAtMs = 8_500L))
        assertFalse(ControlCenter.figureShown(nowMs = 10_000L, changedAtMs = 7_900L))
        assertFalse(ControlCenter.figureShown(nowMs = 10_000L, changedAtMs = 0L))
    }

    @Test fun `on a Pro Max it is pulled from where its lenses end, over the left half from there`() {
        val left = RearGeometry(976, 596, 296, 110, 450).cutoutUnits
        assertTrue(ControlCenter.startsPull(x = 280f, y = 4f, left = left))
        assertFalse(ControlCenter.startsPull(x = 270f, y = 4f, left = left))
        // The half's edge moves with it: (274 + 904) / 2 = 589.
        assertTrue(ControlCenter.startsPull(x = 585f, y = 4f, left = left))
        assertFalse(ControlCenter.startsPull(x = 595f, y = 4f, left = left))
    }
}

class ChaseTest {
    @Test fun `it is where the target is while the target moves slowly, and trails a fast one`() {
        val c = ControlCenter.Chase(maxSpeed = 1f)
        org.junit.Assert.assertEquals(0f, c.to(0f, 1000), 0f)
        // 10 in 16 ms: slower than 1 a ms, so right there.
        org.junit.Assert.assertEquals(10f, c.to(10f, 1016), 0f)
        // 300 in the next 16 ms: it goes 16 of it.
        org.junit.Assert.assertEquals(26f, c.to(310f, 1032), 0f)
        // And catches up frame by frame, never past it.
        var at = 1032L
        var v = 26f
        while (v < 310f) { at += 16; v = c.to(310f, at) }
        org.junit.Assert.assertEquals(310f, v, 0f)
    }

    @Test fun `a missed frame is not a jump`() {
        val c = ControlCenter.Chase(maxSpeed = 1f)
        c.snap(0f, 0)
        org.junit.Assert.assertEquals(34f, c.to(500f, 1_000), 0f)
    }
}

class BrightnessCurveTest {
    @Test fun `the slider is perceptual, its middle far less light than half, as Android's own`() {
        // Half the slider: about an eighth of the light (HLG), not half.
        val mid = ControlCenter.setting(0.5f)
        org.junit.Assert.assertTrue("half the slider gave $mid", mid in 20..30)
        // And it climbs ever faster toward the top: the last fifth does far more than the first.
        val first = ControlCenter.setting(0.2f) - ControlCenter.setting(0f)
        val last = ControlCenter.setting(1f) - ControlCenter.setting(0.8f)
        org.junit.Assert.assertTrue(last > first * 5)
    }

    @Test fun `the curve and its inverse agree`() {
        for (i in 0..100) {
            val g = i / 100f
            org.junit.Assert.assertEquals(g, ControlCenter.linearToGamma(ControlCenter.gammaToLinear(g)), 1e-4f)
        }
    }
}
