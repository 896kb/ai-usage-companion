package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

/**
 * The rear touchscreen's first finger, from `getevent <device>` lines, as down, move and
 * up in panel pixels — the shell service cannot otherwise see a guest app's touches
 * (see EdgeGestures).
 *
 * Multitouch protocol B, as [BandTap] reads it: a tracking id starts a contact, -1 ends
 * it, positions arrive between SYN_REPORTs. An event is emitted on the SYN that closes a
 * frame, so a down carries its first position rather than none.
 *
 * Other fingers are ignored until the first lifts; a finger still down then is not
 * promoted, the next landing is the new first. The last position is kept across lifts, as
 * the kernel only re-sends an axis that changed, so a landing at the same X or Y as the
 * last contact arrives without it.
 */
class RearTouches(private val maxX: Int, private val maxY: Int) {

    sealed interface Event { val x: Float; val y: Float; val atMs: Long }
    data class Down(override val x: Float, override val y: Float, override val atMs: Long) : Event
    data class Move(override val x: Float, override val y: Float, override val atMs: Long) : Event
    data class Up(override val x: Float, override val y: Float, override val atMs: Long) : Event

    private var slot = 0
    private var primary = -1
    private var landed = false
    private var lifted = false
    private var moved = false
    private var rawX = -1
    private var rawY = -1

    fun feed(line: String, nowMs: Long): Event? {
        val t = line.trim().split(WHITESPACE).takeLast(3)
        if (t.size < 3) return null
        val type = t[0].toIntOrNull(16) ?: return null
        val code = t[1].toIntOrNull(16) ?: return null
        val value = t[2].toLongOrNull(16)?.toInt() ?: return null
        if (type == EV_SYN && code == SYN_REPORT) return frame(nowMs)
        if (type != EV_ABS) return null
        when (code) {
            ABS_MT_SLOT -> slot = value
            ABS_MT_TRACKING_ID -> if (value == -1) {
                if (slot == primary) lifted = true
            } else if (primary < 0) {
                primary = slot; landed = true
            }
            ABS_MT_POSITION_X -> if (slot == primary) { rawX = value; moved = true }
            ABS_MT_POSITION_Y -> if (slot == primary) { rawY = value; moved = true }
        }
        return null
    }

    private fun frame(nowMs: Long): Event? {
        if (rawX < 0 || rawY < 0) {
            // Only before the very first position: nothing to report, but never stay stuck.
            if (lifted) primary = -1
            landed = false; lifted = false; moved = false
            return null
        }
        val x = rawX * Panel.WIDTH / (maxX + 1f)
        val y = rawY * Panel.HEIGHT / (maxY + 1f)
        val e: Event? = when {
            landed -> Down(x, y, nowMs)
            lifted -> Up(x, y, nowMs)
            moved && primary >= 0 -> Move(x, y, nowMs)
            else -> null
        }
        if (lifted) primary = -1
        landed = false; lifted = false; moved = false
        return e
    }

    private companion object {
        const val EV_SYN = 0x00
        const val SYN_REPORT = 0x00
        const val EV_ABS = 0x03
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        val WHITESPACE = Regex("""\s+""")
    }
}
