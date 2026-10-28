package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.SheetMotion.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetMotionTest {

    private val travel = PortalPull.TRAVEL

    /** A pull from shut, landing at y 560 at t 0, moved slowly to [y], let go at [upAt]. */
    private fun pulled(y: Float, upAt: Long = 1_000): SheetMotion = SheetMotion().apply {
        grab(560f, 0)
        drag(560f + (y - 560f) / 2, 400)
        drag(y, 800)
        release(y, upAt)
    }

    @Test fun `a pull follows the finger as PortalPull does`() {
        val m = SheetMotion()
        m.grab(560f, 0)
        assertEquals(PortalPull.progress(false, -100f), m.drag(460f, 100), 1e-5f)
        assertEquals(Phase.HELD, m.phase)
    }

    @Test fun `past halfway it opens, short of it it shuts`() {
        val long = pulled(560f - travel * 0.6f)
        assertEquals(Phase.SETTLING, long.phase)
        assertTrue(long.heading)
        val short = pulled(560f - travel * 0.3f)
        assertFalse(short.heading)
    }

    @Test fun `a flick up opens even a short pull`() {
        val m = SheetMotion()
        m.grab(560f, 0)
        m.drag(540f, 10)
        m.drag(500f, 20)
        assertTrue(m.release(500f, 25))
    }

    @Test fun `a finger that stopped before lifting is not flicking`() {
        val m = SheetMotion()
        m.grab(560f, 0)
        m.drag(540f, 10)
        m.drag(500f, 20)
        assertFalse(m.release(500f, 20 + SheetMotion.STILL_MS + 1))
    }

    @Test fun `a settle eases out and comes to rest open`() {
        val m = pulled(560f - travel * 0.6f)
        val start = 1_000L
        val ms = m.settleMs
        assertTrue(ms in ControlCenter.SETTLE_MIN_MS..ControlCenter.SETTLE_MAX_MS)
        val half = m.at(start + ms / 2)
        // Eased out: three quarters of the way at half time.
        assertEquals(0.6f + 0.4f * 0.75f, half, 0.01f)
        assertEquals(Phase.SETTLING, m.phase)
        assertEquals(1f, m.at(start + ms), 1e-5f)
        assertEquals(Phase.OPEN, m.phase)
    }

    @Test fun `shut after a settle down, at rest`() {
        val m = pulled(560f - travel * 0.2f)
        m.at(5_000)
        assertEquals(Phase.SHUT, m.phase)
        assertEquals(0f, m.progress(5_000), 0f)
    }

    @Test fun `open, a drag down past halfway shuts it`() {
        val m = SheetMotion()
        m.settle(true, 0); m.at(10_000)
        assertEquals(Phase.OPEN, m.phase)
        m.grab(200f, 20_000)
        assertEquals(PortalPull.progress(true, travel * 0.7f), m.drag(200f + travel * 0.7f, 20_400), 1e-5f)
        assertFalse(m.release(200f + travel * 0.7f, 20_800))
    }

    @Test fun `open, a short drag down springs back open`() {
        val m = SheetMotion()
        m.settle(true, 0); m.at(10_000)
        m.grab(200f, 20_000)
        m.drag(200f + travel * 0.2f, 20_400)
        assertTrue(m.release(200f + travel * 0.2f, 20_800))
    }

    @Test fun `caught mid-settle it is held where it was, not snapped to an end`() {
        val m = pulled(560f - travel * 0.6f)
        val ms = m.settleMs
        val mid = m.progress(1_000 + ms / 2)
        m.grab(300f, 1_000 + ms / 2)
        assertEquals(mid, m.progress(1_000 + ms / 2), 1e-5f)
        // And dragged from there.
        assertEquals((mid - 20f / travel).coerceIn(0f, 1f), m.drag(320f, 1_000 + ms / 2 + 50), 1e-5f)
    }

    @Test fun `held and let go without a drag, it carries on where it was going`() {
        val m = pulled(560f - travel * 0.2f) // on its way shut
        m.hold(1_000 + 10)
        assertEquals(Phase.HELD, m.phase)
        assertFalse(m.heading)
        assertFalse(m.release(0f, 1_100))
        assertEquals(Phase.SETTLING, m.phase)
        m.at(10_000)
        assertEquals(Phase.SHUT, m.phase)
    }

    @Test fun `held at rest open and resumed, it stays open at once`() {
        val m = SheetMotion()
        m.settle(true, 0); m.at(10_000)
        m.hold(20_000)
        m.resume(20_100)
        assertEquals(Phase.OPEN, m.phase)
    }

    @Test fun `only fully open counts as open for taps`() {
        val m = pulled(560f - travel * 0.6f)
        assertFalse(m.fullyOpen(1_000))
        m.at(10_000)
        assertTrue(m.fullyOpen(10_000))
    }

    @Test fun `a sheet caught on its way shut can be pushed back open`() {
        val m = SheetMotion()
        m.settle(true, 0); m.at(10_000)
        m.settle(false, 20_000)
        val p = m.progress(20_000 + m.settleMs / 4)
        m.grab(400f, 20_000 + m.settleMs / 4)
        m.drag(400f - travel * 0.5f, 20_500)
        assertTrue(m.release(400f - travel * 0.5f, 20_900))
        assertTrue(p in 0f..1f)
    }

    @Test fun `reset shuts it whatever it was doing`() {
        val m = pulled(560f - travel * 0.6f)
        m.reset()
        assertEquals(Phase.SHUT, m.phase)
        assertEquals(0f, m.progress(1_200), 0f)
    }

    @Test fun `a drag at the grab's own time is no flick`() {
        // The pull is recognised a little way from where it landed, at that event's time:
        // grabbed and dragged at the same instant, the first step has moved but taken no time.
        val m = SheetMotion()
        m.grab(560f, 100)
        m.drag(540f, 100)
        m.release(540f, 150)
        assertFalse(m.heading)
    }

    @Test fun `grabbed at the landing, the first step's speed is the real one`() {
        val m = SheetMotion()
        m.grab(560f, 0)
        m.drag(540f, 200)
        m.release(540f, 210)
        assertFalse(m.heading)
    }
}
