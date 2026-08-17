package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TweenTest {

    private val D = Tween.DURATION_MS.toLong()

    /** The first reading is adopted, not animated to. Respawns are frequent. */
    @Test fun `opens on the real value`() {
        val t = Tween()
        assertEquals(0.42f, t.advance(0.42f, 1_000L), 1e-6f)
        assertTrue(t.settled(1_000L))
    }

    @Test fun `eases to a new target and arrives exactly`() {
        val t = Tween()
        t.advance(0f, 0L)
        t.advance(1f, 0L)
        val mid = t.advance(1f, D / 2)
        assertEquals(0.5f, mid, 0.01f)
        assertEquals(1f, t.advance(1f, D), 1e-6f)
        assertTrue(t.settled(D))
    }

    /** The reason this replaced a spring: a bounded value must never exceed its target. */
    @Test fun `never overshoots`() {
        val t = Tween()
        t.advance(0f, 0L)
        t.advance(0.94f, 0L)
        var peak = 0f
        for (ms in 0..D step 10) peak = maxOf(peak, t.advance(0.94f, ms))
        assertTrue("peaked at $peak", peak <= 0.94f + 1e-6f)
    }

    /**
     * THE REGRESSION.
     *
     * The ring's target is recomputed from the wall clock every frame, so it drifts by a
     * hair continuously. A tween that treats any change as a new leg restarts its clock
     * every frame and never moves at all — which is exactly what shipped, and what left
     * one ring frozen at 0.00 while its raw input read 0.50.
     */
    @Test fun `a target drifting every frame still completes`() {
        val t = Tween()
        t.advance(0f, 0L)
        var target = 0.5f
        var last = 0f
        for (ms in 0..D step 16) {
            target += 6e-8f                 // clock jitter, per frame
            last = t.advance(target, ms)
        }
        assertEquals("frozen at $last", 0.5f, last, 0.01f)
        assertTrue(t.settled(D))
    }

    /** ...and the drift must still be tracked, not ignored. */
    @Test fun `sub-threshold drift moves the destination`() {
        val t = Tween()
        t.advance(0f, 0L)
        t.advance(1f, 0L)
        val arrived = t.advance(1.001f, D)
        assertEquals(1.001f, arrived, 1e-4f)
    }

    /** A change mid-flight continues from where it is rather than snapping back. */
    @Test fun `retargets from the current value`() {
        val t = Tween()
        t.advance(0f, 0L)
        t.advance(1f, 0L)
        val mid = t.advance(1f, D / 2)
        val next = t.advance(0f, D / 2)
        assertEquals("should continue from $mid", mid, next, 1e-6f)
    }
}
