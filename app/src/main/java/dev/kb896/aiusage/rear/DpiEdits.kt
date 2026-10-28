package dev.kb896.aiusage.rear

/**
 * The DPI nudges made on the back, between the tap and the config agreeing (pure; the shell
 * holds one under a lock of its own).
 *
 * The shell cannot write the config: it shows and applies each nudge at once, and hands the
 * app's latest value to the main process only once the taps have stopped for [delayMs]
 * ([due]), so a run of taps is one config write. Until a payload comes back carrying that
 * value, an older payload (the minute's usage push, say) must not undo the nudge on the
 * back: [overlay] lays the edits over the payload's map. An edit is dropped once the payload
 * agrees with it, or [holdMs] after it was handed on with no payload agreeing — the main
 * process never saved it, or the front changed it since, and the config wins.
 *
 * A value of null is "Default": the app's entry removed.
 */
class DpiEdits(private val delayMs: Long = DELAY_MS, private val holdMs: Long = HOLD_MS) {

    private class Edit(val dpi: Int?, val at: Long, var sentAt: Long = -1L)

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

    /** When the next [due] has something, or null when nothing waits. */
    fun nextDueAt(): Long? = edits.values.filter { it.sentAt < 0 }.minOfOrNull { it.at + delayMs }

    /**
     * [stored] (a payload's portalDpi) with the edits laid over it. Edits the payload
     * agrees with, and handed-on ones older than [holdMs], are dropped here.
     */
    fun overlay(stored: Map<String, Int>, nowMs: Long): Map<String, Int> {
        val out = stored.toMutableMap()
        val it = edits.entries.iterator()
        while (it.hasNext()) {
            val (pkg, e) = it.next()
            val sent = e.sentAt >= 0
            if (sent && (stored[pkg] == e.dpi || nowMs - e.sentAt > holdMs)) { it.remove(); continue }
            if (e.dpi == null) out.remove(pkg) else out[pkg] = e.dpi
        }
        return out
    }

    companion object {
        /** Taps closer than this are one change. */
        const val DELAY_MS = 600L
        /** How long a handed-on edit outlives payloads that do not carry it. */
        const val HOLD_MS = 10_000L

        /** [map] with the main process's [dpi] for [pkg]: 0 (or less) removes it, else clamped. */
        fun applied(map: Map<String, Int>, pkg: String, dpi: Int): Map<String, Int> =
            if (dpi <= 0) map - pkg else map + (pkg to RearDensity.clamp(dpi))
    }
}
