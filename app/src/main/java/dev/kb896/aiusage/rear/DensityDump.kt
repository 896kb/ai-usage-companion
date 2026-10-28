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

    fun parse(out: String): Densities = Densities(
        physical = PHYSICAL.find(out)?.groupValues?.get(1)?.toIntOrNull(),
        override = OVERRIDE.find(out)?.groupValues?.get(1)?.toIntOrNull(),
    )
}
