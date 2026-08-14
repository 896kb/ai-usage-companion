package dev.rgarjoaba.claudestatus.rear

import dev.rgarjoaba.claudestatus.Panel

/**
 * The arch_rice usage ramp: green -> yellow -> orange -> red -> maxed.
 *
 * Per-component sRGB lerp between adjacent stops — deliberately NOT a
 * perceptual/linear-light blend, because the approved mockups were computed
 * this way and the rendered colours must match them exactly.
 */
object RampColor {

    fun at(t: Float): Int {
        val stops = Panel.RAMP_STOPS
        val clamped = when {
            t.isNaN() -> 0f
            t < 0f -> 0f
            t > 1f -> 1f
            else -> t
        }
        for (i in 0 until stops.size - 1) {
            val (p0, c0) = stops[i]
            val (p1, c1) = stops[i + 1]
            if (clamped <= p1) {
                val span = p1 - p0
                val f = if (span <= 0f) 0f else (clamped - p0) / span
                return lerp(c0, c1, f)
            }
        }
        return stops.last().second
    }

    private fun lerp(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            // Half-up rounding, NOT truncation. Truncating loses up to a full
            // level per channel and makes the ramp read consistently darker
            // than the approved mockups. Math.round(Float) returns Int — keep
            // the argument a Float so this does not resolve to the Double
            // overload, which returns Long.
            return Math.round(x.toFloat() + (y - x) * f).coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
