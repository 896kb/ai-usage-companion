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
    /**
     * How far a pull travels from shut to open: three quarters of the panel's height. At 260 (under
     * half) it came twice as fast as the finger; the whole height was too long a pull (the owner,
     * 2026-11-09).
     */
    const val TRAVEL = dev.kb896.aiusage.Panel.HEIGHT * 0.75f
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
    /**
     * The shortest a settle takes, and the fastest a flick may send one: past these a fast swipe
     * drew a panel in four or five frames, as if it had appeared rather than come (the owner,
     * 2026-11-08; 1.6 and 200 ms were still too quick). Panel units a millisecond.
     */
    const val SETTLE_MIN_MS = 280L
    const val SETTLE_MAX_MS = 480L
    const val SPEED_MAX = 0.9f

    /**
     * How long the rest of the way takes, [distance] to go at [speed] toward it (panel units
     * a millisecond): the settle eases OUT, starting at twice its average speed, so this
     * starts it at the finger's — no stop, no slower restart — and slows it into place.
     */
    fun settleMs(distance: Float, speed: Float): Long {
        if (speed <= 0f) return SETTLE_MAX_MS
        return (2f * distance / minOf(speed, SPEED_MAX)).toLong().coerceIn(SETTLE_MIN_MS, SETTLE_MAX_MS)
    }

    /**
     * The fastest a panel follows a finger, panel units a millisecond. Under it, it is under the
     * finger; past it, it trails and catches up ([Chase]): a fast swipe brought the control centre
     * down in two frames, as if it had appeared (the owner, 2026-11-08).
     */
    const val DRAG_SPEED_MAX = 1.0f

    /**
     * A value that follows its target at no more than [maxSpeed] units a millisecond: a panel under
     * a finger, its place where the finger is unless the finger outruns it. Not thread-safe.
     */
    class Chase(private val maxSpeed: Float = DRAG_SPEED_MAX) {
        /** Where it is shown; NaN before the first [to] after a [reset]. */
        var shown = Float.NaN
            private set
        private var at = 0L

        /** Where it stands at [nowMs], gone toward [target] as far as the time since the last call allows. */
        fun to(target: Float, nowMs: Long): Float {
            if (shown.isNaN()) { shown = target; at = nowMs; return target }
            val step = maxSpeed * (nowMs - at).coerceIn(0L, MAX_STEP_MS)
            at = nowMs
            shown = if (kotlin.math.abs(target - shown) <= step) target else shown + kotlin.math.sign(target - shown) * step
            return shown
        }

        /** Shown at [value] now, nothing to catch up. */
        fun snap(value: Float, nowMs: Long) { shown = value; at = nowMs }

        fun reset() { shown = Float.NaN }

        private companion object {
            /** A frame missed is not a jump: no more than this much time is caught up in one step. */
            const val MAX_STEP_MS = 34L
        }
    }

    /**
     * The slider's level (0 to 1, as far as the liquid fills and the figure says) for a brightness
     * [setting] (1 to 255), and back ([setting]). ON A PERCEPTUAL CURVE, Android's own: the hybrid
     * log-gamma of its BrightnessUtils, which its brightness slider uses. The setting is linear in
     * light, and eyes are not: on a straight line the bottom tenth of the slider did most of what
     * could be seen and the top half next to nothing ("inconsistent, unnatural" — a user, through
     * the owner, 2026-11-09). Now an even drag looks like an even change.
     */
    fun level(setting: Int): Float = linearToGamma(((setting - 1) / 254f).coerceIn(0f, 1f)).coerceIn(MIN_LEVEL, 1f)

    fun setting(level: Float): Int = (1 + Math.round(gammaToLinear(level.coerceIn(0f, 1f)) * 254f)).coerceIn(1, 255)

    // BrightnessUtils' constants (frameworks/base, packages/SettingsLib): Rec. 2100 HLG, scaled so
    // the curve's 0..1 is the slider's and its 0..12 the light's.
    private const val HLG_R = 0.5f
    private const val HLG_A = 0.17883277f
    private const val HLG_B = 0.28466892f
    private const val HLG_C = 0.55991073f

    /** Light (0 to 1) to the slider's place on it (0 to 1). */
    fun linearToGamma(linear: Float): Float {
        val n = linear.coerceIn(0f, 1f) * 12f
        return if (n <= 1f) HLG_R * kotlin.math.sqrt(n) else HLG_A * kotlin.math.ln(n - HLG_B) + HLG_C
    }

    /** The slider's place (0 to 1) to light (0 to 1). */
    fun gammaToLinear(gamma: Float): Float {
        val g = gamma.coerceIn(0f, 1f)
        val n = if (g <= HLG_R) (g / HLG_R) * (g / HLG_R) else kotlin.math.exp((g - HLG_C) / HLG_A) + HLG_B
        return (n / 12f).coerceIn(0f, 1f)
    }

    /** The level after a drag of [dy] on the cell from [from]: up is brighter. */
    fun drag(from: Float, dy: Float): Float = (from - dy / CELL_TRAVEL).coerceIn(MIN_LEVEL, 1f)

    /** Whether the figure is up: while changing, and for [FIGURE_HOLD_MS] after. */
    fun figureShown(nowMs: Long, changedAtMs: Long): Boolean =
        changedAtMs > 0L && nowMs - changedAtMs < FIGURE_HOLD_MS
}
