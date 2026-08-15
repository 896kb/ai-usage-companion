package dev.rgarjoaba.claudestatus.service

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
    // Vestigial while always-on is enabled, but kept correct rather than deleted: if
    // FLAG_KEEP_SCREEN_ON is ever lifted, a sleeping panel should back off further
    // still. The fast < slow invariant is what CadenceTest pins.
    const val SLOW_MS = 900_000L     // panel off

    fun forPanelState(panelOn: Boolean): Long = if (panelOn) FAST_MS else SLOW_MS
}
