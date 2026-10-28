package dev.kb896.aiusage.rear

import kotlin.math.abs

/**
 * Where the shell's portal sheet stands on its one axis, and where it is going: kept apart
 * from the drawing so the shell service can drive ShellSheet from the raw touchscreen and
 * the rules can be tested. In panel pixels of finger travel, up positive: 0 shut,
 * PortalSheet.OPEN open (the pull's whole travel), and past it — with a guest — the open
 * sheet's contents scrolled up by as much as the finger goes, to PortalSheet.REVEALED,
 * where the DPI slider shows (PortalSheet.progress, .scroll).
 *
 * It rests at one of its [detents] (PortalSheet.detents):
 * - SHUT at 0, OPEN at any other detent ([target] says which).
 * - HELD: a finger has it. [hold] stops it where it was (a settle caught mid-way, with
 *   [target] remembering where it was going); [grab] then lets that finger drag it, from
 *   where it was — never a jump — so [drag] reads `base - dy`, up raising it. A drag goes
 *   no further than the stops either side of where it was grabbed: the pull that opens
 *   the sheet stops open (the slider takes a swipe of its own), and scrolled back from
 *   the slider the sheet stops open too (shutting it takes another).
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
    var detents: List<Float> = listOf(0f, PortalSheet.OPEN)
        private set

    private var from = 0f
    private var startMs = 0L
    private var base = 0f
    private var dragging = false
    private var downY = 0f
    private var dy = 0f
    /** The lowest and highest a drag may take it: the stops either side of where it was grabbed. */
    private var floor = 0f
    private var ceiling = 0f
    private val speed = FingerSpeed()

    /** Where it stands at [nowMs], without moving a settle on. */
    fun position(nowMs: Long): Float = when (phase) {
        Phase.SHUT -> 0f
        Phase.OPEN -> target
        Phase.HELD -> if (dragging) (base - dy).coerceIn(floor, ceiling) else base
        Phase.SETTLING -> {
            val t = if (settleMs <= 0L) 1f else ((nowMs - startMs) / settleMs.toFloat()).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t)
            from + (target - from) * e
        }
    }

    private val top get() = detents.last()

    /** [position], and a settle that has arrived comes to rest. */
    fun at(nowMs: Long): Float {
        val h = position(nowMs)
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
     * gives way to the nearest one that is: a sheet scrolled to a slider that has gone
     * scrolls back, open.
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
        floor = detents.lastOrNull { it < base - 0.5f } ?: 0f
        ceiling = detents.firstOrNull { it > base + 0.5f } ?: top
        speed.start(downY, atMs)
        dy = 0f
        dragging = true
    }

    /** The finger at [y]: how high it is now. */
    fun drag(y: Float, atMs: Long): Float {
        if (phase != Phase.HELD || !dragging) return position(atMs)
        speed.move(y, atMs)
        dy = y - downY
        return position(atMs)
    }

    /**
     * Let go at [y]: off to the detent [snap] picks for where it is and how fast it was
     * going — never past the stops either side of where it was grabbed — settling there at the
     * finger's speed. The detent it goes to. Not dragging (held only), it carries on where
     * it was going.
     */
    fun release(y: Float, atMs: Long): Float {
        if (phase != Phase.HELD) return target
        if (!dragging) { resume(atMs); return target }
        val v = speed.at(atMs)
        dy = y - downY
        val h = position(atMs)
        val to = snap(detents.filter { it in floor..ceiling }, h, v)
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
        from = position(nowMs)
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
        /**
         * Where a sheet let go at [h] at [velocity] (panel units a ms, down positive) comes
         * to rest among [detents]: a flick (past ControlCenter.FLING) to the next stop the
         * way it was flicked — one stop, never two — else the nearest. With two stops that
         * is PortalPull's old rule exactly: a flick wins, else past halfway.
         */
        fun snap(detents: List<Float>, h: Float, velocity: Float): Float {
            val up = -velocity
            return when {
                up > ControlCenter.FLING -> detents.firstOrNull { it > h + 0.5f } ?: detents.last()
                up < -ControlCenter.FLING -> detents.lastOrNull { it < h - 0.5f } ?: detents.first()
                else -> detents.minBy { abs(it - h) }
            }
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
