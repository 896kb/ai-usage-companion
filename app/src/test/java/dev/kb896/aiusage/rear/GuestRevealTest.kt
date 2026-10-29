package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.GuestReveal.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestRevealTest {

    private val w = 904f
    private val off = GuestReveal.TRAVEL * w

    @Test fun `the slide starts where the screen is, whole`() {
        val f = GuestReveal.slide(0L, w)
        assertEquals(0f, f.dx, 0f)
        assertEquals(1f, f.alpha, 0f)
    }

    @Test fun `it ends past the left edge, gone`() {
        val f = GuestReveal.slide(GuestReveal.SLIDE_MS, w)
        assertEquals(-off, f.dx, 1e-3f)
        assertEquals(0f, f.alpha, 0f)
        // And stays there.
        assertEquals(-off, GuestReveal.slide(GuestReveal.SLIDE_MS + 500, w).dx, 1e-3f)
    }

    @Test fun `it goes left on the system close's accelerate-quint curve`() {
        // Half the time, (1/2)^5 of the way.
        assertEquals(-off / 32f, GuestReveal.slide(GuestReveal.SLIDE_MS / 2, w).dx, 0.5f)
        val xs = (0..GuestReveal.SLIDE_MS.toInt() step 8).map { GuestReveal.slide(it.toLong(), w).dx }
        assertTrue("never back to the right", xs.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(xs.all { it <= 0f })
    }

    @Test fun `whole until FADE_FROM, then fading on an accelerate-quad to nothing at the end`() {
        assertEquals(1f, GuestReveal.slide(GuestReveal.FADE_FROM_MS, w).alpha, 0f)
        val half = GuestReveal.FADE_FROM_MS + (GuestReveal.SLIDE_MS - GuestReveal.FADE_FROM_MS) / 2f
        assertEquals(0.75f, GuestReveal.slide(Math.round(half).toLong(), w).alpha, 0.01f)
    }

    @Test fun `covered and still, until the guest has drawn`() {
        val r = GuestReveal(coveredAtMs = 1_000)
        assertEquals(Phase.COVERED, r.phase)
        assertTrue(r.waiting(1_200))
        val f = r.at(1_200, w)
        assertEquals(0f, f.dx, 0f)
        assertEquals(1f, f.alpha, 0f)
        assertEquals(Phase.COVERED, r.phase)
    }

    @Test fun `drawn, it slides from that moment and is done SLIDE_MS later`() {
        val r = GuestReveal(coveredAtMs = 1_000)
        r.drawn(1_300)
        assertEquals(Phase.SLIDING, r.phase)
        assertFalse(r.waiting(1_300))
        assertEquals(GuestReveal.slide(100, w).dx, r.at(1_400, w).dx, 0f)
        r.at(1_300 + GuestReveal.SLIDE_MS, w)
        assertEquals(Phase.DONE, r.phase)
    }

    @Test fun `a second drawn does not start it over`() {
        val r = GuestReveal(coveredAtMs = 0)
        r.drawn(100)
        r.drawn(300)
        assertEquals(GuestReveal.slide(300, w).dx, r.at(400, w).dx, 0f)
    }

    @Test fun `never drawn, it slides anyway once MAX_WAIT_MS is up, from then`() {
        val r = GuestReveal(coveredAtMs = 1_000)
        val due = 1_000 + GuestReveal.MAX_WAIT_MS
        assertTrue(r.waiting(due - 1))
        assertFalse(r.waiting(due))
        assertEquals(GuestReveal.slide(50, w).dx, r.at(due + 50, w).dx, 0f)
        assertEquals(Phase.SLIDING, r.phase)
        r.at(due + GuestReveal.SLIDE_MS, w)
        assertEquals(Phase.DONE, r.phase)
        // A late drawn changes nothing.
        r.drawn(due + GuestReveal.SLIDE_MS + 10)
        assertEquals(Phase.DONE, r.phase)
    }

    @Test fun `the wait and the slide fit inside the guest's survive wait`() {
        // The launch's two seconds after the move: the frame comes after the cover, before the survive check.
        assertTrue(GuestReveal.MAX_WAIT_MS + GuestReveal.SLIDE_MS < 2_000)
    }
}
