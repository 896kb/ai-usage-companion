package dev.kb896.aiusage.rear

/**
 * "Keep right of the cameras" switched on the back, between the switch and the config agreeing
 * (pure; the shell holds one under its DPI lock). The same trouble as the DPI slider's, so
 * DpiEdits' own rules: a payload older than the save must not undo the switch — here that would
 * relaunch the app straight back where it came from — and one the app never saved gives way to
 * the config after its hold. Handed on at once ([due]): a switch is not a run of taps.
 */
class ConfinedEdits {

    private val edits = DpiEdits(delayMs = 0L)

    fun put(pkg: String, on: Boolean, nowMs: Long) = edits.put(pkg, if (on) ON else null, nowMs)

    /** What to hand on now, once each. */
    fun due(nowMs: Long): Map<String, Boolean> = edits.due(nowMs).mapValues { it.value != null }

    /** [pkg]'s switch to [on], handed on by [due], did not get there: due again. */
    fun unsend(pkg: String, on: Boolean) = edits.unsend(pkg, if (on) ON else null)

    /** [stored] (a payload's portalConfined) with the switches laid over it. */
    fun overlay(stored: Set<String>, nowMs: Long): Set<String> =
        edits.overlay(stored.associateWith { ON }, nowMs).keys

    private companion object {
        /** DpiEdits' value for "on the list": anything but null. */
        const val ON = 1
    }
}
