package dev.kb896.aiusage.service

/**
 * Pocket mode's decisions: when to switch the back off, and when to bring it back.
 *
 * It used to be always-on's alone, and it let go of the display rather than switching it
 * off: covered for a second and a half, the panel dropped its hold and HyperOS powered the
 * rear down on its own ten-second timeout — eleven and a half seconds of a lit back in a
 * pocket, taking touches through the fabric for most of them. Now the back is switched off
 * the moment the cover has lasted [debounceMs] (the shell sleeps the rear's power group on
 * its own, PocketWatch), whatever is on it, and coming out of the pocket switches it back on.
 *
 * ONLY A BACK POCKET MODE SWITCHED OFF IS SWITCHED BACK ON. A back already dark when it
 * was covered — the double tap, the timeout, the power button — is nobody's business here:
 * it is not watched at all while it is dark ([watching]), and taking it out of a pocket
 * must not light it. The same goes for a back that goes dark for one of those reasons in
 * the middle of the count.
 *
 * Lit again by something else while still covered (the front's "Show on the back"), the
 * cover counts from the light and the back goes off again; [pocketed] stays, so coming out
 * still restores.
 *
 * The debounce is a passing finger, or a hand brushed over the lens on the way to a
 * button: a cover shorter than that is not a pocket.
 *
 * Pure, and times are the caller's clock, so it can be tested without a sensor.
 */
class PocketMode(private val debounceMs: Long) {

    enum class Act {
        NONE,
        /** Switch the back off now — already counted as [pocketed]. */
        SLEEP,
        /** Out of the pocket: switch the back on, the slider up. */
        RESTORE,
    }

    /** The last reading. */
    var covered = false
        private set

    /** The back is off because of the pocket (or is being switched off for it). */
    var pocketed = false
        private set

    /** The back is lit — whoever lit it. */
    private var lit = false

    /** When the current cover began counting; -1 while there is nothing to count. */
    private var coveredSince = -1L

    /**
     * Whether the sensor is wanted: lit, to see a pocket coming, or switched off for one, to
     * see the way out. A back dark for any other reason is not watched — and a reading it
     * never gets cannot light it.
     */
    val watching: Boolean get() = lit || pocketed

    /** The back lit up or went dark. */
    fun lit(on: Boolean, now: Long) {
        if (on == lit) return
        lit = on
        if (on) {
            // A cover already there counts from the light, not from before it.
            coveredSince = if (covered) now else -1L
            return
        }
        coveredSince = -1L
        // Dark, and not for the pocket: the watch stops, and its last word goes with it — a
        // new watch hears the sensor again as it starts.
        if (!pocketed) covered = false
    }

    /** A reading from the sensor. */
    fun reading(covered: Boolean, now: Long): Act {
        val was = this.covered
        this.covered = covered
        if (covered) {
            // A sensor may say "covered" again; the count runs from the first.
            if (lit && (!was || coveredSince < 0)) coveredSince = now
            return Act.NONE
        }
        coveredSince = -1L
        if (!pocketed) return Act.NONE
        pocketed = false
        return Act.RESTORE
    }

    /** When [due] has something to decide, or -1. */
    fun dueAt(): Long = if (lit && covered && coveredSince >= 0) coveredSince + debounceMs else -1L

    /**
     * The count's end. [lettingGo]: the panel has let go of the display already and the back
     * is on its way to dark on its own (the idle sleep, the double tap's dim, a spent lock) —
     * switching it off now would make it something to bring back, which it was not going to
     * be. Marked [pocketed] at once, not when the shell says it is done, so a way out of the
     * pocket arriving in between still brings the back back.
     */
    fun due(now: Long, lettingGo: Boolean): Act {
        val at = dueAt()
        if (at < 0 || now < at) return Act.NONE
        // The count is over either way: letting go, there is nothing left to count to.
        coveredSince = -1L
        if (lettingGo) return Act.NONE
        pocketed = true
        return Act.SLEEP
    }

    /** The shell would not switch it off (the other build has the back, no shell): no pocket. */
    fun refused() {
        pocketed = false
    }

    /** The switch off, the watch gone: forget the pocket and the cover, light nothing. */
    fun end() {
        covered = false
        pocketed = false
        coveredSince = -1L
    }
}
