package dev.rgarjoaba.claudestatus.service

/**
 * How long until a window resets, in words.
 *
 * Shared because two surfaces now say it — the limit warnings and the HyperIsland pill —
 * and the same reset reading phrased two ways in two places on the same screen reads as
 * two different facts.
 */
internal object ResetClock {

    /** "resets in 2h 14m". Long form, for a notification body with room for it. */
    fun phrase(resetsAt: Long?, now: Long = System.currentTimeMillis()): String {
        if (resetsAt == null) return "no reset time reported"
        val ms = resetsAt - now
        if (ms <= 0) return "resetting now"
        val minutes = ms / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "resets in ${days}d ${hours % 24}h"
            hours > 0 -> "resets in ${hours}h ${minutes % 60}m"
            else -> "resets in ${minutes}m"
        }
    }

    /**
     * "2h14" — the same reading with every character earned, for the collapsed island
     * pill, which is a few characters wide and gets truncated rather than wrapped.
     */
    fun short(resetsAt: Long?, now: Long = System.currentTimeMillis()): String? {
        if (resetsAt == null) return null
        val ms = resetsAt - now
        if (ms <= 0) return "now"
        val minutes = ms / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d${hours % 24}"
            hours > 0 -> "${hours}h${(minutes % 60).toString().padStart(2, '0')}"
            else -> "${minutes}m"
        }
    }
}
