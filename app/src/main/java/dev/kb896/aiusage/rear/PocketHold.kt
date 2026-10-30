package dev.kb896.aiusage.rear

/**
 * Always-on's pocket mode, between letting go of the display and the display going dark.
 *
 * The rear proximity sensor covered for a moment lets the hold go, and HyperOS powers the
 * rear down on its own timeout, ten seconds later. The sensor used to stop being watched
 * the moment the hold was let go, so a phone taken out of the pocket inside those ten
 * seconds went dark in the hand — and with always-on swallowing every touch but the
 * slider's, nothing short of the swipe could stop it. Now the watch lasts until the
 * display is actually dark, and an uncovered reading before then takes the hold back.
 *
 * [covered] is the last reading, kept so that something else taking the hold back (a
 * notification, [heldAgain]) can start the pocket's countdown over without waiting for a
 * reading that a sensor reporting on change will never send. Pure, so it can be tested
 * without a sensor.
 */
class PocketHold {
    var covered = false
        private set

    /** The hold has been let go for the pocket, and the display is not dark yet. */
    var released = false
        private set

    /** A reading: true if it takes the hold back — out of the pocket before the display went dark. */
    fun reading(covered: Boolean): Boolean {
        this.covered = covered
        if (covered || !released) return false
        released = false
        return true
    }

    /** In a pocket: the hold is let go. */
    fun letGo() {
        released = true
    }

    /** Something else took the hold back. */
    fun heldAgain() {
        released = false
    }

    /** Not watching any more — the display dark, the switch off, always-on left. */
    fun reset() {
        covered = false
        released = false
    }
}
