package dev.rgarjoaba.claudestatus.data

enum class WindowKind(val wire: String) {
    SESSION("session"),
    WEEKLY_ALL("weekly_all"),
    WEEKLY_SCOPED("weekly_scoped");

    companion object {
        fun fromWire(s: String?): WindowKind? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * @param utilization 0f..1f — already divided by 100. Never the raw wire percent.
 * @param resetsAt epoch millis, or null when the window is unused and has nothing to reset.
 */
data class UsageWindow(
    val kind: WindowKind,
    val label: String,
    val utilization: Float,
    val resetsAt: Long?,
    /**
     * The API's `is_active`: whether this is the limit currently BINDING the account —
     * the one that will stop you first — not whether the window is counting.
     *
     * That distinction was established the hard way. It was first read as "this window
     * is running" and rendered as an IDLE tag, which was wrong: measured live, a
     * session window with percent=5 and a live resets_at reported is_active=false while
     * every window was plainly accruing. In the same response the ONLY window flagged
     * active was weekly_all at percent=41 — the highest of the three. So it marks the
     * governing limit, and an inactive window is emphatically not an unused one.
     *
     * Nothing renders this today. It is kept because "which limit is about to bite" is
     * genuinely worth showing and the field is free — it arrives in a response the app
     * already fetches. Defaults true so a response omitting it behaves as before.
     */
    val isActive: Boolean = true,
)

data class UsageSnapshot(
    val windows: List<UsageWindow>,
    val fetchedAt: Long,
)
