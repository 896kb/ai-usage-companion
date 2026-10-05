package dev.kb896.aiusage.rear

/**
 * A single tap in the left band of the rear touchscreen, read from `getevent` output.
 *
 * The camera's rear-screen layout leaves its left part an empty grey band beside the
 * lenses, and taps there do nothing — the camera has no way out on the back at all.
 * Nothing of ours can be drawn over it: HyperOS refuses overlay windows on the rear
 * display to every app outside a short whitelist ("Not allow non-system app … add
 * system_window on rear display", WindowManagerServiceImpl.hasPermission). So the tap is
 * read where shell can read it, the raw touchscreen, and the camera is closed from there.
 *
 * Fed one `getevent <device>` line at a time — `type code value` in hex, optionally after
 * a device or time prefix. Multitouch protocol B: a contact starts with a tracking id and
 * ends with tracking id -1 (ffffffff). A tap is one finger only, short, barely moving,
 * and starting inside the band.
 *
 * [maxX] and [maxY] are the device's raw axis maxima; [band] the band's right edge as a
 * fraction of the width.
 */
class BandTap(
    private val maxX: Int,
    private val maxY: Int,
    private val band: Float = BAND,
) {
    private var slot = 0
    private var active = 0
    private var primary = -1
    private var crowded = false
    private var startAt = 0L
    private var startX = -1
    private var startY = -1
    private var x = -1
    private var y = -1

    /** Feeds one line; true when it ends a tap in the band. */
    fun feed(line: String, nowMs: Long): Boolean {
        val t = line.trim().split(WHITESPACE).takeLast(3)
        if (t.size < 3) return false
        val type = t[0].toIntOrNull(16) ?: return false
        val code = t[1].toIntOrNull(16) ?: return false
        val value = t[2].toLongOrNull(16)?.toInt() ?: return false
        if (type != EV_ABS) return false
        when (code) {
            ABS_MT_SLOT -> slot = value
            ABS_MT_TRACKING_ID -> return if (value == -1) lift(nowMs) else land(nowMs)
            ABS_MT_POSITION_X -> if (slot == primary) { x = value; if (startX < 0) startX = value }
            ABS_MT_POSITION_Y -> if (slot == primary) { y = value; if (startY < 0) startY = value }
        }
        return false
    }

    private fun land(nowMs: Long): Boolean {
        active++
        if (active == 1) {
            primary = slot
            crowded = false
            startAt = nowMs
            startX = -1; startY = -1; x = -1; y = -1
        } else {
            crowded = true
        }
        return false
    }

    private fun lift(nowMs: Long): Boolean {
        if (active == 0) return false
        active--
        if (active > 0) return false
        val tap = !crowded &&
            startX >= 0 && startY >= 0 &&
            nowMs - startAt <= TAP_MS &&
            kotlin.math.abs(x - startX) <= maxX * SLOP &&
            kotlin.math.abs(y - startY) <= maxY * SLOP &&
            startX <= maxX * band
        primary = -1
        return tap
    }

    companion object {
        /**
         * The lens column, the panel's camera cutout: the camera keeps it grey on every
         * model, since its picture cannot go under the lenses. On the 17 Pro the grey runs a
         * little further (to 40.6% of the width), but the Pro Max's camera layout has not
         * been measured, and a tap past the grey lands on the viewfinder — so the zone is
         * the part that is grey on both. The panel lights up exactly this strip for the tip.
         */
        const val BAND = dev.kb896.aiusage.Panel.CUTOUT_RIGHT / dev.kb896.aiusage.Panel.WIDTH.toFloat()
        const val TAP_MS = 400L
        const val SLOP = 0.03f
        private const val EV_ABS = 0x03
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
        private val WHITESPACE = Regex("""\s+""")

        /**
         * The rear touchscreen in `getevent -pl` output: the device whose X axis is longer
         * than its Y, since the rear display is the landscape one. Its path and axis maxima.
         */
        fun rearDevice(dump: String): Triple<String, Int, Int>? {
            var path: String? = null
            var mx = -1
            var my = -1
            var found: Triple<String, Int, Int>? = null
            fun close() {
                val p = path
                if (found == null && p != null && mx > 0 && my > 0 && mx > my) found = Triple(p, mx, my)
            }
            val add = Regex("""^add device \d+: (\S+)""")
            val axis = Regex("""(ABS_MT_POSITION_[XY])\s*:.*\bmax (\d+)""")
            for (line in dump.lineSequence()) {
                add.find(line)?.let { close(); path = it.groupValues[1]; mx = -1; my = -1; return@let }
                axis.find(line)?.let {
                    val max = it.groupValues[2].toInt()
                    if (it.groupValues[1].endsWith("X")) mx = max else my = max
                }
            }
            close()
            return found
        }
    }
}
