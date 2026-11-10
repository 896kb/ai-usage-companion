package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.GuestReveal.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestRevealTest {

    private val w = 904f

    @Test fun `the cover starts whole, where the screen is`() {
        val f = GuestReveal.slide(0L, w)
        assertEquals(0f, f.dx, 0f)
        assertEquals(1f, f.alpha, 0f)
    }

    @Test fun `it fades where it stands, and never moves across`() {
        val frames = (0..GuestReveal.SLIDE_MS.toInt() step 8).map { GuestReveal.slide(it.toLong(), w) }
        assertTrue(frames.all { it.dx == 0f })
        assertTrue("never back up", frames.zipWithNext().all { (a, b) -> b.alpha <= a.alpha })
        assertEquals(0.5f, GuestReveal.slide(GuestReveal.SLIDE_MS / 2, w).alpha, 0.01f)
    }

    @Test fun `it ends gone, and stays gone`() {
        assertEquals(0f, GuestReveal.slide(GuestReveal.SLIDE_MS, w).alpha, 0f)
        assertEquals(0f, GuestReveal.slide(GuestReveal.SLIDE_MS + 500, w).alpha, 0f)
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
        assertEquals(GuestReveal.slide(100, w).alpha, r.at(1_400, w).alpha, 0f)
        r.at(1_300 + GuestReveal.SLIDE_MS, w)
        assertEquals(Phase.DONE, r.phase)
    }

    @Test fun `a second drawn does not start it over`() {
        val r = GuestReveal(coveredAtMs = 0)
        r.drawn(100)
        r.drawn(300)
        assertEquals(GuestReveal.slide(300, w).alpha, r.at(400, w).alpha, 0f)
    }

    @Test fun `never drawn, it slides anyway once MAX_WAIT_MS is up, from then`() {
        val r = GuestReveal(coveredAtMs = 1_000)
        val due = 1_000 + GuestReveal.MAX_WAIT_MS
        assertTrue(r.waiting(due - 1))
        assertFalse(r.waiting(due))
        assertEquals(GuestReveal.slide(50, w).alpha, r.at(due + 50, w).alpha, 0f)
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
