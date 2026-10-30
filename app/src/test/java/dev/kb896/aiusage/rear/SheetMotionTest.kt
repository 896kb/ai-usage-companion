package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.PortalSheet.OPEN
import dev.kb896.aiusage.rear.PortalSheet.REVEALED
import dev.kb896.aiusage.rear.PortalSheet.STRIP
import dev.kb896.aiusage.rear.SheetMotion.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetMotionTest {

    private val noGuest = listOf(0f, OPEN)
    private val guest = listOf(0f, OPEN, REVEALED)

    private fun motion(stops: List<Float> = noGuest) = SheetMotion().apply { setDetents(stops, 0) }

    /** A slow pull from shut, landing at y 560 at t 0, to [y], let go at [upAt]. */
    private fun pulled(y: Float, stops: List<Float> = noGuest, upAt: Long = 1_000): SheetMotion = motion(stops).apply {
        grab(560f, 0)
        drag(560f + (y - 560f) / 2, 400)
        drag(y, 800)
        release(y, upAt)
    }

    /** At rest at [at] among [stops]. */
    private fun restingAt(at: Float, stops: List<Float> = guest) = motion(stops).apply { settle(at, 0); at(10_000) }

    // -- snapping ------------------------------------------------------------------------

    @Test fun `let go slowly, it rests at the nearest stop`() {
        assertEquals(0f, SheetMotion.snap(noGuest, 120f, 0f), 0f)
        assertEquals(OPEN, SheetMotion.snap(noGuest, 140f, 0f), 0f)
        assertEquals(OPEN, SheetMotion.snap(guest, OPEN + STRIP / 2 - 10f, 0f), 0f)
        assertEquals(REVEALED, SheetMotion.snap(guest, OPEN + STRIP / 2 + 10f, 0f), 0f)
    }

    @Test fun `a flick goes to the next stop its way, one stop and never two`() {
        assertEquals(OPEN, SheetMotion.snap(guest, 30f, -3f), 0f)
        assertEquals(OPEN, SheetMotion.snap(guest, REVEALED - 10f, 3f), 0f)
        assertEquals(REVEALED, SheetMotion.snap(guest, OPEN + 5f, -0.8f), 0f)
        assertEquals(0f, SheetMotion.snap(guest, OPEN - 5f, 0.8f), 0f)
    }

    @Test fun `with two stops it is the old rule, a flick wins, else past halfway`() {
        assertEquals(OPEN, SheetMotion.snap(noGuest, 10f, -0.7f), 0f)
        assertEquals(0f, SheetMotion.snap(noGuest, OPEN - 10f, 0.7f), 0f)
        assertEquals(OPEN, SheetMotion.snap(noGuest, OPEN * 0.6f, 0.3f), 0f)
        assertEquals(0f, SheetMotion.snap(noGuest, OPEN * 0.4f, -0.3f), 0f)
    }

    @Test fun `a flick past the last stop stays there`() {
        assertEquals(REVEALED, SheetMotion.snap(guest, REVEALED, -3f), 0f)
        assertEquals(0f, SheetMotion.snap(guest, 0f, 3f), 0f)
    }

    // -- the pull ------------------------------------------------------------------------

    @Test fun `the pull follows the finger one for one`() {
        val m = motion()
        m.grab(560f, 0)
        assertEquals(100f, m.drag(460f, 100), 1e-4f)
        assertEquals(Phase.HELD, m.phase)
        assertEquals(0f, m.drag(600f, 200), 0f)
    }

    @Test fun `the opening pull stops open even with a guest, the slider takes a swipe of its own`() {
        val m = motion(guest)
        m.grab(560f, 0)
        assertEquals(OPEN, m.drag(0f, 100), 0f)
        assertEquals(OPEN, m.release(0f, 400), 0f)
    }

    @Test fun `a slow pull past halfway opens it, short of that it shuts`() {
        assertEquals(OPEN, pulled(560f - OPEN * 0.6f).target, 0f)
        assertEquals(0f, pulled(560f - OPEN * 0.4f).target, 0f)
    }

    @Test fun `open with a guest, a swipe up scrolls on to the slider`() {
        val m = restingAt(OPEN)
        m.grab(400f, 20_000); m.drag(360f, 20_400); m.drag(300f, 20_800)
        assertEquals(REVEALED, m.release(300f, 21_000), 0f)
    }

    @Test fun `open without a guest, a swipe up does nothing`() {
        val m = restingAt(OPEN, noGuest)
        m.grab(400f, 20_000)
        assertEquals(OPEN, m.drag(200f, 20_400), 0f)
        assertEquals(OPEN, m.release(200f, 20_410), 0f)
    }

    @Test fun `at the slider a swipe down scrolls back and stops open, however far it goes`() {
        val m = restingAt(REVEALED)
        m.grab(200f, 20_000)
        assertEquals(OPEN, m.drag(500f, 20_400), 0f)
        assertEquals(OPEN, m.release(500f, 20_800), 0f)
    }

    @Test fun `a fling down from the slider goes back open, not shut`() {
        val m = restingAt(REVEALED)
        m.grab(200f, 20_000)
        m.drag(230f, 20_010)
        m.drag(290f, 20_020)
        assertEquals(OPEN, m.release(290f, 20_025), 0f)
    }

    @Test fun `open, a swipe down shuts it`() {
        val m = restingAt(OPEN)
        m.grab(200f, 20_000); m.drag(300f, 20_400); m.drag(400f, 20_800)
        assertEquals(0f, m.release(400f, 21_000), 0f)
    }

    @Test fun `a flick up from shut opens even a short pull`() {
        val m = motion()
        m.grab(560f, 0)
        m.drag(540f, 10)
        m.drag(500f, 20)
        assertEquals(OPEN, m.release(500f, 25), 0f)
    }

    @Test fun `a finger that stopped before lifting is not flicking`() {
        val m = motion()
        m.grab(560f, 0)
        m.drag(540f, 10)
        m.drag(500f, 20)
        assertEquals(0f, m.release(500f, 20 + SheetMotion.STILL_MS + 1), 0f)
    }

    @Test fun `a settle eases out and comes to rest`() {
        val m = pulled(560f - OPEN * 0.6f)
        val ms = m.settleMs
        assertTrue(ms in ControlCenter.SETTLE_MIN_MS..ControlCenter.SETTLE_MAX_MS)
        // Eased out: three quarters of the way at half time.
        assertEquals(OPEN * 0.6f + OPEN * 0.4f * 0.75f, m.at(1_000 + ms / 2), 0.5f)
        assertEquals(Phase.SETTLING, m.phase)
        assertEquals(OPEN, m.at(1_000 + ms), 1e-3f)
        assertEquals(Phase.OPEN, m.phase)
    }

    @Test fun `shut after a settle down, at rest`() {
        val m = pulled(560f - OPEN * 0.2f)
        m.at(5_000)
        assertEquals(Phase.SHUT, m.phase)
        assertEquals(0f, m.position(5_000), 0f)
    }

    @Test fun `caught mid-settle it is held where it was, not snapped to a stop`() {
        val m = pulled(560f - OPEN * 0.6f)
        val ms = m.settleMs
        val mid = m.position(1_000 + ms / 2)
        m.grab(300f, 1_000 + ms / 2)
        assertEquals(mid, m.position(1_000 + ms / 2), 1e-3f)
        assertNull(m.settled(1_000 + ms / 2))
        // And dragged from there.
        assertEquals(mid - 20f, m.drag(320f, 1_000 + ms / 2 + 50), 1e-3f)
    }

    @Test fun `held and let go without a drag, it carries on where it was going`() {
        val m = pulled(560f - OPEN * 0.2f) // on its way shut
        m.hold(1_000 + 10)
        assertEquals(Phase.HELD, m.phase)
        assertFalse(m.heading)
        assertEquals(0f, m.release(0f, 1_100), 0f)
        assertEquals(Phase.SETTLING, m.phase)
        m.at(10_000)
        assertEquals(Phase.SHUT, m.phase)
    }

    @Test fun `held at rest and resumed, it stays where it was at once`() {
        val m = restingAt(REVEALED)
        m.hold(20_000)
        assertEquals(REVEALED, m.settled(20_000))
        m.resume(20_100)
        assertEquals(Phase.OPEN, m.phase)
        assertEquals(REVEALED, m.target, 0f)
    }

    @Test fun `only a sheet standing still on a stop counts as settled for taps`() {
        val m = pulled(560f - OPEN * 0.6f)
        assertNull(m.settled(1_000))
        m.at(10_000)
        assertEquals(OPEN, m.settled(10_000))
        assertNull(motion().settled(0))
    }

    @Test fun `a sheet caught on its way shut can be pushed back open`() {
        val m = restingAt(OPEN, noGuest)
        m.settle(0f, 20_000)
        m.grab(400f, 20_000 + m.settleMs / 4)
        m.drag(300f, 20_500)
        m.drag(100f, 20_700)
        assertEquals(OPEN, m.release(100f, 20_710), 0f)
    }

    @Test fun `reset shuts it whatever it was doing`() {
        val m = pulled(560f - OPEN * 0.6f)
        m.reset()
        assertEquals(Phase.SHUT, m.phase)
        assertEquals(0f, m.position(1_200), 0f)
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

    @Test fun `scrolled to the slider when the guest goes, it scrolls back open`() {
        val m = restingAt(REVEALED)
        m.setDetents(noGuest, 20_000)
        assertEquals(OPEN, m.target, 0f)
        m.at(30_000)
        assertEquals(OPEN, m.settled(30_000))
    }

    @Test fun `open when a guest comes or goes, it stays open`() {
        val m = restingAt(OPEN, noGuest)
        m.setDetents(guest, 20_000)
        assertEquals(Phase.OPEN, m.phase)
        m.setDetents(noGuest, 20_000)
        assertEquals(OPEN, m.settled(20_000))
    }
}
