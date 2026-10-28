package dev.kb896.aiusage.rear

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Which page of the portal sheet's grid shows, and where it is going: a sideways drag on
 * the grid moves the pages with the finger, and let go they settle on a page as the sheet
 * does. Pure, as SheetMotion; the service drives it under the sheet's lock.
 *
 * [position] is in pages: 1.25 is a quarter of the way from the second page to the third.
 * A finger moving left brings the next page in. Past the first or the last page the pages
 * follow the finger only a third of the way, and come back.
 *
 * Let go, it turns a page if the drag went [SWIPE] of a page (PortalSheet.PAGE_STRIDE
 * pixels a page) or was a flick (ControlCenter.FLING), else it goes back — one page at a
 * time either way, from the page it was grabbed on.
 */
class SheetPager {

    /** How many pages there are; the page it is on is held within them. */
    var pages = 1
        set(value) {
            field = value.coerceAtLeast(1)
            if (page >= field) { page = field - 1; rest() }
        }

    /** The page it is on, or settling to. */
    var page = 0
        private set

    /** Settling: [at] moves it on. */
    var settling = false
        private set

    private var held = false
    private var base = 0f
    private var grabbedPage = 0
    private var downX = 0f
    private var dx = 0f
    private var from = 0f
    private var startMs = 0L
    private var settleMs = 0L
    private val speed = FingerSpeed()

    /** Where the pages stand at [nowMs], in pages. */
    fun position(nowMs: Long): Float = when {
        held -> band(base - dx / PortalSheet.PAGE_STRIDE)
        settling -> {
            val t = if (settleMs <= 0L) 1f else ((nowMs - startMs) / settleMs.toFloat()).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t)
            from + (page - from) * e
        }
        else -> page.toFloat()
    }

    /** [position], and a settle that has arrived comes to rest. */
    fun at(nowMs: Long): Float {
        val p = position(nowMs)
        if (settling && nowMs - startMs >= settleMs) rest()
        return p
    }

    /** A finger lands at [x] to drag the pages: they stop where they are. */
    fun grab(x: Float, atMs: Long) {
        base = at(atMs)
        grabbedPage = base.roundToInt().coerceIn(0, pages - 1)
        settling = false
        held = true
        downX = x
        dx = 0f
        speed.start(x, atMs)
    }

    /** The finger at [x]: where the pages stand now. */
    fun drag(x: Float, atMs: Long): Float {
        if (!held) return position(atMs)
        speed.move(x, atMs)
        dx = x - downX
        return position(atMs)
    }

    /** Let go at [x]: the page it settles on. Not held, the page it is on. */
    fun release(x: Float, atMs: Long): Int {
        if (!held) return page
        dx = x - downX
        val p = position(atMs)
        val v = speed.at(atMs)
        held = false
        val moved = p - grabbedPage
        val to = when {
            v < -ControlCenter.FLING -> grabbedPage + 1
            v > ControlCenter.FLING -> grabbedPage - 1
            moved > SWIPE -> grabbedPage + 1
            moved < -SWIPE -> grabbedPage - 1
            else -> grabbedPage
        }.coerceIn(0, pages - 1)
        go(to, atMs, p, abs(v))
        return to
    }

    /** To page [to] from where it stands (a tap on its dot), settling. */
    fun show(to: Int, nowMs: Long) {
        held = false
        go(to.coerceIn(0, pages - 1), nowMs, position(nowMs), 0f)
    }

    /** On the first page, at rest: the sheet has shut. */
    fun reset() {
        page = 0
        held = false
        rest()
    }

    private fun go(to: Int, nowMs: Long, at: Float, speedPx: Float) {
        page = to
        from = at
        if (abs(to - at) < 0.001f) { rest(); return }
        settleMs = ControlCenter.settleMs(abs(to - at) * PortalSheet.PAGE_STRIDE, speedPx)
        startMs = nowMs
        settling = true
    }

    private fun rest() {
        settling = false
        from = page.toFloat()
    }

    /** Past either end the pages give a third as much. */
    private fun band(p: Float): Float {
        val last = (pages - 1).toFloat()
        return when {
            p < 0f -> p / 3f
            p > last -> last + (p - last) / 3f
            else -> p
        }
    }

    companion object {
        /** A drag of this much of a page turns it. */
        const val SWIPE = 0.2f
    }
}
