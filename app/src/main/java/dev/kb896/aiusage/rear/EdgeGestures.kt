package dev.kb896.aiusage.rear

import kotlin.math.abs

/**
 * What a finger on the rear means to the shell service, which reads it from [RearTouches]
 * — the raw touchscreen, whoever HyperOS hands the touch to (our panel, a guest app, its
 * own gesture strips). See specs/2026-10-28-app-portal-shell-sheet-design.md.
 *
 * WITH THE SHEET SHUT ([sheet] false), only the two edges the App Portal owns:
 * - The bottom band ([PortalPull.startsPull]): [Action.Arm] the moment it lands, so the
 *   screenshot for the backdrop starts while the finger is still deciding (it takes about
 *   240 ms); [Action.PullStart] once it has moved up past [SLOP] more than sideways, then
 *   every move, then the lift; [Action.Disarm] if it turns out not to be a pull.
 * - The right edge, x ≥ [BACK_LEFT] — Xiaomi's own Back band, whose inert chevron then
 *   lines up with what happens: [Action.Back] on a lift that travelled left at least
 *   [BACK_TRAVEL] further than it travelled vertically.
 * Where the two bands meet (the bottom-right corner), the pull wins. Anything else is
 * nobody's.
 *
 * WITH THE SHEET UP ([sheet] true when a finger lands), every finger is the sheet's,
 * wherever it lands:
 * - [Action.SheetDown] at once (the caller stops a settle there and shows a press);
 * - a drag up or down past [SLOP], more vertical than sideways, is [Action.PullStart] /
 *   [Action.PullMove] / [Action.PullEnd], as a pull — the caller moves the sheet from
 *   wherever it was when the finger landed (a drag down closes an open sheet);
 * - [Action.LongPress] once, while still down, after [LONG_PRESS_MS] within [SLOP] of
 *   where it landed — from a move, or from [tick] when the finger holds so still that the
 *   touchscreen sends nothing;
 * - the lift: [Action.Tap] if it stayed within [SLOP] for no more than [TAP_MS] (at the
 *   landing point, where the press was shown), [Action.PullEnd] for a drag, otherwise
 *   [Action.Cancel]. Every sheet gesture ends in exactly one of the three.
 * [sheet] is read when a finger lands: a gesture keeps the kind it started with, so the
 * pull that opens the sheet runs to its own lift as a pull.
 *
 * A Down while the previous gesture never got its Up (a lost lift) closes that gesture
 * first, so nothing is left following a finger that is gone, and an armed screenshot is
 * never leaked.
 *
 * The windows under the touch see it too, unless the sheet's blocker is up; reading is
 * all the shell does.
 */
class EdgeGestures {

    sealed interface Action {
        data object Arm : Action
        data object Disarm : Action
        data class PullStart(val y: Float) : Action
        data class PullMove(val y: Float) : Action
        data class PullEnd(val y: Float) : Action
        data object Back : Action
        /** A finger landed on the sheet. */
        data class SheetDown(val x: Float, val y: Float) : Action
        /** A quick touch on the sheet, where it landed. */
        data class Tap(val x: Float, val y: Float) : Action
        /** A finger held still on the sheet, where it landed; the gesture goes on to its lift. */
        data class LongPress(val x: Float, val y: Float) : Action
        /** A sheet gesture that came to nothing: wandered, held too long, or lost. */
        data object Cancel : Action
    }

    private enum class Kind { NONE, PULL, BACK, SHEET }

    /** Whether the sheet is up, for fingers that land from now on. Set by the caller. */
    var sheet = false

    private var kind = Kind.NONE
    private var committed = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var lastY = 0f
    /** A sheet finger that went past the slop without becoming a drag. */
    private var wandered = false
    private var longFired = false

    fun feed(e: RearTouches.Event): List<Action> = when (e) {
        is RearTouches.Down -> {
            val stale = when {
                kind == Kind.PULL && committed -> listOf(Action.PullEnd(lastY))
                kind == Kind.PULL -> listOf(Action.Disarm)
                kind == Kind.SHEET && committed -> listOf(Action.PullEnd(lastY))
                kind == Kind.SHEET -> listOf(Action.Cancel)
                else -> emptyList()
            }
            downX = e.x; downY = e.y; lastY = e.y; downAt = e.atMs
            committed = false; wandered = false; longFired = false
            kind = when {
                sheet -> Kind.SHEET
                PortalPull.startsPull(e.x, e.y) -> Kind.PULL
                e.x >= BACK_LEFT -> Kind.BACK
                else -> Kind.NONE
            }
            stale + when (kind) {
                Kind.PULL -> listOf(Action.Arm)
                Kind.SHEET -> listOf(Action.SheetDown(e.x, e.y))
                else -> emptyList()
            }
        }
        is RearTouches.Move -> { lastY = e.y; when (kind) {
            Kind.PULL -> when {
                committed -> listOf(Action.PullMove(e.y))
                downY - e.y >= SLOP && downY - e.y > abs(e.x - downX) -> { committed = true; listOf(Action.PullStart(e.y)) }
                abs(e.x - downX) >= SLOP -> { kind = Kind.NONE; listOf(Action.Disarm) }
                else -> emptyList()
            }
            Kind.SHEET -> sheetMove(e)
            else -> emptyList()
        } }
        is RearTouches.Up -> {
            val out = when (kind) {
                Kind.PULL -> if (committed) listOf(Action.PullEnd(e.y)) else listOf(Action.Disarm)
                Kind.BACK -> if ((downX - e.x) - abs(e.y - downY) >= BACK_TRAVEL) listOf(Action.Back) else emptyList()
                Kind.SHEET -> sheetUp(e)
                Kind.NONE -> emptyList()
            }
            kind = Kind.NONE; committed = false
            out
        }
    }

    /**
     * The long-press, for a finger held so still that no move comes: call it once
     * [LONG_PRESS_MS] after a [Action.SheetDown] (a later call is harmless).
     */
    fun tick(nowMs: Long): List<Action> =
        if (kind == Kind.SHEET && !committed && !wandered && !longFired && nowMs - downAt >= LONG_PRESS_MS) {
            longFired = true
            listOf(Action.LongPress(downX, downY))
        } else emptyList()

    private fun sheetMove(e: RearTouches.Event): List<Action> {
        if (committed) return listOf(Action.PullMove(e.y))
        val dx = abs(e.x - downX)
        val dy = abs(e.y - downY)
        if (!wandered && dy >= SLOP && dy > dx) { committed = true; return listOf(Action.PullStart(e.y)) }
        if (maxOf(dx, dy) >= SLOP) wandered = true
        return tick(e.atMs)
    }

    private fun sheetUp(e: RearTouches.Event): List<Action> {
        if (committed) return listOf(Action.PullEnd(e.y))
        if (maxOf(abs(e.x - downX), abs(e.y - downY)) >= SLOP) wandered = true
        val held = e.atMs - downAt
        return when {
            wandered -> listOf(Action.Cancel)
            !longFired && held <= TAP_MS -> listOf(Action.Tap(downX, downY))
            // Held long enough, but lifted before anyone looked: the long-press, then its end.
            else -> tick(e.atMs) + Action.Cancel
        }
    }

    companion object {
        /** The panel's own drag slop (RearDialView.DRAG_SLOP), so both decide alike. */
        const val SLOP = 16f
        /** Where Xiaomi's SubScreenGestureBack band starts on the 17 Pro (measured: x 842–904). */
        const val BACK_LEFT = 842f
        const val BACK_TRAVEL = 60f
        /** The longest touch that is still a tap. */
        const val TAP_MS = 300L
        /** How long a still finger takes to become a long-press. */
        const val LONG_PRESS_MS = 450L
    }
}
