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
)

data class UsageSnapshot(
    val windows: List<UsageWindow>,
    val fetchedAt: Long,
)
