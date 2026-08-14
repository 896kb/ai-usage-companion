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
    // The panel is always-on at the user's request, so this is now the ONLY interval
    // that ever applies — FLAG_KEEP_SCREEN_ON means panelOn() is permanently true.
    // At 60s that was 1,440 requests per account per day, polling the very endpoint
    // that reports rate limits. Rate-limit windows move by a fraction of a percent
    // per minute, so 5 minutes shows the same thing for a fifth of the traffic.
    const val FAST_MS = 300_000L     // panel on
    // Vestigial while always-on is enabled, but kept correct rather than deleted: if
    // FLAG_KEEP_SCREEN_ON is ever lifted, a sleeping panel should back off further
    // still. The fast < slow invariant is what CadenceTest pins.
    const val SLOW_MS = 900_000L     // panel off

    fun forPanelState(panelOn: Boolean): Long = if (panelOn) FAST_MS else SLOW_MS
}
