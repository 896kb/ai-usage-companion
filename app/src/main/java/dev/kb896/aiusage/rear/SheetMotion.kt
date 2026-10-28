package dev.kb896.aiusage.rear

import kotlin.math.abs

/**
 * How far open the shell's portal sheet is, and where it is going: the panel's own portal
 * motion (RearDialView's pt* fields), kept apart from the drawing so the shell service can
 * drive ShellSheet from the raw touchscreen and the rules can be tested.
 *
 * - SHUT, OPEN: at rest, 0 or 1.
 * - HELD: a finger has it. [hold] stops it where it was (a settle caught mid-way, with
 *   [heading] remembering where it was going); [grab] then lets that finger drag it, from
 *   where it was — never a jump to an end — so [drag] reads `base - dy / TRAVEL`, which is
 *   PortalPull.progress exactly when it was held at rest.
 * - SETTLING: let go, it goes the rest of the way, eased out as the control centre's settle
 *   is, over ControlCenter.settleMs from the finger's speed. [at] advances it and puts it
 *   at rest on arrival.
 *
 * Times are uptime milliseconds, as RearTouches stamps its events. Not thread-safe: the
 * service drives it under one lock.
 */
class SheetMotion {

    enum class Phase { SHUT, HELD, SETTLING, OPEN }

    var phase = Phase.SHUT
        private set
    /** Open is where it is going (settling), was going (held), or is (at rest). */
    var heading = false
        private set
    /** The current settle's length, for the curious. */
    var settleMs = 0L
        private set

    private var from = 0f
    private var to = 0f
    private var startMs = 0L
    private var base = 0f
    private var dragging = false
    private var downY = 0f
    private var dy = 0f
    private var lastY = 0f
    private var lastAt = 0L
    private var velocity = 0f

    /** How far open at [nowMs], 0 to 1, without moving a settle on. */
    fun progress(nowMs: Long): Float = when (phase) {
        Phase.SHUT -> 0f
        Phase.OPEN -> 1f
        Phase.HELD -> if (dragging) (base - dy / PortalPull.TRAVEL).coerceIn(0f, 1f) else base
        Phase.SETTLING -> {
            val t = if (settleMs <= 0L) 1f else ((nowMs - startMs) / settleMs.toFloat()).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t)
            from + (to - from) * e
        }
    }

    /** [progress], and a settle that has arrived comes to rest. */
    fun at(nowMs: Long): Float {
        val p = progress(nowMs)
        if (phase == Phase.SETTLING && nowMs - startMs >= settleMs) rest(to >= 1f)
        return p
    }

    /** Fully open, so the sheet's boxes are where PortalSheet.hit says. */
    fun fullyOpen(nowMs: Long) = progress(nowMs) >= OPEN_AT

    /** A finger on it: it stops where it is. */
    fun hold(nowMs: Long) {
        val p = at(nowMs)
        heading = when (phase) {
            Phase.SHUT -> false
            Phase.OPEN -> true
            else -> heading
        }
        base = p
        dragging = false
        phase = Phase.HELD
    }

    /** That finger drags it from here, having landed at [downY]. */
    fun grab(downY: Float, atMs: Long) {
        hold(atMs)
        this.downY = downY
        lastY = downY; lastAt = atMs; velocity = 0f; dy = 0f
        dragging = true
    }

    /** The finger at [y]: how far open it is now. */
    fun drag(y: Float, atMs: Long): Float {
        if (phase != Phase.HELD || !dragging) return progress(atMs)
        // A step that took no time says nothing about speed — taken as 1 ms, a finger that
        // had moved 20 px since it landed read as a flick. Its distance counts in the next
        // step that does take time.
        if (atMs > lastAt) {
            // As the panel's: the newest step counts most, so a flick is read at its end.
            velocity = 0.6f * ((y - lastY) / (atMs - lastAt)) + 0.4f * velocity
            lastY = y; lastAt = atMs
        }
        dy = y - downY
        return progress(atMs)
    }

    /**
     * Let go at [y]: open or shut as PortalPull.release has it — a flick goes the way it
     * was flicked, else past halfway — settling there from where it is at the finger's
     * speed. True if it opens. Not dragging (held only), it carries on where it was going.
     */
    fun release(y: Float, atMs: Long): Boolean {
        if (phase != Phase.HELD) return heading
        if (!dragging) { resume(atMs); return heading }
        // A finger that stopped before lifting is not flicking.
        val v = if (atMs - lastAt > STILL_MS) 0f else velocity
        dy = y - downY
        // From whichever end it was nearer when grabbed, the drag measured from that end:
        // PortalPull's rule unchanged, for a sheet caught anywhere.
        val wasOpen = base >= 0.5f
        val end = if (wasOpen) 1f else 0f
        val open = PortalPull.release(wasOpen, dy + (end - base) * PortalPull.TRAVEL, v)
        settle(open, atMs, v)
        return open
    }

    /** Held but not dragged (a tap mid-settle, a long-press): on to where it was going. */
    fun resume(nowMs: Long) = settle(heading, nowMs)

    /**
     * Go [open] from where it is at [nowMs], carrying on at [velocity] (panel units a ms,
     * down positive) if it was moving that way. Already there: at rest at once.
     */
    fun settle(open: Boolean, nowMs: Long, velocity: Float = 0f) {
        from = progress(nowMs)
        to = if (open) 1f else 0f
        heading = open
        dragging = false
        if (abs(to - from) < 0.001f) { rest(open); return }
        val toward = if (open) -velocity else velocity
        settleMs = ControlCenter.settleMs(abs(to - from) * PortalPull.TRAVEL, toward)
        startMs = nowMs
        phase = Phase.SETTLING
    }

    /** Shut, now: nothing moving, nothing held. */
    fun reset() = rest(false)

    private fun rest(open: Boolean) {
        phase = if (open) Phase.OPEN else Phase.SHUT
        heading = open
        dragging = false
        base = if (open) 1f else 0f
        dy = 0f
    }

    companion object {
        /** Open enough for taps: PortalSheet.hit is laid out for the settled sheet. */
        const val OPEN_AT = 0.99f
        /** A finger still this long before lifting has no speed. */
        const val STILL_MS = 80L
    }
}
