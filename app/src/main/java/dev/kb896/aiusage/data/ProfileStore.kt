package dev.kb896.aiusage.data

import android.content.Context

/**
 * Per-account plan tier, cached on disk.
 *
 * Plain SharedPreferences, no encryption, unlike TokenStore: a plan bracket is not a
 * credential. It is deliberately the ONLY thing kept from /api/oauth/profile — see
 * ProfileApi for why the rest of that response is not welcome on an outward-facing
 * panel.
 *
 * Cached for a week because the value changes on the order of months, and every avoided
 * request is one that cannot contribute to the bunching that has twice throttled this
 * app's accounts. Surviving restarts matters for the same reason: re-fetching on every
 * process start would reintroduce exactly the burst pattern that caused those 429s.
 */
class ProfileStore(private val context: Context) {

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tier(account: Account): String? = prefs.getString(key(account), null)

    /**
     * Whether it is time to ask again. Keyed on the ATTEMPT timestamp, not on whether a
     * value is present.
     *
     * Those differ, and the difference is the whole point of [markAttempted]: an
     * account whose profile lookup fails has no cached tier, so a "no value means
     * stale" rule would re-ask on every single refresh, forever, for a decorative
     * field. Timestamping the attempt makes a failure cost one request per week rather
     * than one per poll. A missing stamp reads as epoch, so the first call is always
     * stale.
     */
    fun isStale(account: Account, now: Long): Boolean =
        now - prefs.getLong(stampKey(account), 0L) > MAX_AGE_MS

    /**
     * Who the account belongs to, for the settings screen.
     *
     * Stored beside the tier because it comes from the same response and has the same
     * lifetime. Deliberately NOT part of DashboardState: identity must never travel in
     * the payload, because that payload is what the outward-facing panel draws from.
     */
    /**
     * True when the cache holds a tier but no identity.
     *
     * That is not a rare edge: it is every install that cached a tier before identity
     * was extracted, and the cache lasts a week — so without this the settings screen
     * would show a plan and nothing else for six more days, looking like the fetch had
     * silently failed.
     */
    fun needsIdentity(account: Account): Boolean =
        tier(account) != null && identity(account).toList().all { it == null }

    fun identity(account: Account): Triple<String?, String?, String?> = Triple(
        prefs.getString("name_${account.slug}", null),
        prefs.getString("email_${account.slug}", null),
        prefs.getString("org_${account.slug}", null),
    )

    fun saveIdentity(account: Account, name: String?, email: String?, org: String?) =
        prefs.edit()
            .putString("name_${account.slug}", name)
            .putString("email_${account.slug}", email)
            .putString("org_${account.slug}", org)
            .apply()

    fun save(account: Account, tier: String, now: Long) = prefs.edit()
        .putString(key(account), tier)
        .putLong(stampKey(account), now)
        .apply()

    /**
     * Records a failed lookup so it is not retried on every poll.
     *
     * Without this, an account whose profile endpoint refuses (an expired scope, an
     * org that does not expose it) would be asked again every single refresh forever —
     * a permanent extra request per cycle for a field that is decorative. The timestamp
     * moves without a value, so the next attempt waits out the full cache window.
     */
    fun markAttempted(account: Account, now: Long) = prefs.edit()
        .putLong(stampKey(account), now)
        .apply()

    private fun key(a: Account) = "tier_${a.slug}"
    private fun stampKey(a: Account) = "tier_at_${a.slug}"

    companion object {
        private const val PREFS = "profile"
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
