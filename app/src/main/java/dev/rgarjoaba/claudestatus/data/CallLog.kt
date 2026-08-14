package dev.rgarjoaba.claudestatus.data

/**
 * The last few requests actually made to Anthropic, across both accounts.
 *
 * Exists because the panel's own rendering cannot distinguish "fetching fine" from
 * "hasn't fetched in an hour" — a grey account says something is wrong, not what or
 * when. Diagnosing the work account's 429 meant reading logcat over adb, which is not
 * available to someone holding the phone.
 *
 * Deliberately small and in-memory: this is a debugging aid, not an audit trail. It
 * records outcomes only — never a token, never a response body.
 */
object CallLog {

    data class Entry(val atMillis: Long, val account: String, val outcome: String)

    private const val CAPACITY = 5
    private val entries = ArrayDeque<Entry>(CAPACITY)

    @Synchronized
    fun record(account: Account, outcome: String) {
        if (entries.size == CAPACITY) entries.removeFirst()
        entries.addLast(Entry(System.currentTimeMillis(), account.slug, outcome))
    }

    /** Newest first. */
    @Synchronized
    fun recent(): List<Entry> = entries.reversed()
}
