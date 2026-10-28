package dev.kb896.aiusage.rear

/**
 * What `wm density -d 1` says, parsed. It prints `Physical density: 450`, and a second line
 * `Override density: 380` while an override is set. Either is null when the output does
 * not say (an error, an empty read).
 */
object DensityDump {
    /** The 17 Pro's rear display, for when the dump says nothing. */
    const val FALLBACK = 450

    data class Densities(val physical: Int?, val override: Int?)

    private val PHYSICAL = Regex("""Physical density:\s*(\d+)""")
    private val OVERRIDE = Regex("""Override density:\s*(\d+)""")
    private val MARKER = Regex("""(\d+),(\d+|-)""")

    fun parse(out: String): Densities = Densities(
        physical = PHYSICAL.find(out)?.groupValues?.get(1)?.toIntOrNull(),
        override = OVERRIDE.find(out)?.groupValues?.get(1)?.toIntOrNull(),
    )

    /**
     * The crash marker: the override this app set on display 1, and the override that was
     * there before it (null = none), written as `<applied>,<baseline or ->` before every
     * override of ours and cleared once the baseline is back. A service killed outright
     * leaves it behind for the next one to read with [start].
     */
    data class Marker(val applied: Int, val baseline: Int?)

    fun marker(applied: Int, baseline: Int?): String = "$applied,${baseline ?: "-"}"

    /** Null for anything else — `settings get` prints `null` for a missing key. */
    fun parseMarker(s: String?): Marker? {
        val m = MARKER.matchEntire(s?.trim() ?: return null) ?: return null
        val applied = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2]
        val baseline = if (b == "-") null else (b.toIntOrNull() ?: return null)
        return Marker(applied, baseline)
    }

    /**
     * What the service finds at its first read. [baseline] is display 1's own state, put
     * back whenever no guest wants a density (null = no override, so `reset`); [ours] says
     * the override there now is one this app set and never took back.
     */
    data class Start(val baseline: Int?, val ours: Boolean)

    /**
     * The override found now is ours only if it is exactly the one the marker says we set;
     * then the marker's baseline is the display's own. Anything else — no marker, or an
     * override somebody has changed or reset since — is the display's own as found, and a
     * marker that does not match it is stale. So an override we did not set is never wiped.
     */
    fun start(override: Int?, marker: String?): Start {
        val m = parseMarker(marker)
        return if (m != null && override != null && override == m.applied) Start(m.baseline, true)
        else Start(override, false)
    }
}
