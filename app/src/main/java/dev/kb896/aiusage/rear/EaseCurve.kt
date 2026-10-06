package dev.kb896.aiusage.rear

/**
 * How fast a non-looping video background plays at a given position: a quarter speed at
 * an eased end, rising (or falling) to full over [RAMP_MS] along a smoothstep. A clip
 * shorter than two ramps gives each half of itself, so the two always meet at full speed.
 */
object EaseCurve {
    const val SLOWEST = 0.25f
    const val RAMP_MS = 1000L

    fun speed(positionMs: Long, durationMs: Long, easeIn: Boolean, easeOut: Boolean): Float =
        speedAt(positionMs.toFloat(), durationMs, easeIn, easeOut)

    private fun speedAt(positionMs: Float, durationMs: Long, easeIn: Boolean, easeOut: Boolean): Float {
        if (durationMs <= 0) return 1f
        val p = positionMs.coerceIn(0f, durationMs.toFloat())
        val ramp = minOf(RAMP_MS, durationMs / 2).coerceAtLeast(1).toFloat()
        var s = 1f
        if (easeIn && p < ramp) s = minOf(s, rise(p / ramp))
        if (easeOut && durationMs - p < ramp) s = minOf(s, rise((durationMs - p) / ramp))
        return s
    }

    /**
     * When, in wall-clock milliseconds from the start of a run, the clip reaches
     * [positionMs]: the time each frame is due on screen. The sum of 1/speed — integrated a
     * millisecond at a time over the ramps only, since between them the clip and the wall
     * run together.
     */
    fun wallMs(positionMs: Long, durationMs: Long, easeIn: Boolean, easeOut: Boolean): Float {
        if (durationMs <= 0) return positionMs.toFloat()
        val p = positionMs.coerceIn(0, durationMs)
        val ramp = minOf(RAMP_MS, durationMs / 2).coerceAtLeast(1)
        val inEnd = if (easeIn) ramp else 0L
        val outStart = if (easeOut) durationMs - ramp else durationMs
        fun slowed(from: Long, to: Long): Float {
            var wall = 0f
            var t = from
            // The middle of each millisecond, so a rising ramp and a falling one weigh the same.
            while (t < to) { wall += 1f / speedAt(t + 0.5f, durationMs, easeIn, easeOut); t++ }
            return wall
        }
        return slowed(0, minOf(p, inEnd)) +
            (p.coerceIn(inEnd, outStart) - inEnd) +
            slowed(outStart, maxOf(p, outStart))
    }

    private fun rise(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return SLOWEST + (1f - SLOWEST) * x * x * (3f - 2f * x)
    }
}
