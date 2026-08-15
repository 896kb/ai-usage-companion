package dev.kb896.aiusage.service

/**
 * How often UsagePollService ticks: fast while the panel is awake so real numbers
 * land on the rear display within a minute, slow while it's off so the always-on
 * service isn't doing full-speed work nobody can see.
 *
 * Kept Android-free (no DisplayManager/Display dependency) so this decision — and
 * only this decision, not the DisplayListener plumbing that feeds it — is unit
 * testable without Robolectric, the same reasoning behind CalibrateMode.isCalibrate
 * and UsageRepository.healthFor.
 */
object Cadence {
    // 120s. 60s was tried and MEASURED: over twelve samples the accounts were stale in
    // seven of them, oscillating in and out of the 429 backoff, while a direct probe of
    // the same endpoint with the same token returned 200 — so it was our rate, not the
    // service. Two minutes still lets the mascot's burst rule see a change within a
    // couple of minutes, which 5 minutes did not.
    //
    // The panel is always-on, so this is the ONLY interval that ever applies:
    // FLAG_KEEP_SCREEN_ON means panelOn() is permanently true. That makes it ~1,440
    // requests per account per day against the very endpoint that reports rate limits.
    //
    // The earlier reasoning — that only BUNCHING causes 429s, never a steady rate — was
    // wrong, and the 60s trial is what showed it. A steady cadence can be too fast on
    // its own. Recorded here because the argument was persuasive and is worth not
    // re-making: two accounts polled every 60s is ~2,880 requests a day against the
    // endpoint that reports rate limits, and that is enough on its own.
    const val FAST_MS = 120_000L     // every account, every two minutes
    // No slow cadence any more. There used to be a 15-minute interval for "panel off",
    // on the theory that nobody is looking. Two problems with that in practice:
    //
    //   * panelOn() reads the REAR display's power state, and MIUI powers that panel
    //     down whenever the phone itself idles — so the app spent most of its time on
    //     the slow interval, polling every fifteen minutes. That is what the user saw.
    //   * the numbers are not only for the panel. The mascot's burst rule and the limit
    //     notifications both need a fresh trail, and both are wanted while the phone is
    //     in a pocket.
    //
    // One interval, always.
    fun forPanelState(panelOn: Boolean): Long = FAST_MS
}
