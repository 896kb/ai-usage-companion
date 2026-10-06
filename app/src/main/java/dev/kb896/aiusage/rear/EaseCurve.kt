package dev.kb896.aiusage.rear

import kotlin.math.roundToLong

/**
 * How fast a video background plays at a position in a run: a quarter speed at an eased
 * end, rising (or falling) to full along a smoothstep over that end's ramp — a stretch of
 * clip, in clip milliseconds, 0 for no ease. Ramps longer than the clip share it in
 * proportion, so the two always meet.
 */
object EaseCurve {
    const val SLOWEST = 0.25f

    fun speed(positionMs: Long, durationMs: Long, inRampMs: Long, outRampMs: Long): Float =
        speedAt(positionMs.toFloat(), durationMs, inRampMs, outRampMs)

    private fun ramps(durationMs: Long, inRampMs: Long, outRampMs: Long): Pair<Float, Float> {
        val i = inRampMs.coerceAtLeast(0).toFloat()
        val o = outRampMs.coerceAtLeast(0).toFloat()
        val sum = i + o
        return if (sum > durationMs && sum > 0f) (i * durationMs / sum) to (o * durationMs / sum) else i to o
    }

    private fun speedAt(positionMs: Float, durationMs: Long, inRampMs: Long, outRampMs: Long): Float {
        if (durationMs <= 0) return 1f
        val p = positionMs.coerceIn(0f, durationMs.toFloat())
        val (ri, ro) = ramps(durationMs, inRampMs, outRampMs)
        var s = 1f
        if (ri > 0f && p < ri) s = minOf(s, rise(p / ri))
        if (ro > 0f && durationMs - p < ro) s = minOf(s, rise((durationMs - p) / ro))
        return s
    }

    /**
     * When, in wall-clock milliseconds from the start of a run, the clip reaches
     * [positionMs]: the time each frame is due on screen. The sum of 1/speed — integrated a
     * millisecond at a time over the ramps only, since between them the clip and the wall
     * run together.
     */
    fun wallMs(positionMs: Long, durationMs: Long, inRampMs: Long, outRampMs: Long): Float {
        if (durationMs <= 0) return positionMs.toFloat()
        val p = positionMs.coerceIn(0, durationMs)
        val (ri, ro) = ramps(durationMs, inRampMs, outRampMs)
        val inEnd = ri.toLong()
        val outStart = durationMs - ro.toLong()
        fun slowed(from: Long, to: Long): Float {
            var wall = 0f
            var t = from
            // The middle of each millisecond, so a rising ramp and a falling one weigh the same.
            while (t < to) { wall += 1f / speedAt(t + 0.5f, durationMs, inRampMs, outRampMs); t++ }
            return wall
        }
        return slowed(0, minOf(p, inEnd)) +
            (p.coerceIn(inEnd, outStart) - inEnd) +
            slowed(outStart, maxOf(p, outStart))
    }

    /**
     * The stretch of clip whose slow-down lasts [wallMs] on screen. A ramp's clip plays
     * slower than the wall, by the same factor whatever its length, so this is a division.
     */
    fun clipRampFor(wallMs: Long): Long = if (wallMs <= 0) 0L else (wallMs / stretch).roundToLong()

    /** Wall time per clip millisecond across a ramp. */
    private val stretch: Float by lazy { wallMs(PROBE_MS, PROBE_MS * 1000, PROBE_MS, 0) / PROBE_MS }

    private const val PROBE_MS = 1000L

    private fun rise(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return SLOWEST + (1f - SLOWEST) * x * x * (3f - 2f * x)
    }
}
