package dev.kb896.aiusage.rear

import kotlin.math.abs

/**
 * How high the shell's portal sheet stands, and where it is going: kept apart from the
 * drawing so the shell service can drive ShellSheet from the raw touchscreen and the
 * rules can be tested. Heights in panel pixels, 0 shut to PortalSheet.FULL; the sheet's
 * top edge follows the finger one for one.
 *
 * It rests at one of its [detents] (PortalSheet.detents: shut, the strip alone over a guest
 * on top, all of it):
 * - SHUT at 0, OPEN at any other detent ([target] says which).
 * - HELD: a finger has it. [hold] stops it where it was (a settle caught mid-way, with
 *   [target] remembering where it was going); [grab] then lets that finger drag it, from
 *   where it was — never a jump — so [drag] reads `base - dy`, up raising it.
 * - SETTLING: let go, it goes to the detent [snap] picks, eased out as the control
 *   centre's settle is, over ControlCenter.settleMs from the finger's speed. [at] advances
 *   it and puts it at rest on arrival.
 *
 * Times are uptime milliseconds, as RearTouches stamps its events. Not thread-safe: the
 * service drives it under one lock.
 */
class SheetMotion {

    enum class Phase { SHUT, HELD, SETTLING, OPEN }

    var phase = Phase.SHUT
        private set
    /** The detent it is going to (settling), was going to (held), or is at (at rest). */
    var target = 0f
        private set
    /** Up, or going up: anywhere but shut. */
    val heading: Boolean get() = target > 0f
    /** The current settle's length, for the curious. */
    var settleMs = 0L
        private set

    /** Where it may rest, lowest first, always from 0; see [setDetents]. */
    var detents: List<Float> = listOf(0f, PortalSheet.FULL)
        private set

    private var from = 0f
    private var startMs = 0L
    private var base = 0f
    private var dragging = false
    private var downY = 0f
    private var dy = 0f
    private val speed = FingerSpeed()

    /** How high at [nowMs], without moving a settle on. */
    fun height(nowMs: Long): Float = when (phase) {
        Phase.SHUT -> 0f
        Phase.OPEN -> target
        Phase.HELD -> if (dragging) (base - dy).coerceIn(0f, top) else base
        Phase.SETTLING -> {
            val t = if (settleMs <= 0L) 1f else ((nowMs - startMs) / settleMs.toFloat()).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t)
            from + (target - from) * e
        }
    }

    private val top get() = detents.last()

    /** [height], and a settle that has arrived comes to rest. */
    fun at(nowMs: Long): Float {
        val h = height(nowMs)
        if (phase == Phase.SETTLING && nowMs - startMs >= settleMs) rest(target)
        return h
    }

    /**
     * The detent above 0 it stands still at — at rest, or held there without a drag — or
     * null while it moves, is dragged, or is shut: the sheet's boxes are where
     * PortalSheet.hit says only then.
     */
    fun settled(nowMs: Long): Float? = when (phase) {
        Phase.OPEN -> target
        Phase.HELD -> if (dragging) null else base.let { b -> detents.firstOrNull { it > 0f && abs(it - b) < SETTLED_PX } }
        else -> null
    }

    /**
     * New stops (the guest came or went while it was up). A target no longer among them
     * gives way to the nearest one that is: a sheet resting on a strip that has gone shuts.
     */
    fun setDetents(stops: List<Float>, nowMs: Long) {
        if (stops == detents) return
        detents = stops
        if (target !in stops) {
            val to = stops.minBy { abs(it - target) }
            when (phase) {
                Phase.HELD -> target = to
                else -> settle(to, nowMs)
            }
        }
    }

    /** A finger on it: it stops where it is. */
    fun hold(nowMs: Long) {
        val h = at(nowMs)
        if (phase == Phase.SHUT) target = 0f
        base = h
        dragging = false
        phase = Phase.HELD
    }

    /** That finger drags it from here, having landed at [downY]. */
    fun grab(downY: Float, atMs: Long) {
        hold(atMs)
        this.downY = downY
        speed.start(downY, atMs)
        dy = 0f
        dragging = true
    }

    /** The finger at [y]: how high it is now. */
    fun drag(y: Float, atMs: Long): Float {
        if (phase != Phase.HELD || !dragging) return height(atMs)
        speed.move(y, atMs)
        dy = y - downY
        return height(atMs)
    }

    /**
     * Let go at [y]: off to the detent [snap] picks for where it is and how fast it was
     * going, settling there at the finger's speed. The detent it goes to. Not dragging
     * (held only), it carries on where it was going.
     */
    fun release(y: Float, atMs: Long): Float {
        if (phase != Phase.HELD) return target
        if (!dragging) { resume(atMs); return target }
        val v = speed.at(atMs)
        dy = y - downY
        val h = height(atMs)
        val to = snap(detents, h, v)
        settle(to, atMs, v)
        return to
    }

    /** Held but not dragged (a tap mid-settle, a drag that moved something else): on to where it was going. */
    fun resume(nowMs: Long) = settle(target, nowMs)

    /**
     * Go to [to] from where it is at [nowMs], carrying on at [velocity] (panel units a ms,
     * down positive) if it was moving that way. Already there: at rest at once.
     */
    fun settle(to: Float, nowMs: Long, velocity: Float = 0f) {
        from = height(nowMs)
        target = to
        dragging = false
        if (abs(to - from) < 0.5f) { rest(to); return }
        val toward = if (to > from) -velocity else velocity
        settleMs = ControlCenter.settleMs(abs(to - from), toward)
        startMs = nowMs
        phase = Phase.SETTLING
    }

    /** Shut, now: nothing moving, nothing held. */
    fun reset() = rest(0f)

    private fun rest(at: Float) {
        phase = if (at > 0f) Phase.OPEN else Phase.SHUT
        target = at
        dragging = false
        base = at
        dy = 0f
    }

    companion object {
        /** Held within this of a detent, it counts as resting there. */
        const val SETTLED_PX = 1f
        /** A finger still this long before lifting has no speed. */
        const val STILL_MS = FingerSpeed.STILL_MS
        /** How far ahead a let-go sheet is projected at the finger's speed, to pick its stop. */
        const val PROJECT_MS = 250f

        /**
         * Where a sheet let go at height [h] at [velocity] (panel units a ms, down positive)
         * comes to rest among [detents]: the one nearest to where it would be [PROJECT_MS]
         * on at that speed — slow, simply the nearest; a fast fling can go all the way, shut
         * to full or full to shut. A flick (past ControlCenter.FLING) always gets at least
         * to the next stop the way it was flicked.
         */
        fun snap(detents: List<Float>, h: Float, velocity: Float): Float {
            val up = -velocity
            var to = detents.minBy { abs(it - (h + up * PROJECT_MS)) }
            if (up > ControlCenter.FLING && to <= h) to = detents.firstOrNull { it > h + 0.5f } ?: detents.last()
            if (up < -ControlCenter.FLING && to >= h) to = detents.lastOrNull { it < h - 0.5f } ?: detents.first()
            return to
        }
    }
}

/**
 * A finger's speed along one axis, in panel units a millisecond, as the panel reads it:
 * the newest step counts most, so a flick is read at its end; a finger still for
 * [STILL_MS] before lifting has none.
 */
class FingerSpeed {
    private var last = 0f
    private var lastAt = 0L
    private var v = 0f

    fun start(at: Float, atMs: Long) { last = at; lastAt = atMs; v = 0f }

    fun move(at: Float, atMs: Long) {
        // A step that took no time says nothing about speed — taken as 1 ms, a finger that
        // had moved 20 px since it landed read as a flick. Its distance counts in the next
        // step that does take time.
        if (atMs <= lastAt) return
        v = 0.6f * ((at - last) / (atMs - lastAt)) + 0.4f * v
        last = at; lastAt = atMs
    }

    /** The speed at a lift at [atMs]: none for a finger that stopped before lifting. */
    fun at(atMs: Long): Float = if (atMs - lastAt > STILL_MS) 0f else v

    companion object {
        const val STILL_MS = 80L
    }
}
