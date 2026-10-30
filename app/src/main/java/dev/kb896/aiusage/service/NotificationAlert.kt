package dev.kb896.aiusage.service

/**
 * Whether a notification may LIGHT the back, only reach a back already lit, or not reach it.
 *
 * Every clearable post used to wake the rear display. On a phone in a pocket that is the
 * back lighting up for things the front itself never sounded for: a channel the user turned
 * down to silent, a message Do Not Disturb was holding, a progress tick posted with
 * FLAG_ONLY_ALERT_ONCE — and, the one that was actually seen, a paused song: a player drops
 * FLAG_ONGOING when it pauses, so its next post read as news, and its title was a sentence.
 *
 * The rule is the front's own. The back wakes for exactly what would make the front alert —
 * the ranking's importance DEFAULT or higher (it makes a sound) and the interruption filter
 * letting it through (Do Not Disturb) — and not for an alert-once update of something
 * already seen, which the front does not alert for either. Everything else that survives the
 * relay's filters is still a CARD: sent to the panel, which keeps it when it is lit (someone
 * is looking) and drops it when dark, so nothing quiet is lost from a screen in use.
 *
 * Media is DROPPED, card and all. The panel has its own media page fed from the session
 * itself (MediaWatcher); a player's notification as a card is the same song twice.
 */
object NotificationAlert {

    enum class Verdict {
        /** Never relayed. */
        DROP,
        /** Relayed; the panel keeps it only if it is lit. Never wakes. */
        CARD,
        /** Light the back (and lock it if it was dark), then relay. */
        WAKE,
    }

    /** NotificationManager.IMPORTANCE_DEFAULT: the first level that makes a sound. */
    private const val IMPORTANCE_DEFAULT = 3

    /**
     * [importance] and [matchesFilter] are the ranking's, null when there was none to read —
     * which is no wake: a back left dark is the safe way to be wrong in a pocket. [seenBefore]:
     * the key was posted before (see [SeenKeys]), ongoing or not.
     */
    fun verdict(
        media: Boolean,
        importance: Int?,
        matchesFilter: Boolean?,
        onlyAlertOnce: Boolean,
        seenBefore: Boolean,
    ): Verdict {
        if (media) return Verdict.DROP
        val alerts = importance != null && importance >= IMPORTANCE_DEFAULT &&
            matchesFilter == true &&
            !(onlyAlertOnce && seenBefore)
        return if (alerts) Verdict.WAKE else Verdict.CARD
    }

    /**
     * The keys posted so far, newest last, at most [max] of them.
     *
     * Remembered from a key's FIRST post, before any filter, and that ordering is the point:
     * the relay drops ongoing posts, so a key remembered only once relayed would have no
     * history at the moment it matters — a player's first post after it pauses, the first
     * one that is not ongoing, would read as new. Forgotten when the notification goes, so
     * the same key posted afresh later is new again. Bounded, since an app can mint keys
     * without end; the oldest goes first. Not thread-safe: the relay's callbacks all arrive
     * on its main thread.
     */
    class SeenKeys(private val max: Int = 256) {
        private val keys = LinkedHashSet<String>()

        /** True if [key] was seen before this; it is seen (and newest) from now either way. */
        fun see(key: String): Boolean {
            val was = keys.remove(key)
            keys.add(key)
            while (keys.size > max) keys.remove(keys.first())
            return was
        }

        fun forget(key: String) {
            keys.remove(key)
        }

        /** What is on the front already when the relay (re)connects has been seen. */
        fun seed(active: Collection<String>) {
            for (k in active) see(k)
        }
    }
}
