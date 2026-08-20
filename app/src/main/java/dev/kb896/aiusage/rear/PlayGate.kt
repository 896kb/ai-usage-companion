package dev.kb896.aiusage.rear

/**
 * Decides when the panel should believe the player about whether it is playing.
 *
 * Skipping a track is not one event. Press next and the player reports the old track
 * stopping, then a gap with nothing playing while the new one loads, then playing again —
 * three states in a few hundred milliseconds. A glyph driven by every report flicked from
 * pause to play and back on every press of next or previous, which reads as the button
 * failing rather than as the player working.
 *
 * So a report is not a verdict. A state has to hold still for [settleMs] before it counts,
 * and a state that reverts within that window never counts at all — the glyph simply never
 * hears about the gap.
 *
 * The cost is honest and it is the point: a real pause takes half a second to show. The
 * button has already been pressed by then, and one that answers deliberately beats one
 * that twitches under the finger.
 *
 * Pure and stateful rather than a function, because the whole behaviour is about a
 * sequence of reports over time — which is exactly what cannot be checked by looking at
 * any one of them.
 */
internal class PlayGate(private val settleMs: Long) {

    /** What the panel should be showing. Changes only through [commit]. */
    var shown = false
        private set

    private var reported = false
    private var reportedAt = 0L

    /** What the player just said, and when. */
    fun report(playing: Boolean, nowMs: Long) {
        if (playing == reported) return
        reported = playing
        reportedAt = nowMs
    }

    /**
     * Take a verdict if one is due. Returns true when [shown] changed.
     *
     * Called every frame rather than scheduled: the panel is already on a frame clock,
     * and a timer would be a second thing to cancel when the row goes away.
     */
    fun commit(nowMs: Long): Boolean {
        if (reported == shown) return false
        if (nowMs - reportedAt < settleMs) return false
        shown = reported
        return true
    }

    /** Whether a verdict is pending, so the caller knows to keep redrawing. */
    fun waiting(): Boolean = reported != shown

    /** What the player last said, believed or not. */
    val latest: Boolean get() = reported

    /**
     * Believe the current report at once, skipping the settle.
     *
     * For a state the panel ASKED FOR. The settle exists because a report on its own is
     * not evidence of anything — but a report that matches the command just sent is: the
     * player is answering a question this panel put to it. Waiting half a second to
     * believe the answer to your own button press is the lag the settle was supposed to
     * prevent, applied to the one case that never needed it.
     */
    fun accept(): Boolean {
        if (reported == shown) return false
        shown = reported
        return true
    }

    /**
     * Adopt a state with no settling at all.
     *
     * For the row arriving: there is nothing on screen to flicker yet, and settling would
     * show the wrong glyph for the first half second of every appearance.
     */
    fun snap(playing: Boolean) {
        reported = playing
        shown = playing
        reportedAt = 0L
    }
}
