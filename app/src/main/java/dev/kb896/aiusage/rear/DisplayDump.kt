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
    private val density = Regex(""", density (\d+),""")
    private val cutoutInsets = Regex("""cutout DisplayCutout\{insets=Rect\((\d+), (\d+) - (\d+), (\d+)\)""")
    private val topLeftCorner = Regex("""RoundedCorner\{position=TopLeft, radius=(\d+)""")

    /**
     * What physical display [physicalId]'s DisplayDeviceInfo line says of it: its size, the
     * cutout's left inset (on the rear display the lens column, 296 of 904 on the 17 Pro), its
     * top-left corner's radius and its own density — one line carries them all, with the id.
     * In the panel's natural orientation; RearGeometry.resolve only takes the inset and the
     * corner from it when its size is the display's. Null when no line is that display's.
     */
    fun device(dump: String, physicalId: String): RearGeometry.Reading? {
        for (line in dump.lineSequence()) {
            val size = deviceSize.find(line) ?: continue
            if (size.groupValues[1] != physicalId) continue
            return RearGeometry.Reading(
                width = size.groupValues[2].toInt(),
                height = size.groupValues[3].toInt(),
                cutout = cutoutInsets.find(line)?.groupValues?.get(1)?.toInt()?.takeIf { it > 0 },
                corner = topLeftCorner.find(line)?.groupValues?.get(1)?.toInt(),
                dpi = density.find(line)?.groupValues?.get(1)?.toInt(),
            )
        }
        return null
    }
}
