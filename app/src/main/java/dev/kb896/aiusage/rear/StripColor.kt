package dev.kb896.aiusage.rear

/**
 * The colour of the camera strip (x < Panel.CUTOUT_RIGHT) beside a guest kept right of the
 * cameras: the app's own background, read off its leftmost columns, so the strip reads as
 * part of it — or black where there is no one colour to take. Pure; CameraStrip shows it.
 *
 * [pick]: per channel, the median of the sampled pixels; the edge is uniform enough when
 * every channel's median absolute deviation is within [MAD_LIMIT] — text or an icon
 * touching the edge is outvoted, a photo or a gradient is not, and gives black.
 */
object StripColor {

    /** Over this, per channel (0–255), the edge is busy and the strip goes black. */
    const val MAD_LIMIT = 8
    /** A new colour eases in over this long. */
    const val EASE_MS = 200L

    private const val BLACK = 0xFF000000.toInt()

    /** The strip's colour for [pixels] (ARGB, alpha ignored): opaque, or black. */
    fun pick(pixels: IntArray): Int {
        if (pixels.isEmpty()) return BLACK
        var out = BLACK
        for (shift in intArrayOf(16, 8, 0)) {
            val ch = IntArray(pixels.size) { (pixels[it] shr shift) and 0xFF }
            val med = median(ch)
            val mad = median(IntArray(ch.size) { kotlin.math.abs(ch[it] - med) })
            if (mad > MAD_LIMIT) return BLACK
            out = out or (med shl shift)
        }
        return out
    }

    private fun median(v: IntArray): Int { v.sort(); return v[v.size / 2] }

    /** The strip's colour on its way to the latest pick: eased over [EASE_MS], the first taken as it is. */
    class Ease {
        private var from = 0
        private var to = 0
        private var startMs = 0L
        private var seeded = false

        fun at(target: Int, nowMs: Long): Int {
            if (!seeded) { seeded = true; from = target; to = target; startMs = nowMs - EASE_MS; return target }
            val now = value(nowMs)
            if (target != to) { from = now; to = target; startMs = nowMs }
            return value(nowMs)
        }

        fun settled(nowMs: Long): Boolean = nowMs - startMs >= EASE_MS

        private fun value(nowMs: Long): Int {
            val t = ((nowMs - startMs).toFloat() / EASE_MS).coerceIn(0f, 1f)
            return if (t >= 1f) to else RampColor.lerp(from, to, Tween.easeInOut(t))
        }
    }
}
