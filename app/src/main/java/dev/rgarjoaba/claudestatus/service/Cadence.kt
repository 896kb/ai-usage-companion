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
    const val FAST_MS = 60_000L      // panel on
    const val SLOW_MS = 300_000L     // panel off

    fun forPanelState(panelOn: Boolean): Long = if (panelOn) FAST_MS else SLOW_MS
}
