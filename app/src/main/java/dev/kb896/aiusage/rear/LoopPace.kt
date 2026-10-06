package dev.kb896.aiusage.rear

/**
 * When a video's frames are due as it eases to a stop and back up again — the double tap,
 * always-on, a wake — rather than halting and jumping. Steady, a frame is due by [clock];
 * easing out from the frame at clip time `at`, the next `rampMs` of clip slow down along the
 * ease curve and the frames past that are not due at all (null: hold there); easing in, a
 * ramp is run the other way and the clock takes over where it ends.
 *
 * Clip times are a pass's own; a loop going round mid-ramp says so with [wrapped], so the
 * ramp's frames keep counting on from the pass before.
 */
class LoopPace(private val clock: RunClock) {
    private enum class Phase { STEADY, IN, OUT }

    private var phase = Phase.STEADY
    private var wall0 = 0L
    private var clip0 = 0L
    private var ramp = 0L

    /** Slows from the frame at [at] to a stop, starting [now]. */
    fun easeOut(now: Long, at: Long, rampMs: Long) {
        phase = Phase.OUT
        wall0 = now
        clip0 = at
        ramp = rampMs
    }

    /** Picks up speed from the frame at [at], starting [now]. */
    fun easeIn(now: Long, at: Long, rampMs: Long) {
        phase = Phase.IN
        wall0 = now
        clip0 = at
        ramp = rampMs
    }

    /** Carries on at once from the frame at [at], [now]. */
    fun resume(now: Long, at: Long) {
        phase = Phase.STEADY
        clock.startFrom(now, at)
    }

    /** Starts over: steady, the clock already set by the caller. */
    fun steady() { phase = Phase.STEADY }

    /** The loop went round: clip times start again from 0, [passMs] on from the last pass's. */
    fun wrapped(passMs: Long) {
        if (phase != Phase.STEADY) clip0 -= passMs
    }

    val stopping: Boolean get() = phase == Phase.OUT

    /** When the frame at clip time [pts] is due; null past an ease-out's stop. [wall] maps a steady frame. */
    fun due(pts: Long, wall: (Long) -> Long = { it }): Long? = when (phase) {
        Phase.STEADY -> clock.due(wall(pts))
        Phase.IN -> {
            val d = pts - clip0
            if (d <= ramp) {
                wall0 + inWall(d)
            } else {
                // Up to speed: the clock from here, set so this frame lands where the ramp left it.
                clock.restartAt(wall0 + inWall(ramp) - (clip0 + ramp))
                phase = Phase.STEADY
                clock.due(pts)
            }
        }
        Phase.OUT -> {
            val d = pts - clip0
            if (d <= ramp) wall0 + outWall(d) else null
        }
    }

    private fun inWall(d: Long): Long = EaseCurve.wallMs(d.coerceAtLeast(0), ramp * 4, ramp, 0).toLong()

    private fun outWall(d: Long): Long =
        (EaseCurve.wallMs(ramp + d.coerceAtLeast(0), ramp * 2, 0, ramp) - EaseCurve.wallMs(ramp, ramp * 2, 0, ramp)).toLong()
}
