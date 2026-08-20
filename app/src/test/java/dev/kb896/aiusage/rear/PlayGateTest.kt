package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport state machine, driven through the sequences that actually happen.
 *
 * This is the whole reason PlayGate is a class rather than three fields in the view: the
 * behaviour is about a sequence over time, which is exactly what cannot be checked by
 * inspecting any single report.
 */
class PlayGateTest {

    private val settle = 500L

    /** The bug. Press next, and the player reports a gap it did not mean as a pause. */
    @Test fun `a skip does not move the glyph`() {
        val gate = PlayGate(settle)
        gate.snap(true)

        // Press next. The player drops out while it loads the next track...
        gate.report(false, 1_000L)
        assertFalse("committed mid-skip", gate.commit(1_200L))
        assertTrue("glyph left the play state during a skip", gate.shown)

        // ...and is back before the gate ever believed it.
        gate.report(true, 1_300L)
        assertFalse("committed after the state came back", gate.commit(1_900L))
        assertTrue(gate.shown)
    }

    @Test fun `a real pause is committed once it holds`() {
        val gate = PlayGate(settle)
        gate.snap(true)
        gate.report(false, 1_000L)

        assertFalse("committed early", gate.commit(1_000L + settle - 1))
        assertTrue(gate.shown)
        assertTrue("never committed", gate.commit(1_000L + settle))
        assertFalse(gate.shown)
    }

    @Test fun `a commit happens once, not on every frame after`() {
        val gate = PlayGate(settle)
        gate.snap(true)
        gate.report(false, 0L)
        assertTrue(gate.commit(settle))
        assertFalse("re-committed an unchanged state", gate.commit(settle + 1))
        assertFalse("re-committed later still", gate.commit(10_000L))
    }

    /** The settle clock restarts on a CHANGE, not on every report of the same value. */
    @Test fun `repeating the same report does not postpone the verdict`() {
        val gate = PlayGate(settle)
        gate.snap(true)
        gate.report(false, 0L)
        for (t in 1L..9L) gate.report(false, t * 50L)
        assertTrue("repeats pushed the verdict back", gate.commit(settle))
        assertFalse(gate.shown)
    }

    @Test fun `waiting says whether a verdict is pending`() {
        val gate = PlayGate(settle)
        gate.snap(true)
        assertFalse(gate.waiting())
        gate.report(false, 0L)
        assertTrue("nothing pending after a change", gate.waiting())
        gate.commit(settle)
        assertFalse("still pending after committing", gate.waiting())
    }

    @Test fun `snap adopts a state with no wait`() {
        val gate = PlayGate(settle)
        gate.snap(true)
        assertTrue(gate.shown)
        assertFalse("snap left something pending", gate.waiting())
        gate.snap(false)
        assertFalse(gate.shown)
        assertFalse(gate.waiting())
    }
}
