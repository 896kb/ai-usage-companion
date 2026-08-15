package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MascotMoodTest {

    @Test fun `an untouched window celebrates`() {
        assertEquals(MascotMood.CONFETTI, MascotMood.of(0f, null))
        assertEquals(MascotMood.CONFETTI, MascotMood.of(0f, 0f))
    }

    @Test fun `a nearly spent window flies the flag`() {
        assertEquals(MascotMood.FLAG, MascotMood.of(0.90f, 0.10f))
        assertEquals(MascotMood.FLAG, MascotMood.of(1.0f, null))
    }

    @Test fun `burning through it with room left lifts`() {
        assertEquals(MascotMood.MUSCLE, MascotMood.of(0.40f, 0.30f))
        // 1% over the window is the bar now, not 5%.
        assertEquals(MascotMood.MUSCLE, MascotMood.of(0.4101f, 0.40f))
    }

    /**
     * The spec's "and total < 90%" qualifier. Checking the burst before the high-water
     * mark would hide the warning at exactly the moment it matters most — the user is
     * burning hard AND nearly out.
     */
    @Test fun `a burst above the high mark still flies the flag`() {
        assertEquals(MascotMood.FLAG, MascotMood.of(0.95f, 0.80f))
    }

    @Test fun `steady use in the middle just stands`() {
        assertEquals(MascotMood.STAND, MascotMood.of(0.400f, 0.395f))
        assertEquals(MascotMood.STAND, MascotMood.of(0.400f, 0.400f))
    }

    /**
     * The threshold is exclusive, but do not assert it at exactly BURST: 0.40f - 0.39f
     * is 0.010000005 in float32, so an "exactly 1%" case tests the arithmetic's rounding
     * rather than the rule. Values either side of it are what matter.
     */
    @Test fun `the burst threshold is exclusive`() {
        assertEquals(MascotMood.STAND, MascotMood.of(0.3090f, 0.30f))
        assertEquals(MascotMood.MUSCLE, MascotMood.of(0.3120f, 0.30f))
    }

    /**
     * With no history the honest reading is "unknown", not "no burn". Inventing a
     * baseline from a single sample would fire the lifting animation on the first poll
     * after every restart.
     */
    @Test fun `no history cannot produce a burst`() {
        assertEquals(MascotMood.STAND, MascotMood.of(0.40f, null))
    }

    /** Usage can fall when a window rolls over; that is not a burst. */
    /** A minute is enough of a gap; five is the most that still counts. */
    @Test fun `the comparison window spans one to five minutes`() {
        assertEquals(60_000L, MascotMood.WINDOW_MS)
        assertEquals(5 * 60_000L, MascotMood.MAX_BASELINE_MS)
    }

    @Test fun `a falling window does not lift`() {
        assertEquals(MascotMood.STAND, MascotMood.of(0.10f, 0.80f))
    }

    // ---- the latch ----------------------------------------------------------------

    private val T = 1_700_000_000_000L

    @Test fun `a burst starts a five minute hold`() {
        val d = MascotMood.decide(0.20f, 0.10f, 0L, T)
        assertEquals(MascotMood.MUSCLE, d.clip)
        assertEquals(T + MascotMood.HOLD_MS, d.holdUntil)
    }

    /**
     * The reason the hold exists. Without it the next comparison is against the reading
     * the burst itself produced, so a steady stretch of work would flicker between
     * lifting and standing.
     */
    @Test fun `it keeps lifting between bursts`() {
        val start = MascotMood.decide(0.20f, 0.10f, 0L, T)
        val later = MascotMood.decide(0.20f, 0.20f, start.holdUntil, T + 2 * 60_000L)
        assertEquals(MascotMood.MUSCLE, later.clip)
        assertEquals("an idle poll must not extend the hold", start.holdUntil, later.holdUntil)
    }

    @Test fun `it stops five minutes after the last burst`() {
        val start = MascotMood.decide(0.20f, 0.10f, 0L, T)
        val after = MascotMood.decide(0.20f, 0.20f, start.holdUntil, T + MascotMood.HOLD_MS + 1)
        assertEquals(MascotMood.STAND, after.clip)
    }

    /** A burst mid-hold restarts the clock from that moment rather than topping up. */
    @Test fun `a second burst restarts the hold from now`() {
        val first = MascotMood.decide(0.20f, 0.10f, 0L, T)
        val t2 = T + 3 * 60_000L
        val second = MascotMood.decide(0.30f, 0.20f, first.holdUntil, t2)
        assertEquals(MascotMood.MUSCLE, second.clip)
        assertEquals(t2 + MascotMood.HOLD_MS, second.holdUntil)
        // still lifting well past when the first hold alone would have lapsed
        val late = MascotMood.decide(0.30f, 0.30f, second.holdUntil, T + 7 * 60_000L)
        assertEquals(MascotMood.MUSCLE, late.clip)
    }

    /** Where you stand outranks what you are doing. */
    @Test fun `hitting the high mark mid-hold flies the flag`() {
        val start = MascotMood.decide(0.20f, 0.10f, 0L, T)
        val d = MascotMood.decide(0.95f, 0.90f, start.holdUntil, T + 60_000L)
        assertEquals(MascotMood.FLAG, d.clip)
    }

    @Test fun `a reset to zero mid-hold celebrates`() {
        val start = MascotMood.decide(0.20f, 0.10f, 0L, T)
        val d = MascotMood.decide(0f, 0.20f, start.holdUntil, T + 60_000L)
        assertEquals(MascotMood.CONFETTI, d.clip)
    }
}
