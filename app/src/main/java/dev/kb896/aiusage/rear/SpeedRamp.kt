package dev.kb896.aiusage.rear

/**
 * A speed that eases between moving (1) and still (0) over [RAMP_MS] rather than jumping:
 * the silk slowing to a stop as the panel goes into always-on, the double tap's dim or
 * sleep, and picking up again when it comes back. Asked to turn part-way, it turns from
 * where it had got to.
 */
class SpeedRamp {
    private var from = 1f
    private var to = 1f
    private var start = 0L

    fun at(now: Long): Float {
        val t = ((now - start).toFloat() / RAMP_MS).coerceIn(0f, 1f)
        return from + (to - from) * t * t * (3f - 2f * t)
    }

    fun toward(target: Float, now: Long) {
        if (target == to) return
        from = at(now)
        to = target
        start = now
    }

    /** Still at once — the display went dark, so there is nothing to see slow down. */
    fun stopNow() {
        from = 0f
        to = 0f
    }

    /** Still moving towards where it was asked to go. */
    fun turning(now: Long): Boolean = now - start < RAMP_MS && from != to

    companion object {
        const val RAMP_MS = 1200L
    }
}
