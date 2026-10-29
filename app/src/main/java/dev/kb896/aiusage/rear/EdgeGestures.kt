package dev.kb896.aiusage.rear

import kotlin.math.abs

/**
 * What a finger on the rear means to the shell service, which reads it from [RearTouches]
 * — the raw touchscreen, whoever HyperOS hands the touch to (our panel, a guest app, its
 * own gesture strips). See specs/2026-10-28-app-portal-shell-sheet-design.md.
 *
 * WITH THE SHEET SHUT ([sheet] false), only the two edges the App Portal owns:
 * - The bottom band ([PortalPull.startsPull]): [Action.PullStart] once it has moved up
 *   past [SLOP] more than sideways — the one start that may open the sheet — then every
 *   move, then the lift; nothing if it turns out not to be a pull.
 * - The right edge, x ≥ [BACK_LEFT] — Xiaomi's own Back band, whose inert chevron then
 *   lines up with what happens: [Action.Back] on a lift that travelled left at least
 *   [BACK_TRAVEL] further than it travelled vertically.
 * - The top edge ([ControlCenter.startsPull]: within its EDGE of the top, over the left half
 *   of the usable area): [Action.TopPullStart] once it has moved DOWN past [SLOP] more than
 *   sideways — the control centre's pull, which the caller opens only over a guest (over
 *   our panel the panel's own control centre has it) — then [Action.TopPullMove] every
 *   move, and [Action.TopPullEnd] at the lift; nothing if it turns out not to be a pull.
 * Where the bottom and right bands meet (the bottom-right corner), the pull wins. Anything
 * else is nobody's.
 *
 * WITH THE SHEET UP ([sheet] true when a finger lands), every finger is the sheet's,
 * wherever it lands:
 * - [Action.SheetDown] at once (the caller stops a settle there and shows a press);
 * - a drag up or down past [SLOP], more vertical than sideways, is [Action.PullStart]
 *   (marked [Action.PullStart.onSheet]: it moves a sheet, never opens one — the sheet may
 *   have gone under the finger) / [Action.PullMove] / [Action.PullEnd], as a pull — the caller moves the sheet from
 *   wherever it was when the finger landed (a drag down lowers it a stop, or shuts it);
 * - a drag sideways past [SLOP], more sideways than vertical, is [Action.SlideStart] /
 *   [Action.SlideMove] / [Action.SlideEnd] — the caller decides by where it landed what
 *   it moves (the DPI slider, the grid's pages, or nothing). Which way a drag goes is
 *   decided once, by the first [SLOP] it travels, and kept to its lift;
 * - the lift: [Action.Tap] if it stayed within [SLOP] for no more than [TAP_MS] (at the
 *   landing point, where the press was shown), [Action.PullEnd] / [Action.SlideEnd] for a
 *   drag, otherwise [Action.Cancel]. Every sheet gesture ends in exactly one of these.
 * [sheet] is read when a finger lands: a gesture keeps the kind it started with, so the
 * pull that opens the sheet runs to its own lift as a pull.
 *
 * A Down while the previous gesture never got its Up (a lost lift) closes that gesture
 * first, so nothing is left following a finger that is gone.
 *
 * The windows under the touch see it too, unless the sheet's blocker is up; reading is
 * all the shell does.
 */
class EdgeGestures(
    /** Where the usable area starts, in panel units: the pull bands' left edge (RearGeometry.cutoutUnits). */
    private val usableLeft: Float,
) {

    sealed interface Action {
        /** A pull begins, the finger at [y]: from the bottom band, or ([onSheet]) a drag on the sheet. */
        data class PullStart(val y: Float, val onSheet: Boolean = false) : Action
        data class PullMove(val y: Float) : Action
        data class PullEnd(val y: Float) : Action
        /** The control centre's pull from the top edge, the finger at [y]. */
        data class TopPullStart(val y: Float) : Action
        data class TopPullMove(val y: Float) : Action
        data class TopPullEnd(val y: Float) : Action
        data object Back : Action
        /** A finger landed on the sheet. */
        data class SheetDown(val x: Float, val y: Float) : Action
        /** A quick touch on the sheet, where it landed. */
        data class Tap(val x: Float, val y: Float) : Action
        /** A sideways drag on the sheet, the finger now at [x]. */
        data class SlideStart(val x: Float) : Action
        data class SlideMove(val x: Float) : Action
        data class SlideEnd(val x: Float) : Action
        /** A sheet gesture that came to nothing: held too long, or lost. */
        data object Cancel : Action
    }

    private enum class Kind { NONE, PULL, TOP, BACK, SHEET }

    /** Whether the sheet is up, for fingers that land from now on. Set by the caller. */
    var sheet = false

    private var kind = Kind.NONE
    private var committed = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var lastX = 0f
    private var lastY = 0f
    /** A committed sheet drag is sideways (a slide), not a pull. */
    private var sideways = false

    fun feed(e: RearTouches.Event): List<Action> = when (e) {
        is RearTouches.Down -> {
            val stale = when {
                kind == Kind.PULL && committed -> listOf(Action.PullEnd(lastY))
                kind == Kind.TOP && committed -> listOf(Action.TopPullEnd(lastY))
                kind == Kind.SHEET && committed -> listOf(if (sideways) Action.SlideEnd(lastX) else Action.PullEnd(lastY))
                kind == Kind.SHEET -> listOf(Action.Cancel)
                else -> emptyList()
            }
            downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downAt = e.atMs
            committed = false; sideways = false
            kind = when {
                sheet -> Kind.SHEET
                PortalPull.startsPull(e.x, e.y, usableLeft) -> Kind.PULL
                ControlCenter.startsPull(e.x, e.y, usableLeft) -> Kind.TOP
                e.x >= BACK_LEFT -> Kind.BACK
                else -> Kind.NONE
            }
            stale + when (kind) {
                Kind.SHEET -> listOf(Action.SheetDown(e.x, e.y))
                else -> emptyList()
            }
        }
        is RearTouches.Move -> { lastX = e.x; lastY = e.y; when (kind) {
            Kind.PULL -> when {
                committed -> listOf(Action.PullMove(e.y))
                downY - e.y >= SLOP && downY - e.y > abs(e.x - downX) -> { committed = true; listOf(Action.PullStart(e.y)) }
                abs(e.x - downX) >= SLOP -> { kind = Kind.NONE; emptyList() }
                else -> emptyList()
            }
            Kind.TOP -> when {
                committed -> listOf(Action.TopPullMove(e.y))
                e.y - downY >= SLOP && e.y - downY > abs(e.x - downX) -> { committed = true; listOf(Action.TopPullStart(e.y)) }
                abs(e.x - downX) >= SLOP || downY - e.y >= SLOP -> { kind = Kind.NONE; emptyList() }
                else -> emptyList()
            }
            Kind.SHEET -> sheetMove(e)
            else -> emptyList()
        } }
        is RearTouches.Up -> {
            val out = when (kind) {
                Kind.PULL -> if (committed) listOf(Action.PullEnd(e.y)) else emptyList()
                Kind.TOP -> if (committed) listOf(Action.TopPullEnd(e.y)) else emptyList()
                Kind.BACK -> if ((downX - e.x) - abs(e.y - downY) >= BACK_TRAVEL) listOf(Action.Back) else emptyList()
                Kind.SHEET -> sheetUp(e)
                Kind.NONE -> emptyList()
            }
            kind = Kind.NONE; committed = false
            out
        }
    }

    private fun sheetMove(e: RearTouches.Event): List<Action> {
        if (committed) return listOf(if (sideways) Action.SlideMove(e.x) else Action.PullMove(e.y))
        val dx = abs(e.x - downX)
        val dy = abs(e.y - downY)
        if (maxOf(dx, dy) < SLOP) return emptyList()
        committed = true
        sideways = dx > dy
        return listOf(if (sideways) Action.SlideStart(e.x) else Action.PullStart(e.y, onSheet = true))
    }

    private fun sheetUp(e: RearTouches.Event): List<Action> {
        if (committed) return listOf(if (sideways) Action.SlideEnd(e.x) else Action.PullEnd(e.y))
        val moved = maxOf(abs(e.x - downX), abs(e.y - downY)) >= SLOP
        return when {
            moved -> listOf(Action.Cancel)
            e.atMs - downAt <= TAP_MS -> listOf(Action.Tap(downX, downY))
            else -> listOf(Action.Cancel)
        }
    }

    companion object {
        /** The panel's own drag slop (RearDialView.DRAG_SLOP), so both decide alike. */
        const val SLOP = 16f
        /**
         * Where Xiaomi's SubScreenGestureBack band starts, in panel units: measured on the 17 Pro
         * as x 842–904 (its 62 px at the right edge). Not read from the system — the band is
         * Xiaomi's window, and nothing reports it — and not measured on a Pro Max: there it is
         * taken to scale with the display as everything in panel units does (909 px of 976).
         * The sheet's contents stay left of it (PortalSheet), and the control centre's Back
         * candidates start at it (ControlCenterFinger), so a wrong guess there costs a Back that
         * starts a few pixels further in, not a tap.
         */
        const val BACK_LEFT = 842f
        const val BACK_TRAVEL = 60f
        /** The longest touch that is still a tap. */
        const val TAP_MS = 300L
    }
}
