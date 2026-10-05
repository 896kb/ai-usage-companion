package dev.kb896.aiusage.rear

/**
 * Parses `dumpsys display` for the physical id behind a logical display — the number
 * SurfaceFlinger heads its HWC sections with, which [SurfaceDump] needs to know which
 * section is the rear display.
 *
 * Read from the logical display's own dump (LogicalDisplay.dumpLocked on Android 16):
 *
 *     mDisplayId=1
 *     mPrimaryDisplayDevice=Built-in Screen(local:4630946949513469332)
 *
 * "local:" is how a built-in panel's unique id is spelled, and what follows it is the
 * physical id. It is read off the phone rather than written down because it belongs to
 * the PANEL: the 17 Pro's rear display and the Pro Max's are different panels with
 * different ids, and the one written down was the Pro's.
 */
object DisplayDump {

    private val displayLine = Regex("""^\s*mDisplayId=(\d+)\s*$""")
    private val deviceLine = Regex("""^\s*mPrimaryDisplayDevice=.*\(local:(\d+)\)\s*$""")

    /**
     * The physical id of logical display [displayId], or null when the dump does not
     * say. Only the lines between that display's `mDisplayId=` and the next one count,
     * so another display's device is never taken for it.
     */
    fun physicalId(dump: String, displayId: Int): String? {
        var inDisplay = false
        for (line in dump.lineSequence()) {
            val id = displayLine.find(line)?.groupValues?.get(1)
            if (id != null) {
                inDisplay = id == displayId.toString()
                continue
            }
            if (inDisplay) deviceLine.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    private val deviceSize = Regex("""uniqueId="local:(\d+)", (\d+) x (\d+),""")
    private val cutoutInsets = Regex("""cutout DisplayCutout\{insets=Rect\((\d+), (\d+) - (\d+), (\d+)\)""")

    /**
     * The left edge of the usable area on physical display [physicalId], as a fraction of
     * its width: the left inset of the cutout the display reports — on the rear display,
     * the lens column (296 of 904 on the 17 Pro). Read from its DisplayDeviceInfo line,
     * which carries the id, the size and the cutout together. Null when the dump does not
     * say, or reports no left inset.
     */
    fun leftInsetFraction(dump: String, physicalId: String): Float? {
        for (line in dump.lineSequence()) {
            val size = deviceSize.find(line) ?: continue
            if (size.groupValues[1] != physicalId) continue
            val width = size.groupValues[2].toInt()
            val left = cutoutInsets.find(line)?.groupValues?.get(1)?.toInt() ?: return null
            return if (width > 0 && left > 0) left / width.toFloat() else null
        }
        return null
    }
}
