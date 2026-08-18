package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun `half the window gone, and nothing happening, is a nap`() {
        assertEquals(MascotMood.SLEEP, MascotMood.of(0.50f, 0.50f))
        assertEquals(MascotMood.SLEEP, MascotMood.of(0.75f, 0.75f))
        // Right up to the flag, which still wins.
        assertEquals(MascotMood.SLEEP, MascotMood.of(0.89f, 0.89f))
        assertEquals(MascotMood.FLAG, MascotMood.of(0.90f, 0.90f))
    }

    /** Doing something beats having done something: the nap yields to a live burst. */
    @Test fun `a burst past the halfway mark lifts rather than sleeps`() {
        assertEquals(MascotMood.MUSCLE, MascotMood.of(0.70f, 0.68f))
        val now = 1_000_000L
        assertEquals(MascotMood.MUSCLE,
            MascotMood.decide(0.70f, 0.68f, 0L, now).clip)
        // ...and goes back to sleep once the hold lapses, rather than to the walk.
        assertEquals(MascotMood.SLEEP,
            MascotMood.decide(0.70f, 0.70f, now - 1, now).clip)
    }

    /** Below halfway the idle state is unchanged — this rule only claims the top half. */
    @Test fun `steady use in the middle just stands`() {
        assertEquals(MascotMood.WALK, MascotMood.of(0.400f, 0.395f))
        assertEquals(MascotMood.WALK, MascotMood.of(0.400f, 0.400f))
    }

    /**
     * The threshold is exclusive, but do not assert it at exactly BURST: 0.40f - 0.39f
     * is 0.010000005 in float32, so an "exactly 1%" case tests the arithmetic's rounding
     * rather than the rule. Values either side of it are what matter.
     */
    /**
     * A single 1% step MUST fire. The API reports whole percentages, so this is the
     * smallest change that can ever be observed — requiring strictly more than 1% means
     * requiring 2%, and a steady 1%-per-minute burn would never trigger anything.
     * A live trail off the device read 0.34, 0.35, 0.35, 0.36.
     */
    @Test fun `one whole percent is enough`() {
        assertEquals(MascotMood.MUSCLE, MascotMood.of(0.35f, 0.34f))
        assertEquals(MascotMood.MUSCLE, MascotMood.of(0.36f, 0.35f))
        assertEquals(MascotMood.WALK, MascotMood.of(0.35f, 0.35f))
    }

    /**
     * With no history the honest reading is "unknown", not "no burn". Inventing a
     * baseline from a single sample would fire the lifting animation on the first poll
     * after every restart.
     */
    @Test fun `no history cannot produce a burst`() {
        assertEquals(MascotMood.WALK, MascotMood.of(0.40f, null))
    }

    /** Usage can fall when a window rolls over; that is not a burst. */
    /** A minute is enough of a gap; ten is the most that still counts. */
    @Test fun `the comparison window spans one to ten minutes`() {
        assertEquals(60_000L, MascotMood.WINDOW_MS)
        assertEquals(10 * 60_000L, MascotMood.MAX_BASELINE_MS)
    }

    /** The comparison reach and the animation's hold are separate knobs. */
    @Test fun `the hold is independent of the comparison reach`() {
        assertEquals(5 * 60_000L, MascotMood.HOLD_MS)
    }

    @Test fun `a falling window does not lift`() {
        assertEquals(MascotMood.WALK, MascotMood.of(0.10f, 0.80f))
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
        assertEquals(MascotMood.WALK, after.clip)
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

    // ---- the week spent -------------------------------------------------------------

    private fun window(kind: WindowKind, u: Float) = UsageWindow(kind, kind.wire, u, null)

    @Test fun `the weekly window at or above 99 percent is dead`() {
        assertTrue(MascotMood.weeklySpent(listOf(
            window(WindowKind.SESSION, 0f),
            window(WindowKind.WEEKLY_ALL, 0.99f),
        )))
        assertEquals(
            MascotMood.DEAD,
            MascotMood.decide(1.0f, null, 0L, T, weeklySpent = true).clip,
        )
    }

    /**
     * The bug this rule was rewritten for.
     *
     * A 5-hour window refills every five hours; the week does not. At the moment one
     * rolls over the session reads 0% while the week is still exhausted, and the old
     * rule — every window at or above 99% — went false, so a dead account threw
     * confetti at a fresh start it could not use.
     */
    @Test fun `a fresh session does not celebrate a spent week`() {
        val windows = listOf(
            window(WindowKind.SESSION, 0f),
            window(WindowKind.WEEKLY_ALL, 0.99f),
        )
        assertTrue(MascotMood.weeklySpent(windows))
        assertEquals(
            MascotMood.DEAD,
            MascotMood.decide(0f, null, 0L, T, MascotMood.weeklySpent(windows)).clip,
        )
    }

    /** A busy week that is not yet spent leaves the account alive. */
    @Test fun `a weekly window below the threshold is not dead`() {
        assertTrue(!MascotMood.weeklySpent(listOf(window(WindowKind.WEEKLY_ALL, 0.98f))))
    }

    /**
     * The scoped weekly is one model's share and can be exhausted while the account has
     * plenty left, so it must not be fatal on its own.
     */
    @Test fun `a spent scoped weekly is not death`() {
        assertTrue(!MascotMood.weeklySpent(listOf(
            window(WindowKind.SESSION, 0.10f),
            window(WindowKind.WEEKLY_SCOPED, 1.0f),
        )))
    }

    /**
     * An account with no weekly window is missing data, not out of allowance, and
     * reporting it as dead would turn a gap in the API into a verdict about the user.
     */
    @Test fun `no weekly window is not dead`() {
        assertTrue(!MascotMood.weeklySpent(emptyList()))
        assertTrue(!MascotMood.weeklySpent(listOf(window(WindowKind.SESSION, 1.0f))))
    }

    /**
     * Death outranks a live lifting hold. What the user is currently doing stops
     * mattering once there is nothing left to do it with.
     */
    @Test fun `dead outranks an active burst hold`() {
        val burst = MascotMood.decide(0.50f, 0.40f, 0L, T)
        assertEquals(MascotMood.MUSCLE, burst.clip)
        val dead = MascotMood.decide(1.0f, 0.99f, burst.holdUntil, T + 60_000L, weeklySpent = true)
        assertEquals(MascotMood.DEAD, dead.clip)
    }

    /** ...and outranks the flag, which is only about the 5-hour window. */
    @Test fun `dead outranks the flag`() {
        assertEquals(
            MascotMood.DEAD,
            MascotMood.decide(0.95f, null, 0L, T, weeklySpent = true).clip,
        )
    }
}
