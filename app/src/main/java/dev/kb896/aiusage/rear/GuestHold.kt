package dev.kb896.aiusage.rear

/**
 * Whether the shell holds the back lit over a guest, and when it looks again.
 *
 * HyperOS gives the rear ten seconds (subscreen_display_time), the last thirty per cent of it
 * dimmed, so an app on the back that does not keep its own screen on dims about seven seconds
 * after the last touch and is gone at ten. The panel never shows this: it holds
 * FLAG_KEEP_SCREEN_ON for thirty seconds past the last touch (RearActivity.AWAKE_MS), and that
 * hold counts for nothing while it is stopped behind a guest. The shell sees every touch on
 * the back, a guest's too (RearTouches), so it holds for the panel: the same thirty seconds
 * from the last touch, and then lets go.
 *
 * ONLY EVER ABOUT A BACK SOMEBODY IS USING. A lock does not light anything here (no
 * ACQUIRE_CAUSES_WAKEUP), but it does keep what is lit lit, so whatever puts the back down has
 * to win at once and stay won:
 * - [rearLit] not true (read as dark when unreadable — dark is the safe way to be wrong, as in
 *   PlaceLight), [dozing], [pocketed]: let go now, and every touch up to here is spent.
 *   Without that, a notification lighting the back five seconds after the power button would
 *   be held for the rest of a thirty seconds nobody asked for. Lit again, only a NEW touch
 *   takes it. Over a dark back the touch that woke it may well be the one seen as dark; the
 *   next is what takes it, and until then HyperOS's own ten seconds apply, as they did.
 * - A double tap: see [DoubleTaps]. Let go on the second landing, [QUIET_MS] of leaving it
 *   alone, and then the back's state says which it was: dark, HyperOS slept it, and the touches
 *   are spent as above; still lit, it was two quick taps in somebody's app, and the hold comes
 *   back with nothing having shown (HyperOS's timeout is far longer than the quiet).
 *
 * [step] is the whole of it, called from anywhere that might have changed the answer; the
 * caller does as [Step.act] says and, one timer only, looks again in [Step.recheckInMs] (none
 * when negative). Held, a touch costs the caller nothing: the look at the end of the time
 * reads the last touch, finds it later than it was, and only moves itself out. So there is
 * never a timer running for a back nobody touched: no touch, no timer. Not thread-safe; the
 * caller's lock.
 */
class GuestHold {

    enum class Act { TAKE, LET_GO, NONE }

    /** What to do now ([why] for the log, when not holding), and when to look again. */
    data class Step(val act: Act, val why: String?, val recheckInMs: Long)

    /** The caller has the lock. Set by [taken] and [released], so a refused take is not believed. */
    var held = false
        private set

    /** The last touch that counted for nothing any more: seen before a dark, a sleep or a pocket. */
    private var spentTouchAt = 0L

    /** Times are one clock (elapsedRealtime); 0 is never. */
    fun step(
        guestOnTop: Boolean,
        rearLit: Boolean?,
        dozing: Boolean,
        pocketed: Boolean,
        lastTouchAt: Long,
        doubleTapAt: Long,
        now: Long,
    ): Step {
        val down = rearLit != true || dozing || pocketed
        if (guestOnTop && down) spentTouchAt = lastTouchAt
        val sinceTouch = now - lastTouchAt
        val sinceDoubleTap = if (doubleTapAt == 0L) Long.MAX_VALUE else now - doubleTapAt
        var recheck = NO_RECHECK
        val why = when {
            !guestOnTop -> "no guest on top"
            dozing -> "asleep on purpose"
            pocketed -> "in a pocket"
            rearLit != true -> "the back is dark"
            lastTouchAt <= spentTouchAt -> "no touch since"
            sinceTouch >= HOLD_MS -> "${HOLD_MS / 1000} s past the last touch"
            sinceDoubleTap < QUIET_MS -> {
                recheck = QUIET_MS - sinceDoubleTap
                "a double tap"
            }
            else -> {
                recheck = HOLD_MS - sinceTouch
                null
            }
        }
        val act = when {
            why == null && !held -> Act.TAKE
            why != null && held -> Act.LET_GO
            else -> Act.NONE
        }
        return Step(act, why, recheck)
    }

    fun taken() { held = true }

    fun released() { held = false }

    /**
     * A double tap on the back, seen on its second landing.
     *
     * HyperOS's MiuiSubscreenDoubleTapGesture sleeps the rear over a guest: goToSleep(display 1,
     * now, reason 6, flags 0) on the second LIFT, for two taps under 400 ms and 100 px apart
     * (read from services.jar, OS4.0.5). In the code as read a screen lock stops none of that
     * — the one check against a screen lock is for GO_TO_SLEEP_FLAG_SOFT_SLEEP, which it does
     * not pass — but flags 0 takes the rear through DOZING first, and what a held bright lock
     * does to a group in that stage could not be read to the end. So it does not get to
     * find out: seen on the second landing, the lock is gone before that lift comes. Wider than
     * HyperOS's numbers, since the two clocks are not the same clock and a miss here is a sleep
     * fought while a false catch is one brief, unseen let-go. Reader thread only.
     */
    class DoubleTaps {
        private var downX = 0f
        private var downY = 0f
        private var liftedAt = NEVER

        /** True: this landing is the second of a double tap. */
        fun down(x: Float, y: Float, atMs: Long): Boolean {
            val second = liftedAt != NEVER && atMs - liftedAt <= WITHIN_MS && near(x, y)
            downX = x
            downY = y
            liftedAt = NEVER
            return second
        }

        /** A finger that travelled is a swipe, and no first tap. */
        fun up(x: Float, y: Float, atMs: Long) {
            liftedAt = if (near(x, y)) atMs else NEVER
        }

        private fun near(x: Float, y: Float) =
            kotlin.math.abs(x - downX) < SLOP_PX && kotlin.math.abs(y - downY) < SLOP_PX

        companion object {
            const val WITHIN_MS = 500L
            const val SLOP_PX = 120f
            private const val NEVER = -1L
        }
    }

    companion object {
        /** The panel's own thirty seconds. */
        const val HOLD_MS = RearActivity.AWAKE_MS
        /** After a double tap, how long the back is left to go dark before the hold is looked at again. */
        const val QUIET_MS = 2_000L
        private const val NO_RECHECK = -1L
    }
}
