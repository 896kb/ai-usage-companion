package dev.kb896.aiusage.rear


/**
 * The way a guest comes onto the rear: a picture of what was there (our panel, or the
 * guest before it) covers the rear while the new app is moved in underneath and draws,
 * then fades away where it stands, and the app is revealed whole — never
 * seen half drawn, never popping in before the portal has gone. GuestCover is the layer;
 * this is when it moves, and how. Pure, one per launch, on the launch's own thread.
 *
 * COVERED until the guest has [drawn] on the rear, or [MAX_WAIT_MS] have gone by without
 * a sign of it (an app that never says it drew is revealed anyway); then SLIDING for
 * [SLIDE_MS]; then DONE, and the cover goes.
 *
 * IT DOES NOT SLIDE ANY MORE: IT FADES WHERE IT STANDS (the owner, 2026-11-10: "why are we
 * still sliding instead of fading?"). It went off to the left on the system's task-close
 * curve, as the owner first asked; now the rear is blurred and dimmed over the whole change
 * (GuestCurtain, up before the cover is and clear only after it has gone), and under that
 * blur the cover simply fades out over [SLIDE_MS] to the new app — nothing moves across.
 * The names say "slide" still: when it goes, not how.
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
    fun at(nowMs: Long, width: Float): Frame {
        if (phase == Phase.COVERED && !waiting(nowMs)) { phase = Phase.SLIDING; slideFrom = coveredAtMs + MAX_WAIT_MS }
        if (phase == Phase.COVERED) return Frame(0f, 1f)
        val elapsed = nowMs - slideFrom
        if (elapsed >= SLIDE_MS) phase = Phase.DONE
        return slide(elapsed, width)
    }

    companion object {
        /** How long the cover takes to fade away. */
        const val SLIDE_MS = 240L
        /**
         * The longest the cover waits for the guest to draw. With [SLIDE_MS] it stays inside
         * the launch's two-second survive wait, so the frame (shown after the cover has gone)
         * is up before the guest is confirmed.
         */
        const val MAX_WAIT_MS = 1_400L

        /** The cover [elapsedMs] into its going, for a display [width] wide: where it was, fading, eased. */
        @Suppress("UNUSED_PARAMETER")
        fun slide(elapsedMs: Long, width: Float): Frame {
            val t = (elapsedMs.toFloat() / SLIDE_MS).coerceIn(0f, 1f)
            return Frame(0f, 1f - t * t * (3f - 2f * t))
        }
    }
}
