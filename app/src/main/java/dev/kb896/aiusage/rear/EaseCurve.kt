package dev.kb896.aiusage.rear

/**
 * How fast a non-looping video background plays at a given position: a quarter speed at
 * an eased end, rising (or falling) to full over [RAMP_MS] along a smoothstep. A clip
 * shorter than two ramps gives each half of itself, so the two always meet at full speed.
 */
object EaseCurve {
    const val SLOWEST = 0.25f
    const val RAMP_MS = 1000L

    fun speed(positionMs: Long, durationMs: Long, easeIn: Boolean, easeOut: Boolean): Float {
        if (durationMs <= 0) return 1f
        val p = positionMs.coerceIn(0, durationMs)
        val ramp = minOf(RAMP_MS, durationMs / 2).coerceAtLeast(1)
        var s = 1f
        if (easeIn && p < ramp) s = minOf(s, rise(p.toFloat() / ramp))
        if (easeOut && durationMs - p < ramp) s = minOf(s, rise((durationMs - p).toFloat() / ramp))
        return s
    }

    private fun rise(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return SLOWEST + (1f - SLOWEST) * x * x * (3f - 2f * x)
    }
}
