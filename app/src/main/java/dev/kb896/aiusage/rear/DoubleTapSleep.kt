package dev.kb896.aiusage.rear

/**
 * The rear screen's way to sleep, as Xiaomi's own app does it: a double tap dims the
 * panel and lets go of the display, and HyperOS powers the display down on its own
 * rear-screen timeout (subscreen_display_time, ten seconds on the Xiaomi 17 Pro) —
 * which puts the display's power group to sleep properly. A second double tap during
 * the dim calls it off; a single touch does not.
 *
 * Forcing the display off ourselves does not work: with the group still awake, a shell
 * power-off is a temporary override that the next power-state recalculation undoes, and
 * hiding our window is such a recalculation (measured 2026-09-17: back on in 40 ms).
 * Pure state, so it can be tested without a display.
 */
class DoubleTapSleep {

    var dimmedAt: Long? = null
        private set

    val dimming: Boolean get() = dimmedAt != null

    /** A double tap on a lit panel: begin the dim. False if one was already running. */
    fun doubleTap(now: Long): Boolean {
        if (dimming) return false
        dimmedAt = now
        return true
    }

    fun reset() {
        dimmedAt = null
    }

    companion object {
        /** the window's brightness while dimmed, on the 0..1 override scale */
        const val DIM_BRIGHTNESS = 0.12f
        /** how much of the content the veil hides, so the dim reads even where the brightness override is ignored */
        const val VEIL_ALPHA = 0.3f
    }
}
