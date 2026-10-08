package dev.kb896.aiusage.rear

/**
 * Vertical paging for the panel: drag freely, let go, land on a page.
 *
 * Built as a general mechanism rather than as a two-state toggle, because the reason it
 * exists is that this panel will eventually hold more accounts than fit on it. Pages are
 * a count, not an enum, and everything here is arithmetic on a span — adding a third page
 * later is a number, not a rewrite.
 *
 * Pure, and driven by an explicit clock, so the whole behaviour is JVM-testable. The
 * failure modes of a snapping scroller are all about sequences — a flick that should
 * carry to the next page, a drag that should fall back, a release mid-animation — and
 * none of them can be seen by inspecting a single frame on the back of a phone.
 *
 * The panel this drives is 904x572 with no scrollbar, no bounce and nothing to grab. So
 * the rules are deliberately forgiving: a short flick counts, and anything past a third
 * of the way counts, because the alternative is a surface that silently refuses to move
 * and gives no reason.
 */
internal class PanelPager(
    private val span: Float,
    private val settleMs: Float,
) {

    /** How many pages there are. One means paging is off and nothing here does anything. */
    var pages: Int = 1
        set(value) {
            field = value.coerceAtLeast(1)
            // A page count that shrinks under a scroll already past the new end would
            // leave the panel showing nothing at all. Happens for real: the media page
            // exists only while something is playing, and it can stop while you are
            // looking at it.
            val limit = (field - 1) * span
            if (offset > limit) {
                offset = limit
                from = limit
                to = limit
                settling = false
            }
        }

    /** Where the panel is, in units. 0 is the first page. */
    var offset: Float = 0f
        private set

    private var from = 0f
    private var to = 0f
    private var startMs = 0L

    /**
     * Whether a settle is in flight.
     *
     * An explicit flag rather than `startMs == 0L`, which is what this was and which is
     * wrong for a reason that would never have shown up on the device: zero is a
     * legitimate reading of an injected clock, so a page change at time zero was
     * indistinguishable from no page change at all and simply did not animate. Real
     * uptime is never zero, so only the tests could see it — which is what they are for.
     */
    private var settling = false

    private var downY = 0f
    private var downOffset = 0f
    private var lastY = 0f
    private var lastMs = 0L
    private var velocity = 0f

    /** True once a touch has moved far enough to be a drag rather than a tap. */
    var dragging = false
        private set

    /** The page nearest to where the panel currently sits. */
    val page: Int get() = Math.round(offset / span).coerceIn(0, pages - 1)

    /** Whether there is anywhere to scroll to. */
    fun enabled(): Boolean = pages > 1

    fun onDown(y: Float, nowMs: Long) {
        // Adopt wherever the animation had got to, so grabbing the panel mid-snap
        // continues from there instead of jumping back to where the drag last ended.
        offset = advance(nowMs)
        settling = false
        from = offset
        to = offset
        downY = y
        downOffset = offset
        lastY = y
        lastMs = nowMs
        velocity = 0f
        dragging = false
    }

    /** Returns true once this gesture counts as a drag. */
    fun onMove(y: Float, nowMs: Long): Boolean {
        if (!dragging && Math.abs(y - downY) < TOUCH_SLOP) return false
        dragging = true
        // Content follows the finger: dragging UP (y decreasing) moves further down the
        // pages, which is how every scrolling surface on the phone behaves.
        offset = (downOffset + (downY - y)).coerceIn(0f, (pages - 1) * span)
        val dt = nowMs - lastMs
        if (dt > 0) {
            // Units per millisecond, smoothed a little. A raw last-two-points velocity on
            // a 120Hz digitizer is mostly noise, and a finger lifting often reports one
            // final stationary sample that would read as a dead stop.
            val instant = (lastY - y) / dt
            velocity = velocity * (1f - VELOCITY_BLEND) + instant * VELOCITY_BLEND
        }
        lastY = y
        lastMs = nowMs
        return true
    }

    /** Let go: pick a page and start moving to it. */
    fun onUp(nowMs: Long) {
        if (!dragging) return
        dragging = false
        // A finger that stopped before it lifted is not moving any more, whatever its last
        // samples said.
        if (nowMs - lastMs > STALE_MS) velocity = 0f
        val at = offset / span
        val settled = Math.round(downOffset / span)
        // A flick decides on its own, whatever distance it covered. This is what makes a
        // short deliberate swipe work on a surface with no room for a long one.
        val target = when {
            velocity > FLING_SPEED -> settled + 1
            velocity < -FLING_SPEED -> settled - 1
            // Otherwise, whichever page the panel is closest to — with the threshold at a
            // third rather than a half, so a hesitant drag still commits.
            at - settled > COMMIT -> settled + 1
            settled - at > COMMIT -> settled - 1
            else -> settled
        }
        val dest = target.coerceIn(0, pages - 1).toFloat() * span
        moveTo(dest, nowMs)
        // CARRYING ON, not starting over: heading where the finger was already going, the
        // settle leaves at the finger's speed and slows into place (see advance).
        val toward = if (dest > offset) velocity else -velocity
        carry = if (toward > 0f) toward else 0f
    }

    /** Go to a page directly, with the same easing a release uses. */
    fun goTo(page: Int, nowMs: Long, overMs: Float = 0f) {
        moveTo(page.coerceIn(0, pages - 1).toFloat() * span, nowMs, overMs)
    }

    /**
     * Head for a page, and keep heading there.
     *
     * IDEMPOTENT, which [goTo] is not, and that is the whole reason it exists. This is
     * called from the draw loop — when the media page stops being available the panel has
     * to walk itself home — and goTo on every frame would reset the start of the
     * animation sixty times a second, so the page would creep instead of travelling.
     */
    fun settleTo(page: Int, nowMs: Long) {
        val target = page.coerceIn(0, pages - 1).toFloat() * span
        if (settling && to == target) return
        if (!settling && offset == target) return
        moveTo(target, nowMs)
    }

    /** Whether the panel is where it has been asked to be, with nothing left to run. */
    fun restingAt(page: Int): Boolean =
        !settling && !dragging && offset == page.coerceIn(0, pages - 1).toFloat() * span

    /** A fixed length for the move under way, 0 for the usual per-page pace. */
    private var overMs = 0f

    /** The finger's speed toward the target when it let go, units a millisecond; 0 for none. */
    private var carry = 0f

    private fun moveTo(target: Float, nowMs: Long, fixedMs: Float = 0f) {
        carry = 0f
        from = offset
        to = target
        startMs = nowMs
        overMs = fixedMs
        settling = target != offset
    }

    /**
     * Where the panel is this frame, easing toward its target.
     *
     * The duration shrinks with the distance left, so a release a hair from the edge
     * lands immediately rather than taking the full time to travel nothing — the same
     * rule the ambient ripple follows.
     */
    fun advance(nowMs: Long): Float {
        if (dragging || !settling) return offset
        val distance = Math.abs(to - from) / span
        // Carried on from a finger: eased OUT, which starts at twice its average speed, so
        // a duration of twice the distance over the finger's speed starts it at that speed.
        val carried = carry > 0f && overMs <= 0f
        val duration = when {
            overMs > 0f -> overMs
            carried -> (2f * Math.abs(to - from) / carry).coerceIn(MIN_SETTLE_MS, maxOf(MIN_SETTLE_MS, settleMs * distance))
            else -> (settleMs * distance).coerceAtLeast(MIN_SETTLE_MS)
        }
        val t = (nowMs - startMs) / duration
        if (t >= 1f) {
            offset = to
            settling = false
            return offset
        }
        val u = t.coerceIn(0f, 1f)
        offset = from + (to - from) * (if (carried) 1f - (1f - u) * (1f - u) else Tween.easeInOut(u))
        return offset
    }

    /** Whether anything is still moving, so the caller knows to keep redrawing. */
    fun animating(): Boolean = settling || dragging

    private companion object {
        /** How far a finger must travel before it stops being a tap. A panel pressed
         *  without looking is never held still. */
        const val TOUCH_SLOP = 18f

        /** Units per millisecond that counts as a flick. */
        const val FLING_SPEED = 0.35f

        /** How far through a page a drag must get to commit, as a fraction. */
        const val COMMIT = 0.33f

        const val VELOCITY_BLEND = 0.4f
        /** A finger still this long before lifting has stopped. */
        const val STALE_MS = 80L
        const val MIN_SETTLE_MS = 90f
    }
}
