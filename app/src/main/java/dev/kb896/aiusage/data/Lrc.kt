package dev.kb896.aiusage.data

/**
 * A parsed LRC lyric sheet: timestamps and the line sung at each.
 *
 * Pure, and deliberately so. Everything about lyrics that can be got wrong quietly —
 * which line is current at a given millisecond, what happens in the gap before the first
 * one, whether a blank line means "nothing" or "no data" — lives here, where it can be
 * tested without a device, a network or a player.
 *
 * The format is the de-facto one every lyrics source emits:
 *
 * ```
 * [ti:Title]
 * [00:12.00]The first line
 * [00:17.20]The second
 * [00:21.10][01:03.50]A line sung twice
 * [00:25.00]
 * ```
 *
 * Two details of it are load-bearing:
 *
 * A line may carry SEVERAL timestamps, for a refrain sung more than once. It is one
 * lyric at several moments, and is stored here as several entries — the panel asks "what
 * now", never "when is this line", so there is nothing to gain by keeping the sharing.
 *
 * A timestamp with NO TEXT is not padding. It marks where the previous line stops being
 * sung, which is how an instrumental break is written down. Dropping those — the obvious
 * tidy-up — leaves the last line before a solo sitting on the panel for the whole solo.
 */
class Lrc(val lines: List<Line>) {

    /** One lyric at one moment. [text] may be empty; see the note above. */
    class Line(val atMs: Long, val text: String)

    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Which line is being sung at [positionMs], or -1 before the first one.
     *
     * -1 IS A REAL ANSWER rather than a failure. Most tracks open with several seconds of
     * music before anyone sings, and the honest reading of that moment is that there is no
     * current line — not the first line, which has not been sung yet, and not a blank,
     * which would waste the one place the panel can say what is coming.
     *
     * Binary search because this is asked on every frame, sixty times a second, while a
     * long track can carry a couple of hundred entries.
     */
    fun indexAt(positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].atMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** The line at [index], or null if there is none — including for the -1 of [indexAt]. */
    fun lineAt(index: Int): String? = lines.getOrNull(index)?.text

    companion object {

        /** An empty sheet: a track with no lyrics, told apart from one not yet looked up. */
        val NONE = Lrc(emptyList())

        /**
         * Everything inside a leading pair of brackets, and whatever follows the last one.
         *
         * Anchored at the start: a bracket in the middle of a line is part of the lyric —
         * `[Chorus]`, or a bracketed aside — and must survive as text.
         */
        // Every ] escaped: the phone's regex is ICU's, stricter than the JVM's (IcuRegexTest).
        private val LEADING = Regex("""^((?:\[[^\]]*\])+)(.*)$""")
        private val TAG = Regex("""\[([^\]]*)\]""")

        /**
         * `mm:ss`, with an optional fraction. Minutes are not capped at 59: a
         * ninety-minute mix is written `[90:00.00]` rather than as hours, and a
         * `\d{1,2}` here would silently drop the back half of one.
         */
        private val STAMP = Regex("""^(\d{1,4}):([0-5]?\d)(?:[.:](\d{1,3}))?$""")

        /**
         * Parse an LRC sheet. Anything unrecognised is skipped rather than rejected —
         * sheets are community-written and a stray line should cost that line, not the song.
         *
         * The `[offset:…]` tag is deliberately ignored. Its sign is documented both ways
         * in the wild, so honouring it is a coin-flip between correcting a sheet and
         * doubling its error, and the source used here does not emit it.
         */
        fun parse(text: String): Lrc {
            val out = ArrayList<Line>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val m = LEADING.find(line) ?: continue
                val body = m.groupValues[2].trim()
                for (tag in TAG.findAll(m.groupValues[1])) {
                    val at = stampMs(tag.groupValues[1]) ?: continue
                    out.add(Line(at, body))
                }
            }
            // Sorted because a sheet is not required to be, and a refrain's second
            // timestamp always arrives out of order by construction — it is written on the
            // line where the refrain FIRST appears.
            out.sortBy { it.atMs }
            return Lrc(out)
        }

        /** A single bracket's contents as a time, or null if it is a metadata tag. */
        private fun stampMs(tag: String): Long? {
            val m = STAMP.find(tag.trim()) ?: return null
            val minutes = m.groupValues[1].toLong()
            val seconds = m.groupValues[2].toLong()
            val frac = m.groupValues[3]
            // A fraction is read by its LENGTH, not as a fixed number of places: `.5` is
            // half a second and `.05` is a twentieth of one. Treating both as centiseconds
            // is the classic way to put a sheet 450ms out.
            val millis = when (frac.length) {
                0 -> 0L
                1 -> frac.toLong() * 100L
                2 -> frac.toLong() * 10L
                else -> frac.toLong()
            }
            return (minutes * 60L + seconds) * 1000L + millis
        }
    }
}
