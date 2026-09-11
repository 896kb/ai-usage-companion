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
    /** Five minutes between weather readings while the panel is awake. */
    const val WEATHER_MS = 300_000L
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
    // One interval, always — while the panel is awake.
    //
    // WHAT CHANGED SINCE THAT WAS WRITTEN. Both objections above were about a SLOW
    // interval, and both still stand: panelOn() was a poor signal, and fifteen-minute-old
    // numbers are wrong for notifications that matter in a pocket. Neither is an argument
    // for polling a panel nobody can see.
    //
    // So there is still one interval and no second one. What there is now is a gate: the
    // loop waits on the panel's own doze state before each tick and asks for nothing at
    // all while it is dark, then asks immediately when it comes back. The signal is the
    // panel telling us rather than us inferring it from a display that MIUI powers down
    // on its own schedule, which is what made panelOn() untrustworthy.
    //
    // The cost is real and was accepted deliberately: a limit notification cannot arrive
    // while the panel has been dark, because nothing is being fetched to notice it with.
    fun forPanelState(panelOn: Boolean): Long = FAST_MS
}
