package dev.rgarjoaba.claudestatus.data

import android.content.Context

/**
 * A short trail of each account's 5-hour utilisation, so MascotMood can ask "how much
 * has been burned in the last ten minutes?".
 *
 * Persisted, because the poll service restarts often — on every install, on memory
 * pressure, whenever MIUI feels like it — and an in-memory trail would reset each time.
 * A reset trail is not neutral: it looks exactly like "no recent burn", so the lifting
 * animation would stop on every restart and only return ten minutes later.
 *
 * Stored as a compact "t:v,t:v" string rather than one preference per sample. The trail
 * is short and written on every poll; a growing key set would be worse to clean up than
 * to re-serialise.
 */
class UsageHistory(private val context: Context) {

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Appends a sample and drops anything older than [MAX_AGE_MS]. */
    fun record(account: Account, utilization: Float, now: Long) {
        val kept = load(account).filter { now - it.first <= MAX_AGE_MS } + (now to utilization)
        prefs.edit()
            .putString(key(account), kept.joinToString(",") { "${it.first}:${it.second}" })
            .apply()
    }

    /**
     * The most recent sample at least [age] old, or null when the trail does not reach
     * back that far.
     *
     * Deliberately the NEWEST qualifying sample rather than the oldest: with a trail
     * that spans more than the window, the oldest sample would keep drifting further
     * into the past and the measured delta would grow with it, so a single burst would
     * keep the mascot lifting long after the burning stopped.
     */
    fun before(account: Account, age: Long, now: Long): Float? =
        load(account).filter { now - it.first >= age }.maxByOrNull { it.first }?.second

    private fun load(account: Account): List<Pair<Long, Float>> =
        prefs.getString(key(account), null)
            ?.split(',')
            ?.mapNotNull { entry ->
                val parts = entry.split(':')
                val t = parts.getOrNull(0)?.toLongOrNull()
                val v = parts.getOrNull(1)?.toFloatOrNull()
                if (t != null && v != null) t to v else null
            }
            .orEmpty()

    private fun key(a: Account) = "u_${a.slug}"

    private companion object {
        const val PREFS = "usage_history"

        /** Twice the decision window, so a sample old enough to answer always survives
         *  a poll cycle or two of pruning. */
        const val MAX_AGE_MS = 2 * MascotMood.WINDOW_MS
    }
}
