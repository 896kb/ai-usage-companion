package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

/**
 * The back's control centre: pulled down from the top edge, over a panel that blurs as it
 * comes, holding — for now — the rear display's brightness as a cell of liquid.
 *
 * PULLED FROM THE EDGE ITSELF, as the system's own edge gestures are: a finger that lands
 * within [EDGE] of the top, over the left half of the usable area (right of the camera
 * column). Anywhere lower is the page's — its scroll, its notifications. It follows the
 * finger over [TRAVEL] and opens past halfway; open, a drag up closes it the same way.
 *
 * The brightness is HyperOS's own (system sub_display_screen_brightness, 1 to 255), shown
 * as a level from [MIN_LEVEL] to 1 that the liquid fills to.
 */
object ControlCenter {

    /**
     * How close to the top a pull must start, in panel units. Sixteen was the system's own
     * edge width and too tight here: a finger on the back of a phone lands short of the
     * glass's rounded top edge.
     */
    const val EDGE = 48f
    /** How far a pull travels from shut to open. */
    const val TRAVEL = 260f
    /** How far a drag on the cell takes the level from empty to full. */
    const val CELL_TRAVEL = 360f
    /** The dimmest it goes: one per cent, never black. */
    const val MIN_LEVEL = 0.01f
    /** How long the figure stays after the last change. */
    const val FIGURE_HOLD_MS = 2_000L
    /** How often a drag on the cell writes the setting, at most. */
    const val SEND_MS = 50L
    /** How long a new brightness reading takes to pour in. */
    const val POUR_MS = 600f
    /** How long a button or a tile takes to change colour, on to off and back. */
    const val SWITCH_MS = 250f

    /**
     * A finger at [x], [y] (panel units) starts a pull: within [EDGE] of the top, over the left
     * half of the usable area right of [left] — the lens column's edge, given: the panel's
     * own Panel.CUTOUT_RIGHT for the panel's centre, the rear's (RearGeometry.cutoutUnits) for
     * the shell's.
     */
    fun startsPull(x: Float, y: Float, left: Float): Boolean =
        y <= EDGE && x >= left && x <= left + (Panel.WIDTH - left) / 2f

    /** How far open, 0 to 1, under a finger that has moved [dy] (down positive). */
    fun progress(open: Boolean, dy: Float): Float =
        ((if (open) 1f else 0f) + dy / TRAVEL).coerceIn(0f, 1f)

    /**
     * Whether it is open once the finger lets go: a flick ([velocity], panel units a
     * millisecond, down positive) goes the way it was flicked; otherwise past halfway it
     * goes, short of that it springs back.
     */
    fun release(open: Boolean, dy: Float, velocity: Float = 0f): Boolean = when {
        velocity > FLING -> true
        velocity < -FLING -> false
        else -> progress(open, dy) > 0.5f
    }

    /** A flick, in panel units a millisecond. */
    const val FLING = 0.6f
    const val SETTLE_MIN_MS = 80L
    const val SETTLE_MAX_MS = 450L

    /**
     * How long the rest of the way takes, [distance] to go at [speed] toward it (panel units
     * a millisecond): the settle eases OUT, starting at twice its average speed, so this
     * starts it at the finger's — no stop, no slower restart — and slows it into place.
     */
    fun settleMs(distance: Float, speed: Float): Long {
        if (speed <= 0f) return SETTLE_MAX_MS
        return (2f * distance / speed).toLong().coerceIn(SETTLE_MIN_MS, SETTLE_MAX_MS)
    }

    fun level(setting: Int): Float = ((setting - 1) / 254f).coerceIn(MIN_LEVEL, 1f)

    fun setting(level: Float): Int = (1 + Math.round(level.coerceIn(0f, 1f) * 254f)).coerceIn(1, 255)

    /** The level after a drag of [dy] on the cell from [from]: up is brighter. */
    fun drag(from: Float, dy: Float): Float = (from - dy / CELL_TRAVEL).coerceIn(MIN_LEVEL, 1f)

    /** Whether the figure is up: while changing, and for [FIGURE_HOLD_MS] after. */
    fun figureShown(nowMs: Long, changedAtMs: Long): Boolean =
        changedAtMs > 0L && nowMs - changedAtMs < FIGURE_HOLD_MS
}
