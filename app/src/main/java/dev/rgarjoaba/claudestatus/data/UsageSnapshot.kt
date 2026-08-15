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
     * Whether this window is currently counting. The API's `is_active`.
     *
     * Defaults true so a response that omits the field behaves as it always did. The
     * session window is the one that matters: an account that has not been used has an
     * inactive 5h window with no resets_at, which the panel previously rendered as a
     * bare em dash — indistinguishable from "we failed to read it".
     */
    val isActive: Boolean = true,
)

data class UsageSnapshot(
    val windows: List<UsageWindow>,
    val fetchedAt: Long,
)
