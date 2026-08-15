package dev.rgarjoaba.claudestatus.data

/**
 * Decides when a limit deserves a notification. Pure logic, no Android types, so the
 * rules are unit-testable — the alerting is the part that must not misfire, because a
 * warning that cries wolf gets muted and then the real one is missed too.
 *
 * Two bands. A single informational milestone at [HALF_PERCENT], then a warning at
 * [FLOOR_PERCENT] and one per [STEP_PERCENT] after it: 50, then 90, 92, 94, 96, 98, 100.
 *
 * Both share one bucket sequence deliberately, and it is monotonic — 50 < 90 < 92 …
 * That is what lets a single "have I already warned above this?" comparison serve both
 * bands, and what makes the re-arm rule work: falling under 50 is the only thing that
 * clears the history, so a window that resets warns again from scratch while one that
 * merely wobbles between 91% and 93% stays quiet.
 */
object LimitAlerts {

    /** Informational halfway mark. Fires once per cycle, not every 2%. */
    const val HALF_PERCENT = 50
    const val FLOOR_PERCENT = 90
    const val STEP_PERCENT = 2

    data class Alert(
        val account: Account,
        val kind: WindowKind,
        val label: String,
        val percent: Int,
        val resetsAt: Long?,
        /** Which band raised this — [HALF_PERCENT] or a warning step. Drives wording. */
        val bucket: Int,
    ) {
        /** The halfway milestone reads as news; everything else reads as a warning. */
        val isMilestone: Boolean get() = bucket == HALF_PERCENT

        /** Stable per account+window, so a later alert for the same limit replaces the
         *  earlier one in the shade instead of stacking six warnings about one bar. */
        val key: String get() = "${account.slug}:${kind.wire}"
    }

    data class Outcome(val alerts: List<Alert>, val thresholds: Map<String, Int>)

    /**
     * @param previous last-notified bucket per key, from durable storage
     * @return the alerts to raise now, and the thresholds to persist
     *
     * Only [Health.OK] accounts are considered. A degraded account keeps showing its
     * last good numbers by design, and alerting on those would fire a warning about a
     * reading that may be hours old — at exactly the moment the app has already lost
     * confidence in it.
     */
    fun evaluate(state: DashboardState, previous: Map<String, Int>): Outcome {
        val alerts = ArrayList<Alert>()
        val next = HashMap(previous)

        for (status in state.statuses) {
            if (status.health != Health.OK) continue
            val snapshot = status.snapshot ?: continue
            for (w in snapshot.windows) {
                val key = "${status.account.slug}:${w.kind.wire}"
                val percent = (w.utilization * 100).toInt()
                val bucket = bucketFor(percent)

                if (bucket == null) {
                    // Back under the halfway mark — the window reset, or usage was
                    // recalculated downward. Forget the history so the NEXT climb warns
                    // again from scratch rather than staying silent forever after one
                    // cycle.
                    next.remove(key)
                    continue
                }
                val already = previous[key] ?: 0
                if (bucket <= already) continue

                next[key] = bucket
                alerts += Alert(status.account, w.kind, w.label, percent, w.resetsAt, bucket)
            }
        }
        return Outcome(alerts, next)
    }

    /**
     * The alert bucket a percentage falls into, or null below [HALF_PERCENT].
     *
     * Everything from 50 to 89 collapses into the single 50 bucket — the halfway mark is
     * one piece of news, not the start of a running commentary.
     *
     * Above the floor it rounds DOWN to the step so each band fires once: 90 and 91 are
     * both the 90 bucket, 92 and 93 the 92 bucket. Rounding up would let a single point
     * of drift (93 -> 94) fire two alerts for what is effectively one crossing.
     */
    fun bucketFor(percent: Int): Int? {
        val capped = percent.coerceAtMost(100)
        if (capped < HALF_PERCENT) return null
        if (capped < FLOOR_PERCENT) return HALF_PERCENT
        return FLOOR_PERCENT + ((capped - FLOOR_PERCENT) / STEP_PERCENT) * STEP_PERCENT
    }

    /** "5-hour" / "7-day" — the window's DURATION, which its label does not always say.
     *  A scoped window is labelled with a model name (FABLE), so without this an alert
     *  about it would name no time period at all. */
    fun windowName(kind: WindowKind): String = when (kind) {
        WindowKind.SESSION -> "5-hour"
        WindowKind.WEEKLY_ALL -> "7-day"
        WindowKind.WEEKLY_SCOPED -> "7-day"
    }
}
