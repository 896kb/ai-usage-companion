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
    /** Short plan bracket ("MAX 5X"), or null when unknown. Purely informational. */
    val planTier: String? = null,
    /** Which mascot clip this account's 5h window has earned. See MascotMood. */
    val mascot: String = MascotMood.WALK,
    /**
     * Whether a credential is stored for the account at all. False means never connected
     * or signed out — not expired, which keeps its credential and is AUTH with this true.
     * The panel leaves an account with no credential out of the readout altogether.
     */
    val connected: Boolean = true,
)

data class DashboardState(
    val fetchedAt: Long,
    val statuses: List<AccountStatus>,
) {
    companion object { val EMPTY = DashboardState(0L, emptyList()) }
}
