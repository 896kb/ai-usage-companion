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
    private val MARKER = Regex("""(\d+(?:\+\d+)*),(\d+|-)""")

    fun parse(out: String): Densities = Densities(
        physical = PHYSICAL.find(out)?.groupValues?.get(1)?.toIntOrNull(),
        override = OVERRIDE.find(out)?.groupValues?.get(1)?.toIntOrNull(),
    )

    /**
     * The crash marker: the override(s) this app may have on display 1, and the override
     * that was there before it (null = none), written as `<applied>,<baseline or ->` before
     * every override of ours and cleared once the baseline is back. A service killed
     * outright leaves it behind for the next one to read with [start].
     *
     * During a change from one of our overrides to another (300 to 310 for the same guest,
     * or guest A's to guest B's) both are listed, `300+310,380`: killed before the new one
     * takes, the display still shows the old one, and that must still read as ours. It is
     * rewritten to the new one alone once the change has taken.
     */
    data class Marker(val applied: Set<Int>, val baseline: Int?)

    /** [applied], and [from] too while the display may still show it (a change between ours). */
    fun marker(applied: Int, baseline: Int?, from: Int? = null): String =
        (if (from != null && from != applied) "$from+$applied" else "$applied") + ",${baseline ?: "-"}"

    /** Null for anything else — `settings get` prints `null` for a missing key. */
    fun parseMarker(s: String?): Marker? {
        val m = MARKER.matchEntire(s?.trim() ?: return null) ?: return null
        val applied = m.groupValues[1].split('+').map { it.toIntOrNull() ?: return null }.toSet()
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
     * The override found now is ours only if it is one the marker says we set (either side
     * of a change between ours); then the marker's baseline is the display's own. Anything else — no marker, or an
     * override somebody has changed or reset since — is the display's own as found, and a
     * marker that does not match it is stale. So an override we did not set is never wiped.
     */
    fun start(override: Int?, marker: String?): Start {
        val m = parseMarker(marker)
        return if (m != null && override != null && override in m.applied) Start(m.baseline, true)
        else Start(override, false)
    }
}
