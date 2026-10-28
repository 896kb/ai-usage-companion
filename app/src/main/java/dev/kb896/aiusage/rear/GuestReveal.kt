package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

/**
 * The way a guest comes onto the rear: a picture of what was there (our panel, or the
 * guest before it) covers the rear while the new app is moved in underneath and draws,
 * then slides off to the LEFT, fading at the end, and the app is revealed whole — never
 * seen half drawn, never popping in before the portal has gone. GuestCover is the layer;
 * this is when it moves, and how. Pure, one per launch, on the launch's own thread.
 *
 * COVERED until the guest has [drawn] on the rear, or [MAX_WAIT_MS] have gone by without
 * a sign of it (an app that never says it drew is revealed anyway); then SLIDING for
 * [SLIDE_MS]; then DONE, and the cover goes.
 *
 * THE CURVE IS THE SYSTEM'S TASK CLOSE, the one played when a guest closes and the panel
 * is revealed: WM Shell's DefaultTransitionHandler with framework-res's task_close_exit
 * (the -port/-land variants on OS4.0.5.0): the closing task moves 110 % of its size on an
 * accelerate-quint over 417 ms, and fades from 250 ms on an accelerate-quad to nothing at
 * 417 ms (on the phone the close transition ran 412 ms, start to finish). The direction is
 * not the resource's: its -port/-land variants move the closing task down, the base one
 * right. Here it goes left, as the user saw the close and asked for this.
 */
internal class GuestReveal(private val coveredAtMs: Long) {

    enum class Phase { COVERED, SLIDING, DONE }

    /** Where the cover stands: [dx] px across (negative is left), at [alpha]. */
    data class Frame(val dx: Float, val alpha: Float)

    var phase = Phase.COVERED
        private set
    private var slideFrom = 0L

    /** The guest has drawn on the rear: the slide begins now, once. */
    fun drawn(nowMs: Long) {
        if (phase == Phase.COVERED) { phase = Phase.SLIDING; slideFrom = nowMs }
    }

    /** Still covered and still worth waiting for the guest's first draw. */
    fun waiting(nowMs: Long): Boolean = phase == Phase.COVERED && nowMs - coveredAtMs < MAX_WAIT_MS

    /**
     * The cover at [nowMs], for a display [width] wide. Past [MAX_WAIT_MS] covered, the slide
     * is taken to have begun right then; past its end, DONE.
     */
    fun at(nowMs: Long, width: Float = Panel.WIDTH.toFloat()): Frame {
        if (phase == Phase.COVERED && !waiting(nowMs)) { phase = Phase.SLIDING; slideFrom = coveredAtMs + MAX_WAIT_MS }
        if (phase == Phase.COVERED) return Frame(0f, 1f)
        val elapsed = nowMs - slideFrom
        if (elapsed >= SLIDE_MS) phase = Phase.DONE
        return slide(elapsed, width)
    }

    companion object {
        /** The system close's translate. */
        const val SLIDE_MS = 417L
        /** Its fade begins this far in, and ends with the slide. */
        const val FADE_FROM_MS = 250L
        /** How far it goes, of the display's width: the close's 110 %, so the last of it leaves before the end. */
        const val TRAVEL = 1.1f
        /**
         * The longest the cover waits for the guest to draw. With [SLIDE_MS] it stays inside
         * the launch's two-second survive wait, so the frame (shown after the slide) is up
         * before the guest is confirmed.
         */
        const val MAX_WAIT_MS = 1_400L

        /** The cover [elapsedMs] into its slide, for a display [width] wide. */
        fun slide(elapsedMs: Long, width: Float): Frame {
            val t = (elapsedMs.toFloat() / SLIDE_MS).coerceIn(0f, 1f)
            // accelerate_quint: AccelerateInterpolator(2.5), t^5.
            val t2 = t * t
            val dx = -TRAVEL * width * t2 * t2 * t
            val f = ((elapsedMs - FADE_FROM_MS).toFloat() / (SLIDE_MS - FADE_FROM_MS)).coerceIn(0f, 1f)
            // accelerate_quad: AccelerateInterpolator(1), t^2.
            return Frame(dx, 1f - f * f)
        }
    }
}
