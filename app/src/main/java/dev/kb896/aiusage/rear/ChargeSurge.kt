package dev.kb896.aiusage.rear

/**
 * Surges inside the liquid: the cell lit from within, the way a cloud is by the discharge
 * inside it rather than by anything you can see.
 *
 * The point is that the SOURCE STAYS HIDDEN. A drawn bolt is a bolt on top of a picture
 * of water; a bright patch under the surface, with its edges lost in the body, is the
 * water having something happen inside it. Clipping every flash to the liquid is what
 * makes the difference — the glow stops exactly where the liquid does, so it reads as
 * lighting the stuff rather than as being drawn over it.
 *
 * Deliberately irregular. Charge does not surge on a beat, and anything periodic here
 * would read as a loading animation — the one thing this must not look like, since the
 * cell is showing a real reading.
 */
class ChargeSurge(seed: Int = 1) {

    /**
     * One discharge: where it sits in the liquid, when it began and how long it lives.
     *
     * [u] runs across the liquid and [v] into its depth, both 0..1, so a flash is placed
     * relative to the body rather than to the cell — which matters because the body moves
     * and turns, and a flash pinned to the glass would swim through the liquid as it did.
     */
    class Flash(
        val u: Float,
        val v: Float,
        val startMs: Float,
        val lifeMs: Float,
        val size: Float,
    )

    private val flashes = ArrayList<Flash>(8)
    private var next = 0f
    private var rand = seed

    private fun rnd(): Float {
        rand = rand * 1_103_515_245 + 12_345
        return ((rand ushr 16) and 0x7FFF) / 32_767f
    }

    val size: Int get() = flashes.size

    fun forEach(action: (Flash) -> Unit) = flashes.forEach(action)

    fun clear() {
        flashes.clear()
        next = 0f
    }

    /**
     * Retire what has burnt out, and start new ones when due.
     *
     * [depth] is how full the cell is, 0..1, and flashes are placed within it — an empty
     * cell has nothing to light and a nearly full one can be lit anywhere. Without that,
     * an early surge is a glow hanging in the air above a puddle.
     */
    fun update(nowMs: Float, depth: Float) {
        flashes.removeAll { nowMs - it.startMs > it.lifeMs }
        if (depth <= 0.02f) return
        if (nowMs < next) return
        // The gap to the next one, drawn fresh each time. A fixed interval reads as a
        // metronome no matter how short it is.
        next = nowMs + GAP_MIN + rnd() * (GAP_MAX - GAP_MIN)
        if (flashes.size >= MAX_FLASHES) return
        flashes.add(
            Flash(
                u = 0.12f + rnd() * 0.76f,
                // Biased AWAY from the surface. A discharge right at the top lights the
                // meniscus and looks like a reflection; deeper down, the body carries the
                // glow and the surface only catches the edge of it.
                v = 0.25f + rnd() * 0.6f,
                startMs = nowMs,
                lifeMs = LIFE_MIN + rnd() * (LIFE_MAX - LIFE_MIN),
                size = SIZE_MIN + rnd() * (SIZE_MAX - SIZE_MIN),
            ),
        )
    }

    /**
     * How bright a flash is now, 0..1.
     *
     * Not a smooth fade. Real lightning strobes — the channel re-ionises several times in
     * a fraction of a second — and that stutter is most of what makes a flash read as a
     * discharge rather than as a light being turned up. So a decaying envelope carries a
     * few hard pulses, and the whole thing is gone inside a quarter of a second.
     */
    fun brightness(flash: Flash, nowMs: Float): Float {
        val t = ((nowMs - flash.startMs) / flash.lifeMs).coerceIn(0f, 1f)
        val decay = (1f - t) * (1f - t)
        val strobe = Math.abs(Math.sin((t * Math.PI * PULSES).toDouble())).toFloat()
        return decay * (FLOOR + (1f - FLOOR) * strobe)
    }

    companion object {
        /** How many discharges may be alight at once. */
        const val MAX_FLASHES = 4

        /** The gap between them, in milliseconds. Drawn fresh each time, never fixed. */
        const val GAP_MIN = 140f
        const val GAP_MAX = 620f

        /** How long one lasts. Short: a surge, not a lamp. */
        const val LIFE_MIN = 130f
        const val LIFE_MAX = 260f

        /** How wide the glow is, as a fraction of the cell's width. */
        const val SIZE_MIN = 0.9f
        const val SIZE_MAX = 2.1f

        /** How many times the channel re-strikes over its life. */
        const val PULSES = 3.5f

        /** What the envelope keeps between strikes, so it flickers rather than blinking. */
        const val FLOOR = 0.35f
    }
}
