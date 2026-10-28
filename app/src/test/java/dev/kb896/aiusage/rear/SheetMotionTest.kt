package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.PortalSheet.FULL
import dev.kb896.aiusage.rear.PortalSheet.PEEK
import dev.kb896.aiusage.rear.SheetMotion.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetMotionTest {

    private val twoStops = listOf(0f, FULL)
    private val threeStops = listOf(0f, PEEK, FULL)

    private fun motion(stops: List<Float> = twoStops) = SheetMotion().apply { setDetents(stops, 0) }

    /** A slow pull from shut, landing at y 560 at t 0, to [y], let go at [upAt]. */
    private fun pulled(y: Float, stops: List<Float> = twoStops, upAt: Long = 1_000): SheetMotion = motion(stops).apply {
        grab(560f, 0)
        drag(560f + (y - 560f) / 2, 400)
        drag(y, 800)
        release(y, upAt)
    }

    /** At rest at [h] among [stops]. */
    private fun restingAt(h: Float, stops: List<Float> = threeStops) = motion(stops).apply { settle(h, 0); at(10_000) }

    // -- snapping ------------------------------------------------------------------------

    @Test fun `let go slowly, it rests at the nearest stop`() {
        assertEquals(0f, SheetMotion.snap(threeStops, 40f, 0f), 0f)
        assertEquals(PEEK, SheetMotion.snap(threeStops, 50f, 0f), 0f)
        assertEquals(PEEK, SheetMotion.snap(threeStops, 300f, 0f), 0f)
        assertEquals(FULL, SheetMotion.snap(threeStops, 340f, 0f), 0f)
        assertEquals(0f, SheetMotion.snap(twoStops, 270f, 0f), 0f)
        assertEquals(FULL, SheetMotion.snap(twoStops, 300f, 0f), 0f)
    }

    @Test fun `a flick goes at least to the next stop its way`() {
        // Just out of the band, flicked up: the strip, not shut.
        assertEquals(PEEK, SheetMotion.snap(threeStops, 30f, -0.8f), 0f)
        // Over our panel there is no strip: all the way.
        assertEquals(FULL, SheetMotion.snap(twoStops, 30f, -0.8f), 0f)
        // Just below full, flicked down: the strip.
        assertEquals(PEEK, SheetMotion.snap(threeStops, 540f, 0.8f), 0f)
        // Just above the strip, flicked down: shut.
        assertEquals(0f, SheetMotion.snap(threeStops, 100f, 0.8f), 0f)
    }

    @Test fun `a fast fling goes all the way, shut to full and full to shut`() {
        assertEquals(FULL, SheetMotion.snap(threeStops, 60f, -2f), 0f)
        assertEquals(0f, SheetMotion.snap(threeStops, 472f, 2f), 0f)
    }

    @Test fun `a slower fling stops at the strip on the way`() {
        assertEquals(PEEK, SheetMotion.snap(threeStops, 60f, -1f), 0f)
        assertEquals(PEEK, SheetMotion.snap(threeStops, 472f, 1.2f), 0f)
    }

    @Test fun `a flick the wrong way for where it is still goes its way`() {
        // Resting on full and flicked up: nowhere further up to go.
        assertEquals(FULL, SheetMotion.snap(threeStops, FULL, -3f), 0f)
        assertEquals(0f, SheetMotion.snap(threeStops, 0f, 3f), 0f)
    }

    // -- the pull ------------------------------------------------------------------------

    @Test fun `its top edge follows the finger one for one`() {
        val m = motion()
        m.grab(560f, 0)
        assertEquals(100f, m.drag(460f, 100), 1e-4f)
        assertEquals(Phase.HELD, m.phase)
        assertEquals(0f, m.drag(600f, 200), 0f)
    }

    @Test fun `it never goes past its highest stop`() {
        val m = motion(threeStops)
        m.grab(560f, 0)
        assertEquals(FULL, m.drag(-200f, 100), 0f)
    }

    @Test fun `a slow pull past halfway opens it, short of that it shuts`() {
        assertEquals(FULL, pulled(560f - 300f).target, 0f)
        assertEquals(0f, pulled(560f - 200f).target, 0f)
    }

    @Test fun `over a guest on top a short pull rests on the strip`() {
        val m = pulled(560f - 100f, threeStops)
        assertEquals(PEEK, m.target, 0f)
        m.at(10_000)
        assertEquals(Phase.OPEN, m.phase)
        assertEquals(PEEK, m.settled(10_000))
    }

    @Test fun `from the strip a pull up goes on to full, a drag down shuts it`() {
        val up = restingAt(PEEK)
        up.grab(500f, 20_000); up.drag(400f, 20_400); up.drag(200f, 20_800)
        assertEquals(FULL, up.release(200f, 21_000), 0f)
        val down = restingAt(PEEK)
        down.grab(500f, 20_000); down.drag(520f, 20_400); down.drag(550f, 20_800)
        assertEquals(0f, down.release(550f, 21_000), 0f)
    }

    @Test fun `from full a drag down comes back to the strip`() {
        val m = restingAt(FULL)
        m.grab(200f, 20_000); m.drag(300f, 20_400); m.drag(500f, 20_800)
        assertEquals(PEEK, m.release(500f, 21_000), 0f)
    }

    @Test fun `a flick up from shut opens even a short pull`() {
        val m = motion()
        m.grab(560f, 0)
        m.drag(540f, 10)
        m.drag(500f, 20)
        assertEquals(FULL, m.release(500f, 25), 0f)
    }

    @Test fun `a finger that stopped before lifting is not flicking`() {
        val m = motion()
        m.grab(560f, 0)
        m.drag(540f, 10)
        m.drag(500f, 20)
        assertEquals(0f, m.release(500f, 20 + SheetMotion.STILL_MS + 1), 0f)
    }

    @Test fun `a settle eases out and comes to rest`() {
        val m = pulled(560f - 300f)
        val ms = m.settleMs
        assertTrue(ms in ControlCenter.SETTLE_MIN_MS..ControlCenter.SETTLE_MAX_MS)
        // Eased out: three quarters of the way at half time.
        assertEquals(300f + (FULL - 300f) * 0.75f, m.at(1_000 + ms / 2), 0.5f)
        assertEquals(Phase.SETTLING, m.phase)
        assertEquals(FULL, m.at(1_000 + ms), 1e-3f)
        assertEquals(Phase.OPEN, m.phase)
    }

    @Test fun `shut after a settle down, at rest`() {
        val m = pulled(560f - 100f)
        m.at(5_000)
        assertEquals(Phase.SHUT, m.phase)
        assertEquals(0f, m.height(5_000), 0f)
    }

    @Test fun `caught mid-settle it is held where it was, not snapped to a stop`() {
        val m = pulled(560f - 300f)
        val ms = m.settleMs
        val mid = m.height(1_000 + ms / 2)
        m.grab(300f, 1_000 + ms / 2)
        assertEquals(mid, m.height(1_000 + ms / 2), 1e-3f)
        assertNull(m.settled(1_000 + ms / 2))
        // And dragged from there.
        assertEquals(mid - 20f, m.drag(320f, 1_000 + ms / 2 + 50), 1e-3f)
    }

    @Test fun `held and let go without a drag, it carries on where it was going`() {
        val m = pulled(560f - 100f) // on its way shut
        m.hold(1_000 + 10)
        assertEquals(Phase.HELD, m.phase)
        assertFalse(m.heading)
        assertEquals(0f, m.release(0f, 1_100), 0f)
        assertEquals(Phase.SETTLING, m.phase)
        m.at(10_000)
        assertEquals(Phase.SHUT, m.phase)
    }

    @Test fun `held at rest and resumed, it stays where it was at once`() {
        val m = restingAt(PEEK)
        m.hold(20_000)
        assertEquals(PEEK, m.settled(20_000))
        m.resume(20_100)
        assertEquals(Phase.OPEN, m.phase)
        assertEquals(PEEK, m.target, 0f)
    }

    @Test fun `only a sheet standing still on a stop counts as settled for taps`() {
        val m = pulled(560f - 300f)
        assertNull(m.settled(1_000))
        m.at(10_000)
        assertEquals(FULL, m.settled(10_000))
        assertNull(motion().settled(0))
    }

    @Test fun `a sheet caught on its way shut can be pushed back open`() {
        val m = restingAt(FULL, twoStops)
        m.settle(0f, 20_000)
        m.grab(400f, 20_000 + m.settleMs / 4)
        m.drag(300f, 20_500)
        m.drag(100f, 20_700)
        assertEquals(FULL, m.release(100f, 20_710), 0f)
    }

    @Test fun `reset shuts it whatever it was doing`() {
        val m = pulled(560f - 300f)
        m.reset()
        assertEquals(Phase.SHUT, m.phase)
        assertEquals(0f, m.height(1_200), 0f)
    }

    @Test fun `a drag at the grab's own time is no flick`() {
        // The pull is recognised a little way from where it landed, at that event's time:
        // grabbed and dragged at the same instant, the first step has moved but taken no time.
        val m = motion()
        m.grab(560f, 100)
        m.drag(540f, 100)
        m.release(540f, 150)
        assertFalse(m.heading)
    }

    @Test fun `grabbed at the landing, the first step's speed is the real one`() {
        val m = motion()
        m.grab(560f, 0)
        m.drag(540f, 200)
        m.release(540f, 210)
        assertFalse(m.heading)
    }

    // -- the stops changing --------------------------------------------------------------

    @Test fun `resting on the strip when the strip goes, it shuts`() {
        val m = restingAt(PEEK)
        m.setDetents(twoStops, 20_000)
        assertEquals(0f, m.target, 0f)
        m.at(30_000)
        assertEquals(Phase.SHUT, m.phase)
    }

    @Test fun `resting on full when the strip comes or goes, it stays`() {
        val m = restingAt(FULL, twoStops)
        m.setDetents(threeStops, 20_000)
        assertEquals(Phase.OPEN, m.phase)
        m.setDetents(twoStops, 20_000)
        assertEquals(FULL, m.settled(20_000))
    }
}
