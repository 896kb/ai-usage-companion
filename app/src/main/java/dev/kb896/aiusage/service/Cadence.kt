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
    // 60s, at the user's explicit direction, so the mascot's burst rule can actually
    // see a minute-scale change — at 5 minutes the earliest a burst could be noticed
    // was the next poll, which made the rule far less responsive than specified.
    //
    // The panel is always-on, so this is the ONLY interval that ever applies:
    // FLAG_KEEP_SCREEN_ON means panelOn() is permanently true. That makes it ~1,440
    // requests per account per day against the very endpoint that reports rate limits.
    //
    // The concern this was raised against is bunching, not rate: both 429s this app has
    // taken came from BURSTS — every service restart fetching immediately, and a
    // DisplayManager listener firing on brightness changes — not from a steady cadence.
    // A request a minute is regular and predictable. The protections that actually
    // matter are unchanged: the per-account backoff on 429 (5min doubling to 60min) and
    // UsageRepository's floor, which still refuses anything faster than this.
    const val FAST_MS = 60_000L      // panel on
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
