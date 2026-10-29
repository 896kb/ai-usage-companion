package dev.kb896.aiusage.rear

import kotlin.math.abs

/**
 * What a finger means on the control centre the shell holds open over a guest
 * (ShellControlCenter), read off the raw rear touchscreen ([RearTouches]). Decided as the
 * panel's own control centre decides it (RearDialView.controlCenterTouch), so both behave
 * alike, plus the right edge's Back:
 *
 * - [Action.Down] as it lands, with what it landed on ([hit], ControlCenterLayout.hit);
 * - past [EdgeGestures.SLOP], once: up or down on the cell, more than sideways, is the
 *   brightness ([Action.Bright], then [Action.BrightEnd] at the lift); sideways from
 *   Xiaomi's Back band (x ≥ [EdgeGestures.BACK_LEFT]) is a Back candidate; anything else
 *   moves the centre ([Action.Pull], then [Action.Release] with the finger's speed — none if
 *   it had stopped for [STILL_MS] before lifting);
 * - a lift that never moved is a [Action.Tap] on what it landed on, however long it was held;
 * - a Back candidate that travelled left at least [EdgeGestures.BACK_TRAVEL] further than it
 *   travelled vertically is [Action.Back] — the centre shuts, rather than the guest under it
 *   going back — and otherwise [Action.Cancel].
 *
 * Every finger ends in exactly one of Release, BrightEnd, Tap, Back or Cancel. A Down while
 * the previous finger never got its Up (a lost lift) closes that one first, as the panel's
 * ACTION_CANCEL does: a pull is released where it was ([Action.Release.lifted] false — it
 * stays open or shut as it was), anything else cancelled.
 *
 * Distances are in panel units from where the finger landed, down positive.
 */
class ControlCenterFinger(private val hit: (Float, Float) -> Int) {

    sealed interface Action {
        /** A finger landed on [target]: a tile (0 to 3), ControlCenterLayout.AUTO, CELL or NONE. */
        data class Down(val target: Int) : Action
        /** The centre follows the finger, [dy] from where it landed. */
        data class Pull(val dy: Float) : Action
        /**
         * The pull let go after [dy], at [velocity] (panel units a millisecond, down positive).
         * [lifted] false: lost, not lifted — the centre stays as it was.
         */
        data class Release(val dy: Float, val velocity: Float, val lifted: Boolean) : Action
        /** The brightness follows a drag on the cell, [dy] from where it landed. */
        data class Bright(val dy: Float) : Action
        data object BrightEnd : Action
        /** A touch that never moved, lifted, on [target]. */
        data class Tap(val target: Int) : Action
        /** A swipe in from the right edge. */
        data object Back : Action
        /** A finger that came to nothing. */
        data object Cancel : Action
    }

    private enum class Mode { NONE, UNDECIDED, PULL, BRIGHT, BACK }

    private var mode = Mode.NONE
    private var target = ControlCenterLayout.NONE
    private var downX = 0f
    private var downY = 0f
    private var dy = 0f
    /** The pull's speed, smoothed a little, as the panel's ccVelocity. */
    private var velocity = 0f
    private var lastY = 0f
    private var lastAt = 0L

    fun feed(e: RearTouches.Event): List<Action> = when (e) {
        is RearTouches.Down -> {
            val stale = when (mode) {
                Mode.PULL -> listOf(Action.Release(dy, 0f, lifted = false))
                Mode.BRIGHT -> listOf(Action.BrightEnd)
                Mode.UNDECIDED, Mode.BACK -> listOf(Action.Cancel)
                Mode.NONE -> emptyList()
            }
            downX = e.x; downY = e.y; dy = 0f
            velocity = 0f; lastY = e.y; lastAt = e.atMs
            target = hit(e.x, e.y)
            mode = Mode.UNDECIDED
            stale + Action.Down(target)
        }
        is RearTouches.Move -> move(e)
        is RearTouches.Up -> {
            val out = when (mode) {
                Mode.PULL -> listOf(Action.Release(dy, if (e.atMs - lastAt > STILL_MS) 0f else velocity, lifted = true))
                Mode.BRIGHT -> listOf(Action.BrightEnd)
                Mode.UNDECIDED -> listOf(Action.Tap(target))
                Mode.BACK -> listOf(if ((downX - e.x) - abs(e.y - downY) >= EdgeGestures.BACK_TRAVEL) Action.Back else Action.Cancel)
                Mode.NONE -> emptyList()
            }
            mode = Mode.NONE
            out
        }
    }

    private fun move(e: RearTouches.Event): List<Action> {
        val dx = e.x - downX
        val d = e.y - downY
        if (mode == Mode.UNDECIDED && maxOf(abs(dx), abs(d)) >= EdgeGestures.SLOP) {
            mode = when {
                downX >= EdgeGestures.BACK_LEFT && abs(dx) > abs(d) -> Mode.BACK
                target == ControlCenterLayout.CELL && abs(d) > abs(dx) -> Mode.BRIGHT
                else -> Mode.PULL
            }
        }
        return when (mode) {
            Mode.PULL -> {
                dy = d
                // Its speed, smoothed a little, for carrying on after the finger lets go.
                val ms = (e.atMs - lastAt).coerceAtLeast(1L)
                velocity = 0.6f * ((e.y - lastY) / ms) + 0.4f * velocity
                lastY = e.y; lastAt = e.atMs
                listOf(Action.Pull(d))
            }
            Mode.BRIGHT -> listOf(Action.Bright(d))
            else -> emptyList()
        }
    }

    companion object {
        /** A finger still this long before it lifts is not flicking (the panel's 80 ms). */
        const val STILL_MS = 80L
    }
}
