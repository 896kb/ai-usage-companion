package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's hold on the back over a guest: thirty seconds past the last touch on a lit back
 * with a guest on top, and never a moment of it against somebody putting the back down.
 */
class GuestHoldTest {

    private val hold = GuestHold()

    /** A lit back, a guest on top, touched at 1000, at 2000 now — unless a test says otherwise. */
    private fun step(
        guestOnTop: Boolean = true,
        rearLit: Boolean? = true,
        dozing: Boolean = false,
        pocketed: Boolean = false,
        lastTouchAt: Long = 1_000L,
        doubleTapAt: Long = 0L,
        now: Long = 2_000L,
    ) = hold.step(guestOnTop, rearLit, dozing, pocketed, lastTouchAt, doubleTapAt, now)

    private fun takeIt(lastTouchAt: Long = 1_000L, now: Long = 2_000L) {
        assertEquals(GuestHold.Act.TAKE, step(lastTouchAt = lastTouchAt, now = now).act)
        hold.taken()
    }

    private fun assertLetsGo(s: GuestHold.Step, why: String) {
        assertEquals(GuestHold.Act.LET_GO, s.act)
        assertTrue("'${s.why}' should say $why", s.why!!.contains(why))
        assertEquals(-1L, s.recheckInMs)
    }

    @Test fun `a touch on a lit back with a guest on top takes it, for thirty seconds from the touch`() {
        val s = step()
        assertEquals(GuestHold.Act.TAKE, s.act)
        assertEquals(GuestHold.HOLD_MS - 1_000L, s.recheckInMs)
    }

    @Test fun `it is the panel's thirty seconds`() {
        assertEquals(RearActivity.AWAKE_MS, GuestHold.HOLD_MS)
    }

    @Test fun `held, a later touch only moves the check out`() {
        takeIt()
        val s = step(lastTouchAt = 20_000L, now = 25_000L)
        assertEquals(GuestHold.Act.NONE, s.act)
        assertEquals(GuestHold.HOLD_MS - 5_000L, s.recheckInMs)
    }

    @Test fun `thirty seconds past the last touch it lets go, and no timer runs on`() {
        takeIt()
        val s = step(now = 1_000L + GuestHold.HOLD_MS)
        assertLetsGo(s, "past the last touch")
        hold.released()
        val after = step(now = 60_000L)
        assertEquals(GuestHold.Act.NONE, after.act)
        assertEquals(-1L, after.recheckInMs)
    }

    @Test fun `the next touch after that takes it again`() {
        takeIt()
        step(now = 40_000L); hold.released()
        assertEquals(GuestHold.Act.TAKE, step(lastTouchAt = 41_000L, now = 41_001L).act)
    }

    @Test fun `no touch yet, nothing to hold for`() {
        val s = step(lastTouchAt = 0L)
        assertEquals(GuestHold.Act.NONE, s.act)
        assertEquals(-1L, s.recheckInMs)
    }

    @Test fun `no guest on top, nothing held, nothing timed`() {
        val s = step(guestOnTop = false)
        assertEquals(GuestHold.Act.NONE, s.act)
        assertEquals(-1L, s.recheckInMs)
    }

    @Test fun `a guest gone or covered lets go at once`() {
        takeIt()
        assertLetsGo(step(guestOnTop = false), "no guest")
    }

    @Test fun `a dark back is never taken — the lock must not be what lights it`() {
        assertEquals(GuestHold.Act.NONE, step(rearLit = false).act)
    }

    @Test fun `an unreadable display counts as dark`() {
        assertEquals(GuestHold.Act.NONE, step(rearLit = null).act)
    }

    @Test fun `the back going dark lets go at once`() {
        takeIt()
        assertLetsGo(step(rearLit = false), "dark")
    }

    @Test fun `asleep on purpose lets go at once, lit or not`() {
        takeIt()
        assertLetsGo(step(dozing = true), "asleep")
    }

    @Test fun `a pocket lets go at once, lit or not`() {
        takeIt()
        assertLetsGo(step(pocketed = true), "pocket")
    }

    @Test fun `lit again by something else, it waits for a touch — not the one from before the dark`() {
        takeIt()
        step(rearLit = false); hold.released()
        // A notification lights the back seconds later: the old touch is spent.
        assertEquals(GuestHold.Act.NONE, step(now = 5_000L).act)
        assertEquals(GuestHold.Act.TAKE, step(lastTouchAt = 6_000L, now = 6_001L).act)
    }

    @Test fun `a touch from before the dark is spent even if nothing was held then`() {
        assertEquals(GuestHold.Act.NONE, step(rearLit = false).act)
        assertEquals(GuestHold.Act.NONE, step(now = 5_000L).act)
    }

    @Test fun `out of a pocket or a sleep, likewise`() {
        step(pocketed = true)
        assertEquals(GuestHold.Act.NONE, step(now = 5_000L).act)
        step(dozing = true)
        assertEquals(GuestHold.Act.NONE, step(now = 6_000L).act)
    }

    @Test fun `a touch before a guest arrives counts — it may be the tap that opened it`() {
        val s = step(guestOnTop = true, lastTouchAt = 1_000L, now = 3_000L)
        assertEquals(GuestHold.Act.TAKE, s.act)
    }

    @Test fun `a double tap lets go before HyperOS's sleep, and looks again after the quiet`() {
        takeIt()
        val s = step(lastTouchAt = 3_000L, doubleTapAt = 3_000L, now = 3_001L)
        assertEquals(GuestHold.Act.LET_GO, s.act)
        assertTrue(s.why!!.contains("double tap"))
        assertEquals(GuestHold.QUIET_MS - 1L, s.recheckInMs)
    }

    @Test fun `touches in the quiet do not take it back`() {
        takeIt()
        step(lastTouchAt = 3_000L, doubleTapAt = 3_000L, now = 3_001L); hold.released()
        assertEquals(GuestHold.Act.NONE, step(lastTouchAt = 3_100L, doubleTapAt = 3_000L, now = 3_100L).act)
    }

    @Test fun `still lit after the quiet, it was only two quick taps — taken back`() {
        takeIt()
        step(lastTouchAt = 3_000L, doubleTapAt = 3_000L, now = 3_001L); hold.released()
        assertEquals(GuestHold.Act.TAKE, step(lastTouchAt = 3_100L, doubleTapAt = 3_000L, now = 3_000L + GuestHold.QUIET_MS).act)
    }

    @Test fun `dark after the quiet, it stays let go`() {
        takeIt()
        step(lastTouchAt = 3_000L, doubleTapAt = 3_000L, now = 3_001L); hold.released()
        val s = step(rearLit = false, lastTouchAt = 3_100L, doubleTapAt = 3_000L, now = 3_000L + GuestHold.QUIET_MS)
        assertEquals(GuestHold.Act.NONE, s.act)
        assertEquals(-1L, s.recheckInMs)
    }

    @Test fun `refused, a step asks again rather than believing it held`() {
        assertEquals(GuestHold.Act.TAKE, step().act)
        // taken() never called
        assertEquals(GuestHold.Act.TAKE, step().act)
        assertFalse(hold.held)
    }

    // The double tap, as HyperOS's MiuiSubscreenDoubleTapGesture counts it (400 ms, 100 px).

    @Test fun `two taps close together are a double tap, on the second landing`() {
        val d = GuestHold.DoubleTaps()
        assertFalse(d.down(100f, 100f, 0L))
        d.up(100f, 100f, 50L)
        assertTrue(d.down(110f, 105f, 300L))
    }

    @Test fun `a slow second tap is not`() {
        val d = GuestHold.DoubleTaps()
        d.down(100f, 100f, 0L); d.up(100f, 100f, 50L)
        assertFalse(d.down(100f, 100f, 50L + GuestHold.DoubleTaps.WITHIN_MS + 1L))
    }

    @Test fun `a far second tap is not`() {
        val d = GuestHold.DoubleTaps()
        d.down(100f, 100f, 0L); d.up(100f, 100f, 50L)
        assertFalse(d.down(100f + GuestHold.DoubleTaps.SLOP_PX + 1f, 100f, 100L))
    }

    @Test fun `a swipe is no first tap`() {
        val d = GuestHold.DoubleTaps()
        d.down(100f, 100f, 0L); d.up(400f, 100f, 50L)
        assertFalse(d.down(400f, 100f, 100L))
    }

    @Test fun `the catch is wider than HyperOS's, so it never sleeps past us`() {
        assertTrue(GuestHold.DoubleTaps.WITHIN_MS >= 400L)
        assertTrue(GuestHold.DoubleTaps.SLOP_PX >= 100f)
    }

    @Test fun `the third tap of three still counts, against the second`() {
        val d = GuestHold.DoubleTaps()
        d.down(100f, 100f, 0L); d.up(100f, 100f, 50L)
        assertTrue(d.down(100f, 100f, 200L)); d.up(100f, 100f, 250L)
        assertTrue(d.down(100f, 100f, 400L))
    }

    @Test fun `a guest coming on top of a lit back counts as a touch`() {
        val h = GuestHold()
        assertEquals(0L, h.openedAt(guestOnTop = false, rearLit = true, now = 500))
        assertEquals(1_000L, h.openedAt(guestOnTop = true, rearLit = true, now = 1_000))
        // Only once, however often it is looked at.
        assertEquals(1_000L, h.openedAt(guestOnTop = true, rearLit = true, now = 9_000))
    }

    @Test fun `a guest on top of a dark back counts from the back lighting`() {
        val h = GuestHold()
        assertEquals(0L, h.openedAt(guestOnTop = true, rearLit = false, now = 1_000))
        assertEquals(2_000L, h.openedAt(guestOnTop = true, rearLit = true, now = 2_000))
    }

    @Test fun `the back relit under the same guest is not a new opening, a new guest is`() {
        val h = GuestHold()
        h.openedAt(guestOnTop = true, rearLit = true, now = 1_000)
        assertEquals(1_000L, h.openedAt(guestOnTop = true, rearLit = false, now = 2_000))
        assertEquals(1_000L, h.openedAt(guestOnTop = true, rearLit = true, now = 3_000))
        h.openedAt(guestOnTop = false, rearLit = true, now = 4_000)
        assertEquals(5_000L, h.openedAt(guestOnTop = true, rearLit = true, now = 5_000))
    }

    @Test fun `the time running out lets go with the dim's tail, nothing else does`() {
        fun let(h: GuestHold, dozing: Boolean = false, pocketed: Boolean = false, lit: Boolean? = true, touch: Long = 1_000L, now: Long = 2_000L, top: Boolean = true) =
            h.step(top, lit, dozing, pocketed, touch, 0L, now)
        val held = { GuestHold().apply { taken() } }
        val out = let(held(), now = 1_000L + GuestHold.HOLD_MS)
        assertEquals(GuestHold.Act.LET_GO, out.act)
        assertEquals(true, out.tail)
        assertEquals(false, let(held(), dozing = true).tail)
        assertEquals(false, let(held(), pocketed = true).tail)
        assertEquals(false, let(held(), lit = false).tail)
        assertEquals(false, let(held(), top = false).tail)
    }

    @Test fun `a double tap is the app's only over a guest on a lit back`() {
        assertTrue(GuestHold.guards(guestOnTop = true, rearLit = true))
        assertFalse(GuestHold.guards(guestOnTop = true, rearLit = false))
        assertFalse(GuestHold.guards(guestOnTop = true, rearLit = null))
        assertFalse(GuestHold.guards(guestOnTop = false, rearLit = true))
    }

    @Test fun `an app keeping its screen on holds the back past the thirty seconds, and the usual thirty after it stops`() {
        val h = GuestHold()
        fun step(now: Long, holding: Boolean) = h.step(true, true, false, false, lastTouchAt = 1_000L, doubleTapAt = 0L, now = now, appHolding = holding)
        val taken = step(2_000L, true)
        assertEquals(GuestHold.Act.TAKE, taken.act)
        h.taken()
        // Ten minutes on, no touch since: still held, and looked at again.
        val on = step(600_000L, true)
        assertEquals(GuestHold.Act.NONE, on.act)
        assertTrue(on.recheckInMs > 0L)
        // It stopped: thirty seconds from the last look that heard it.
        assertEquals(GuestHold.Act.NONE, step(600_000L + GuestHold.HOLD_MS - 1, false).act)
        val out = step(600_000L + GuestHold.HOLD_MS, false)
        assertEquals(GuestHold.Act.LET_GO, out.act)
        assertTrue("the dim tail", out.tail)
    }

    @Test fun `asleep on purpose, a pocket or a dark back still win over an app keeping its screen on`() {
        for ((lit, dozing, pocketed) in listOf(Triple(true, true, false), Triple(true, false, true), Triple(false, false, false))) {
            val h = GuestHold()
            h.step(true, true, false, false, 1_000L, 0L, 2_000L, appHolding = true)
            h.taken()
            val s = h.step(true, lit, dozing, pocketed, 1_000L, 0L, 3_000L, appHolding = true)
            assertEquals(GuestHold.Act.LET_GO, s.act)
            assertFalse("no tail: that poke would light it again", s.tail)
        }
    }

    @Test fun `what an app held for is spent going dark, and only hearing it again takes the back again`() {
        val h = GuestHold()
        h.step(true, true, false, false, 1_000L, 0L, 2_000L, appHolding = true)
        h.taken()
        h.step(true, false, false, false, 1_000L, 0L, 3_000L, appHolding = true)
        h.released()
        assertEquals(GuestHold.Act.NONE, h.step(true, true, false, false, 1_000L, 0L, 4_000L, appHolding = false).act)
        assertEquals(GuestHold.Act.TAKE, h.step(true, true, false, false, 1_000L, 0L, 5_000L, appHolding = true).act)
    }
}
