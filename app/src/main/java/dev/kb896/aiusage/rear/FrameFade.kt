package dev.kb896.aiusage.rear

import kotlin.math.cbrt

/**
 * How much of the portal frame shows, 0 to 1 (PortalFrame multiplies it into its layer's
 * ALPHA): it fades in over [IN_MS] on an ease-out cubic when it comes up, and out over
 * [OUT_MS] on an ease-in cubic when it goes — it used to cut in and out ("instantly
 * appears / disappears, instead of fading"). A turn mid-fade starts from the level it has
 * reached, on the other curve at the point that gives that level, so there is never a
 * jump; asking for where it is already going changes nothing. Pure; PortalFrame keeps one
 * under its lock. Times are a monotonic clock's ms.
 */
class FrameFade {

    /** Where it is going: shown or not. */
    var shown = false
        private set
    private var fromMs = 0L
    /** Where on its curve it was at [fromMs], 0 to 1. */
    private var fromT = 1f

    /** Fade in from wherever it is. */
    fun show(nowMs: Long) = turn(true, nowMs)

    /** Fade out from wherever it is. */
    fun hide(nowMs: Long) = turn(false, nowMs)

    /** How much of it shows at [nowMs]. */
    fun level(nowMs: Long): Float {
        val t = t(nowMs)
        return if (shown) {
            val u = 1f - t
            1f - u * u * u
        } else {
            1f - t * t * t
        }
    }

    /** Hidden and faded all the way: nothing left to draw. */
    fun gone(nowMs: Long): Boolean = !shown && t(nowMs) >= 1f

    private fun t(nowMs: Long): Float =
        (fromT + (nowMs - fromMs).toFloat() / (if (shown) IN_MS else OUT_MS)).coerceIn(0f, 1f)

    private fun turn(show: Boolean, nowMs: Long) {
        if (show == shown) return
        val level = level(nowMs)
        shown = show
        fromMs = nowMs
        // The point on the new curve that is at [level]: ease-out's inverse, or ease-in's.
        fromT = if (show) 1f - cbrt(1f - level) else cbrt(1f - level)
    }

    companion object {
        const val IN_MS = 300L
        const val OUT_MS = 250L
    }
}
