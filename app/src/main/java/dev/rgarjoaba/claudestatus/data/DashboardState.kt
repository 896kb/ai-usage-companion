package dev.rgarjoaba.claudestatus.data

import dev.rgarjoaba.claudestatus.Panel

enum class Account(val slug: String, val label: String, val accent: Int) {
    PERSONAL("personal", "PERSONAL", Panel.Palette.PERSONAL),
    WORK("work", "WORK", Panel.Palette.WORK);

    companion object { fun fromSlug(s: String?) = entries.firstOrNull { it.slug == s } }
}

enum class Health(val wire: String) {
    OK("ok"), STALE("stale"), AUTH("auth"), ERROR("error");
    companion object { fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: ERROR }
}

data class AccountStatus(
    val account: Account,
    val health: Health,
    val snapshot: UsageSnapshot?,
    /** Short plan bracket ("MAX 5X"), or null when unknown. Purely informational. */
    val planTier: String? = null,
    /** Which mascot clip this account's 5h window has earned. See MascotMood. */
    val mascot: String = MascotMood.WALK,
)

data class DashboardState(
    val fetchedAt: Long,
    val statuses: List<AccountStatus>,
) {
    companion object { val EMPTY = DashboardState(0L, emptyList()) }
}
