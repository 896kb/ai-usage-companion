package dev.kb896.aiusage.rear

/**
 * One animated value, eased from wherever it is to wherever it is told to go.
 *
 * Pure — no Android types — so the behaviour can be tested on the JVM. That is not
 * ceremony: this class replaced a damped spring and immediately shipped a bug that was
 * invisible on one of the two things it drives and permanent on the other. See
 * [RETARGET_EPSILON].
 *
 * Values arrive as steps — a poll completes and a bar jumps — which on an always-on
 * panel reads as a glitch rather than as news. Easing them makes a change something you
 * notice out of the corner of your eye, which is the entire job of a display nobody is
 * looking at directly.
 */
internal class Tween {

    /**
     * NaN until the first advance, so the panel opens on the real values rather than
     * animating up from zero every time the activity is re-placed — and respawns are
     * frequent here. Only genuine CHANGES animate.
     */
    var value = Float.NaN
        private set

    private var from = 0f
    private var to = Float.NaN
    private var startMs = 0L

    fun advance(target: Float, nowMs: Long): Float {
        if (value.isNaN()) { value = target; from = target; to = target; return value }

        // A NEW leg only for a meaningful change. Anything smaller moves the destination
        // without restarting the clock.
        //
        // Retargeting from `value` rather than from the old `from` is what lets a change
        // arriving mid-flight continue smoothly instead of snapping back to restart.
        if (kotlin.math.abs(target - to) > RETARGET_EPSILON) {
            from = value; to = target; startMs = nowMs
        } else {
            to = target
        }

        if (to == from) { value = to; return value }
        val t = ((nowMs - startMs).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        value = from + (to - from) * easeInOut(t)
        return value
    }

    fun settled(nowMs: Long): Boolean = to == from || nowMs - startMs >= DURATION_MS

    companion object {
        /** How long a value takes to travel from its old reading to its new one. */
        const val DURATION_MS = 1800f

        /**
         * Smallest target change that counts as a new movement, as a fraction of a
         * bar's full width.
         *
         * This constant is the whole reason this class is tested. The ring's target is
         * derived from System.currentTimeMillis(), so it changes by a hair on EVERY
         * frame — about 6e-8. With a bare `target != to` the leg restarted every frame,
         * startMs was always "now", t was always 0, and the ring stayed pinned at
         * whatever value it held when the panel started, forever.
         *
         * The bars never showed the bug, because a utilisation is constant between
         * payloads. The damped spring this class replaced never showed it either, since
         * an integrator chases a moving target without needing to know a leg had begun.
         * So it was invisible in every place anyone was looking.
         *
         * 0.2% — below what a 280px bar can render as even one pixel, and four orders of
         * magnitude above that per-frame jitter.
         */
        const val RETARGET_EPSILON = 0.002f

        /**
         * Cubic in-out. Starts slow, accelerates, decelerates into the target, and NEVER
         * passes it — which is why this replaced a spring. A spring overshoots by
         * design, and on a bounded quantity that means a bar heading for 94% punches
         * past 100%, clamps flat, and creeps back: a value the data never held.
         */
        fun easeInOut(t: Float): Float =
            if (t < 0.5f) 4f * t * t * t
            else 1f - (-2f * t + 2f).let { it * it * it } / 2f
    }
}
