package dev.kb896.aiusage.rear

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

/**
 * The rear touchscreen's fingers, every one of them, as the motion events a guest on a private
 * display (PrivateDisplay) should get: `getevent` lines in, [Out]s in that display's pixels out.
 * Pure; fed by the service's one touch reader, next to RearTouches, and off to the injector's
 * thread with what it says. VirtualDisplaySpike's parsing, measured on the phone 2026-10-30
 * (1200+ events, none failed, a map's pinch and double tap to zoom all good): multitouch
 * protocol B, a frame's lifts, then its moves, then its landings, so a display can follow.
 *
 * A finger landing at x >= [appX] is the app's, at x - [appX], clamped if it then wanders left;
 * one landing left of that (behind the lenses) is nobody's.
 *
 * THE GESTURES ARE THE SHELL'S FIRST. EdgeGestures reads the same frame before this does, so
 * [feed]'s `open` already says whether the sheet or the control centre has just taken the finger:
 * - Not open, with something forwarded: ACTION_CANCEL, once, and nothing more of that gesture
 *   even if it opens again before the fingers have all lifted ([shut]). A bottom pull's and a top
 *   pull's fingers have been the app's for their first [slop] of travel, and this is how it hears
 *   they are not any more; a landing while not open is never forwarded at all.
 * - The right edge, x >= [backLeft], is Back's, and EdgeGestures only says so at the lift: too
 *   late for a cancel, the app would have been scrolled by it. So a finger landing there alone is
 *   held back, unsent, until it shows what it is ([HOLD_MS] or [slop] of travel): going left more
 *   than sideways it is Back's, and the app never hears of it; anything else, a lift, a second
 *   finger or a finger that stays gets its held landing and goes on as any other. A tap on
 *   something at the app's right edge arrives on its lift, not lost.
 *
 * Not thread-safe: the reader's thread alone.
 */
class TouchForward(
    private val maxX: Int,
    private val maxY: Int,
    private val rearW: Int,
    private val rearH: Int,
    private val appX: Int,
    private val backLeft: Float,
    private val slop: Float,
) {

    /** One motion event: pointer ids (sorted) and their places on the display, at [atMs], its gesture begun at [downMs]. */
    class Out(val action: Int, val ids: IntArray, val xs: FloatArray, val ys: FloatArray, val downMs: Long, val atMs: Long) {
        val masked: Int get() = action and MotionEvent.ACTION_MASK
        val index: Int get() = action shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
    }

    private class Slot {
        var tracking = -1
        var rawX = -1
        var rawY = -1
        /** This frame: a contact started, one ended, or one did both in that order (a light tap). */
        var land = false
        var lift = false
        var tap = false
        var moved = false
        /** Forwarded: its pointer id and its place on the display. */
        var id = -1
        var x = 0f
        var y = 0f
    }

    private val slots = Array(SLOTS) { Slot() }
    private val pointers = ArrayList<Slot>()
    private val ids = java.util.BitSet()
    private var slot = 0
    private var lastRawX = -1
    private var lastRawY = -1
    private var downAt = 0L
    /** Nothing forwarded until every finger on the glass has lifted. */
    private var shut = false
    /** A finger landed on the Back band, not yet sent; its landing kept in the three below. */
    private var held: Slot? = null
    private var heldX = 0f
    private var heldY = 0f
    private var heldAt = 0L

    /** What this line completes: nothing but on a SYN_REPORT. [open] is asked there, once a frame. */
    fun feed(line: String, nowMs: Long, open: () -> Boolean): List<Out> {
        val t = line.trim().split(WHITESPACE).takeLast(3)
        if (t.size < 3) return emptyList()
        val type = t[0].toIntOrNull(16) ?: return emptyList()
        val code = t[1].toIntOrNull(16) ?: return emptyList()
        val value = t[2].toLongOrNull(16)?.toInt() ?: return emptyList()
        if (type == EV_SYN && code == SYN_REPORT) return frame(nowMs, open())
        if (type != EV_ABS) return emptyList()
        if (code == ABS_MT_SLOT) { if (value in slots.indices) slot = value; return emptyList() }
        val s = slots[slot]
        when (code) {
            ABS_MT_TRACKING_ID -> {
                if (value == -1) {
                    if (s.land) { s.land = false; s.tap = true } else if (s.tracking != -1) s.lift = true
                } else {
                    if (s.tracking != -1) s.lift = true // a new contact in the slot, the old one's -1 unsent
                    s.land = true
                }
                s.tracking = value
            }
            ABS_MT_POSITION_X -> { s.rawX = value; lastRawX = value; s.moved = true }
            ABS_MT_POSITION_Y -> { s.rawY = value; lastRawY = value; s.moved = true }
        }
        return emptyList()
    }

    /** A gesture still open, cancelled and forgotten: the reader is ending. */
    fun cancel(nowMs: Long): List<Out> = ArrayList<Out>().also { cancelInto(it, nowMs) }

    private fun cancelInto(out: MutableList<Out>, now: Long) {
        held = null
        if (pointers.isEmpty()) return
        out += emit(MotionEvent.ACTION_CANCEL, now)
        for (p in pointers) { ids.clear(p.id); p.id = -1 }
        pointers.clear()
    }

    private fun frame(now: Long, open: Boolean): List<Out> {
        val out = ArrayList<Out>(2)
        if (!open) {
            cancelInto(out, now)
            shut = true
        }
        // A lift's last position, if it came with one; a slot's new contact is not the old one.
        var changed = false
        for (s in slots) if (s.moved && !s.land && s.id >= 0 && place(s)) changed = true
        held?.let { h -> if (decideHeld(h, now, out)) changed = true }
        for (s in slots) if (s.lift) {
            if (s === held) sendHeld(s, out)
            if (s.id >= 0) up(s, now, out)
            s.lift = false
        }
        if (changed && pointers.isNotEmpty()) out += emit(MotionEvent.ACTION_MOVE, now)
        for (s in slots) {
            if (s.land || s.tap) land(s, now, out)
            s.moved = false
        }
        if (shut && slots.none { it.tracking != -1 }) shut = false
        return out
    }

    private fun land(s: Slot, now: Long, out: MutableList<Out>) {
        // The kernel only sends an axis that changed; before this slot's first, any slot's last.
        if (s.rawX < 0) s.rawX = lastRawX
        if (s.rawY < 0) s.rawY = lastRawY
        if (s.rawX < 0 || s.rawY < 0) { s.tap = false; return } // a landing waits for an axis; a tap is gone
        val rearX = s.rawX * rearW / (maxX + 1f)
        val tap = s.tap
        s.land = false
        s.tap = false
        if (shut || rearX < appX || pointers.size >= MAX_POINTERS) return
        // A second finger: no swipe at the edge. The held one goes first, in order.
        held?.let { sendHeld(it, out) }
        place(s)
        if (!tap && pointers.isEmpty() && rearX >= backLeft) {
            held = s
            heldX = s.x
            heldY = s.y
            heldAt = now
            return
        }
        send(s, now, out)
        if (tap) up(s, now, out)
    }

    /** The held finger this frame: true if it went on to be the app's and has moved since its landing. */
    private fun decideHeld(h: Slot, now: Long, out: MutableList<Out>): Boolean {
        if (h.lift) return false // its landing and its lift, together, in the lifts' turn
        if (h.moved) place(h)
        val left = heldX - h.x
        val down = abs(h.y - heldY)
        if (left >= slop && left > down) { held = null; shut = true; return false } // Back's
        if (max(abs(left), down) < slop && now - heldAt < HOLD_MS) return false
        val x = h.x
        val y = h.y
        sendHeld(h, out)
        h.x = x
        h.y = y
        return x != heldX || y != heldY
    }

    /** [h]'s landing, as it was and when it was. */
    private fun sendHeld(h: Slot, out: MutableList<Out>) {
        held = null
        h.x = heldX
        h.y = heldY
        send(h, heldAt, out)
    }

    /** [s]'s place from its raw position; true if that moved it. */
    private fun place(s: Slot): Boolean {
        if (s.rawX < 0 || s.rawY < 0) return false
        val x = (s.rawX * rearW / (maxX + 1f) - appX).coerceIn(0f, rearW - appX - 1f)
        val y = (s.rawY * rearH / (maxY + 1f)).coerceIn(0f, rearH - 1f)
        val moved = x != s.x || y != s.y
        s.x = x
        s.y = y
        return moved
    }

    /** [s] down where it is placed. */
    private fun send(s: Slot, at: Long, out: MutableList<Out>) {
        s.id = ids.nextClearBit(0)
        ids.set(s.id)
        var i = 0
        while (i < pointers.size && pointers[i].id < s.id) i++
        pointers.add(i, s)
        if (pointers.size == 1) {
            downAt = at
            out += emit(MotionEvent.ACTION_DOWN, at)
        } else {
            out += emit(MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), at)
        }
    }

    private fun up(s: Slot, now: Long, out: MutableList<Out>) {
        val i = pointers.indexOf(s)
        out += emit(
            if (pointers.size == 1) MotionEvent.ACTION_UP
            else MotionEvent.ACTION_POINTER_UP or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            now,
        )
        pointers.removeAt(i)
        ids.clear(s.id)
        s.id = -1
    }

    private fun emit(action: Int, now: Long) = Out(
        action,
        IntArray(pointers.size) { pointers[it].id },
        FloatArray(pointers.size) { pointers[it].x },
        FloatArray(pointers.size) { pointers[it].y },
        downAt,
        now,
    )

    companion object {
        /** A finger that stays on the Back band this long is no swipe: the app's. */
        const val HOLD_MS = 150L
        const val MAX_POINTERS = 16
        private const val SLOTS = 64
        private const val EV_SYN = 0x00
        private const val SYN_REPORT = 0x00
        private const val EV_ABS = 0x03
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
        private val WHITESPACE = Regex("""\s+""")
    }
}
