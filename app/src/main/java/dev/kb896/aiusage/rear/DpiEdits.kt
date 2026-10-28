package dev.kb896.aiusage.rear

/**
 * The DPI changes made on the back (its slider), between the change and the config agreeing
 * (pure; the shell holds one under a lock of its own).
 *
 * The shell cannot write the config: it shows and applies each change at once, and hands the
 * app's latest value to the main process only once the changes have stopped for [delayMs]
 * ([due]), so a run of them is one config write. Until a payload comes back carrying that
 * value, an older payload (the minute's usage push, say) must not undo the change on the
 * back: [overlay] lays the edits over the payload's map. An edit is dropped [graceMs] after
 * a payload first agrees with it — not on that payload: an older one (pushed before the save,
 * delivered after) can still follow it — or [holdMs] after it was handed on with no payload
 * agreeing: the main process never saved it, or the front changed it since, and the config
 * wins.
 *
 * A value of null is "Default": the app's entry removed.
 */
class DpiEdits(
    private val delayMs: Long = DELAY_MS,
    private val holdMs: Long = HOLD_MS,
    private val graceMs: Long = GRACE_MS,
) {

    private class Edit(val dpi: Int?, val at: Long, var sentAt: Long = -1L, var agreedAt: Long = -1L)

    private val edits = LinkedHashMap<String, Edit>()

    /** [pkg] set to [dpi] (null: default) at [nowMs]; the delay starts again. */
    fun put(pkg: String, dpi: Int?, nowMs: Long) {
        edits[pkg] = Edit(dpi, nowMs)
    }

    /**
     * The edits whose last tap is at least [delayMs] old and not yet handed on, marked
     * handed on at [nowMs]. Each is to be sent once: 0 for null (see IRearEvents).
     */
    fun due(nowMs: Long): Map<String, Int?> {
        val out = LinkedHashMap<String, Int?>()
        for ((pkg, e) in edits) if (e.sentAt < 0 && nowMs - e.at >= delayMs) {
            e.sentAt = nowMs
            out[pkg] = e.dpi
        }
        return out
    }

    /**
     * [pkg]'s edit to [dpi], handed on by [due], did not get there (the app process died
     * under the call, or was gone): due again, so the next listener ([due]) gets it. An edit
     * replaced since is left alone.
     */
    fun unsend(pkg: String, dpi: Int?) {
        val e = edits[pkg] ?: return
        if (e.dpi == dpi && e.sentAt >= 0) { e.sentAt = -1L; e.agreedAt = -1L }
    }

    /** When the next [due] has something, or null when nothing waits. */
    fun nextDueAt(): Long? = edits.values.filter { it.sentAt < 0 }.minOfOrNull { it.at + delayMs }

    /**
     * [stored] (a payload's portalDpi) with the edits laid over it. A handed-on edit is
     * dropped here [graceMs] after the first payload agreeing with it, or [holdMs] after it
     * was handed on.
     */
    fun overlay(stored: Map<String, Int>, nowMs: Long): Map<String, Int> {
        val out = stored.toMutableMap()
        val it = edits.entries.iterator()
        while (it.hasNext()) {
            val (pkg, e) = it.next()
            if (e.sentAt >= 0) {
                if (e.agreedAt < 0 && stored[pkg] == e.dpi) e.agreedAt = nowMs
                val agreedLongAgo = e.agreedAt >= 0 && nowMs - e.agreedAt >= graceMs
                if (agreedLongAgo || nowMs - e.sentAt > holdMs) { it.remove(); continue }
            }
            if (e.dpi == null) out.remove(pkg) else out[pkg] = e.dpi
        }
        return out
    }

    companion object {
        /** Taps closer than this are one change. */
        const val DELAY_MS = 600L
        /** How long a handed-on edit outlives payloads that do not carry it. */
        const val HOLD_MS = 10_000L
        /** How long an edit outlives the first payload that agrees with it. */
        const val GRACE_MS = 3_000L

        /** [map] with the main process's [dpi] for [pkg]: 0 (or less) removes it, else clamped. */
        fun applied(map: Map<String, Int>, pkg: String, dpi: Int): Map<String, Int> =
            if (dpi <= 0) map - pkg else map + (pkg to RearDensity.clamp(dpi))
    }
}
