package dev.kb896.aiusage

/**
 * The arch_rice palette and utilisation ramp (the design, shared by every device), plus
 * a read-through to the per-device hardware facts in [DeviceProfiles.current].
 *
 * The geometry members below are no longer compile-time constants: they resolve, at
 * runtime, to the profile for the phone this build runs on — the Xiaomi rear display or a
 * Galaxy Z Flip cover screen. Call sites are unchanged (`Panel.WIDTH`, `Panel.DISPLAY_ID`,
 * …); only their source moved. See [DeviceProfile].
 */
object Panel {
    private val device: DeviceProfile get() = DeviceProfiles.current

    val DISPLAY_ID: Int get() = device.displayId
    val WIDTH: Int get() = device.width
    val HEIGHT: Int get() = device.height
    /** Camera cutout bounding box is x in [0, CUTOUT_RIGHT). Display is LIVE inside it. */
    val CUTOUT_RIGHT: Int get() = device.cutoutRight
    /** Tail of the panel display's physical id in dumpsys — see [DeviceProfile]. */
    val PHYSICAL_ID_SUFFIX: String get() = device.physicalIdSuffix
    /** Whether this device draws the lens rings — see [DeviceProfile.hasRings]. */
    val HAS_RINGS: Boolean get() = device.hasRings

    object Palette {
        const val BG = 0xFF1D1F21.toInt()
        const val FG = 0xFFC5C8C6.toInt()
        const val PERSONAL = 0xFFFFFF78.toInt()
        const val WORK = 0xFFB5BD68.toInt()
        const val DIM = 0xFF555555.toInt()
        const val TRACK = 0xFF2A2C2E.toInt()
        const val RULE = 0xFF2F3234.toInt()
        const val WARN = 0xFFF0C674.toInt()
        const val CRIT = 0xFFBD2C40.toInt()

        /**
         * Calibration-grid line tints. Derived, like TRACK/RULE above — the rice
         * defines no grid colour. Reusing TRACK's and RULE's exact values rather
         * than inventing new ones: a grid's fine/coarse lines are the same "subtle
         * tint over BG" role those already serve, and picking untested numbers isn't
         * safe without the device to check contrast against, which this round doesn't
         * have.
         */
        const val GRID_FINE = 0xFF2A2C2E.toInt()
        const val GRID_COARSE = 0xFF2F3234.toInt()
    }

    /** Ramp stops as (position, argb), ascending by position. */
    val RAMP_STOPS: List<Pair<Float, Int>> = listOf(
        0.00f to 0xFFB5BD68.toInt(),
        0.40f to 0xFFF0C674.toInt(),
        0.70f to 0xFFDE935F.toInt(),
        0.90f to 0xFFCC6666.toInt(),
        1.00f to 0xFFBD2C40.toInt(),
    )
}
