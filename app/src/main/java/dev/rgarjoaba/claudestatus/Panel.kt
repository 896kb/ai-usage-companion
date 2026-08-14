package dev.rgarjoaba.claudestatus

/** Measured hardware facts and the arch_rice palette. Single source of truth. */
object Panel {
    const val DISPLAY_ID = 1
    const val WIDTH = 904
    const val HEIGHT = 572
    /** Camera cutout bounding box is x in [0, CUTOUT_RIGHT). Display is LIVE inside it. */
    const val CUTOUT_RIGHT = 296

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
