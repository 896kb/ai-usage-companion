package dev.kb896.aiusage.data

import dev.kb896.aiusage.Panel

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
)

data class DashboardState(
    val fetchedAt: Long,
    val statuses: List<AccountStatus>,
) {
    companion object { val EMPTY = DashboardState(0L, emptyList()) }
}
