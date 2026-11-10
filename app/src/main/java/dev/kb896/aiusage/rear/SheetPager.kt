package dev.kb896.aiusage.rear

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * How far along the portal sheet's grid of apps is scrolled, and where it is going: a
 * sideways drag on the grid moves the apps with the finger, and let go they glide on at the
 * finger's speed and slow to a stop wherever that is. Pure, as SheetMotion; the service
 * drives it under the sheet's lock.
 *
 * NO PAGES TO STOP ON (the owner, 2026-11-10: "not having breakpoints but rather scroll
 * horizontally, with a smooth scroll"). It was a page at a time: let go, it turned one page or
 * went back. The apps are still laid out a page after a page, each [stride] pixels on from
 * the last (PortalSheet.Layout.pageStride — a page's own width, so the columns run on evenly
 * from one to the next), and [position] is still counted in them: 1.25 is a quarter of the way
 * from the second page to the third. But it rests anywhere between the first and the last.
 *
 * A finger moving left brings the next apps in. Past the first or the last page the apps
 * follow the finger only a third of the way, and come back.
 */
class SheetPager(stride: Float) {

    /** From one page to the next, in pixels. */
    var stride = stride.coerceAtLeast(1f)
        set(value) { field = value.coerceAtLeast(1f) }

    /** How many pages there are; where it stands is held within them. */
    var pages = 1
        set(value) {
            field = value.coerceAtLeast(1)
            if (target > field - 1) { target = (field - 1).toFloat(); rest() }
        }

    /** Where it rests, or is going, in pages. */
    private var target = 0f

    /** The page nearest where it rests or is going. */
    val page: Int get() = target.roundToInt().coerceIn(0, pages - 1)

    /** Moving on its own: [at] moves it on. */
    var settling = false
        private set

    private var held = false
    private var base = 0f
    private var downX = 0f
    private var dx = 0f
    private var from = 0f
    private var startMs = 0L
    /** A glide slowing from the finger's speed, or (0) an eased move of [settleMs]. */
    private var gliding = false
    private var settleMs = 0L
    private val speed = FingerSpeed()

    /** Where the apps stand at [nowMs], in pages. */
    fun position(nowMs: Long): Float = when {
        held -> band(base - dx / stride)
        settling && gliding -> target + (from - target) * exp(-(nowMs - startMs).coerceAtLeast(0L) / GLIDE_MS)
        settling -> {
            val t = if (settleMs <= 0L) 1f else ((nowMs - startMs) / settleMs.toFloat()).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t)
            from + (target - from) * e
        }
        else -> target
    }

    /** [position], and a move that has arrived comes to rest. */
    fun at(nowMs: Long): Float {
        val p = position(nowMs)
        if (settling) {
            val there = if (gliding) abs(p - target) * stride < REST_PX else nowMs - startMs >= settleMs
            if (there) { rest(); return target }
        }
        return p
    }

    /** A finger lands at [x] to drag the apps: they stop where they are. */
    fun grab(x: Float, atMs: Long) {
        base = position(atMs)
        settling = false
        held = true
        downX = x
        dx = 0f
        speed.start(x, atMs)
    }

    /** The finger at [x]: where the apps stand now. */
    fun drag(x: Float, atMs: Long): Float {
        if (!held) return position(atMs)
        speed.move(x, atMs)
        dx = x - downX
        return position(atMs)
    }

    /**
     * Let go at [x]: on at the finger's speed, slowing, to wherever that comes to — no further
     * than the first or the last page; from past either end, back to it. The page nearest where
     * it will rest. Not held, the page it is on.
     */
    fun release(x: Float, atMs: Long): Int {
        if (!held) return page
        dx = x - downX
        val p = position(atMs)
        val v = speed.at(atMs)
        held = false
        val last = (pages - 1).toFloat()
        from = p
        startMs = atMs
        if (p < 0f || p > last) {
            // Past an end: back to it, eased.
            target = p.coerceIn(0f, last)
            gliding = false
            settleMs = ControlCenter.settleMs(abs(target - p) * stride, 0f)
        } else {
            // A speed dying away over GLIDE_MS covers that speed times GLIDE_MS.
            target = (p - v * GLIDE_MS / stride).coerceIn(0f, last)
            gliding = true
        }
        if (abs(target - p) * stride < REST_PX) rest() else settling = true
        return page
    }

    /** To page [to] from where it stands (a tap on its dot), eased. */
    fun show(to: Int, nowMs: Long) {
        val p = position(nowMs)
        held = false
        target = to.coerceIn(0, pages - 1).toFloat()
        from = p
        if (abs(target - p) < 0.001f) { rest(); return }
        gliding = false
        settleMs = ControlCenter.settleMs(abs(target - p) * stride, 0f)
        startMs = nowMs
        settling = true
    }

    /** Stopped where it stands at [nowMs] (a tap on a glide), within the pages: where that is. */
    fun stop(nowMs: Long): Float {
        target = position(nowMs).coerceIn(0f, (pages - 1).toFloat())
        held = false
        rest()
        return target
    }

    /** On the first page, at rest: the sheet has shut. */
    fun reset() {
        target = 0f
        held = false
        rest()
    }

    private fun rest() {
        settling = false
        from = target
    }

    /** Past either end the apps give a third as much. */
    private fun band(p: Float): Float {
        val last = (pages - 1).toFloat()
        return when {
            p < 0f -> p / 3f
            p > last -> last + (p - last) / 3f
            else -> p
        }
    }

    companion object {
        /** How long a glide's speed takes to die away (to 1/e), in ms: a flick at speed v carries v × this. */
        const val GLIDE_MS = 325f
        /** Within this of where it is going, in pixels, a glide has arrived. */
        const val REST_PX = 0.5f
    }
}
