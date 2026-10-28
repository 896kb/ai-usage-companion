package dev.kb896.aiusage.rear

import kotlin.math.abs

/**
 * What a finger on the rear means while a guest app has the display — the two edges the
 * App Portal owns, read from [RearTouches] by the shell service, since nothing of ours can
 * receive the guest's touches.
 *
 * - The bottom band ([PortalPull.startsPull]): [Action.Arm] the moment it lands, so the
 *   screenshot for the frozen backdrop starts while the finger is still deciding (it
 *   takes about 240 ms); [Action.PullStart] once it has moved up past [SLOP] more than
 *   sideways, then every move, then the lift; [Action.Disarm] if it turns out not to be a
 *   pull.
 * - The right edge, x ≥ [BACK_LEFT] — Xiaomi's own Back band, whose inert chevron then
 *   lines up with what happens: [Action.Back] on a lift that travelled left at least
 *   [BACK_TRAVEL] further than it travelled vertically.
 *
 * The guest sees every one of these touches too; reading is all shell can do.
 */
class EdgeGestures {

    sealed interface Action {
        data object Arm : Action
        data object Disarm : Action
        data class PullStart(val y: Float) : Action
        data class PullMove(val y: Float) : Action
        data class PullEnd(val y: Float) : Action
        data object Back : Action
    }

    private enum class Kind { NONE, PULL, BACK }

    private var kind = Kind.NONE
    private var committed = false
    private var downX = 0f
    private var downY = 0f

    fun feed(e: RearTouches.Event): List<Action> = when (e) {
        is RearTouches.Down -> {
            downX = e.x; downY = e.y; committed = false
            kind = when {
                PortalPull.startsPull(e.x, e.y) -> Kind.PULL
                e.x >= BACK_LEFT -> Kind.BACK
                else -> Kind.NONE
            }
            if (kind == Kind.PULL) listOf(Action.Arm) else emptyList()
        }
        is RearTouches.Move -> when {
            kind != Kind.PULL -> emptyList()
            committed -> listOf(Action.PullMove(e.y))
            downY - e.y >= SLOP && downY - e.y > abs(e.x - downX) -> { committed = true; listOf(Action.PullStart(e.y)) }
            abs(e.x - downX) >= SLOP -> { kind = Kind.NONE; listOf(Action.Disarm) }
            else -> emptyList()
        }
        is RearTouches.Up -> {
            val out = when (kind) {
                Kind.PULL -> if (committed) listOf(Action.PullEnd(e.y)) else listOf(Action.Disarm)
                Kind.BACK -> if ((downX - e.x) - abs(e.y - downY) >= BACK_TRAVEL) listOf(Action.Back) else emptyList()
                Kind.NONE -> emptyList()
            }
            kind = Kind.NONE; committed = false
            out
        }
    }

    companion object {
        /** The panel's own drag slop (RearDialView.DRAG_SLOP), so both decide alike. */
        const val SLOP = 16f
        /** Where Xiaomi's SubScreenGestureBack band starts on the 17 Pro (measured: x 842–904). */
        const val BACK_LEFT = 842f
        const val BACK_TRAVEL = 60f
    }
}
