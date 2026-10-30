package dev.kb896.aiusage.rear

/**
 * HyperOS's list of apps its freezer leaves alone (system setting MILLET_NO_RESTRICT_APP, a
 * comma-and-space string, read by AurogonImmobulusMode.getNoRestrictApps).
 *
 * WHY A GUEST GOES ON IT. The freezer counts an app as visible only with the FRONT screen on:
 * a guest in plain view on the back, front off, was frozen five seconds after every thaw (seen
 * 2026-10-30, Brave: THAW display_on, then FZ "from system" 5 s later, stalls of 20 s and more).
 *
 * WHY IT COMES OFF AGAIN (the owner's rule). Nothing stays changed: an app is on the list only
 * while it is on the back, and only what this added comes off, so an app the person had set to
 * "no restrictions" themselves keeps that. What was added is kept in a marker of its own, as
 * the density's is, so a service that died with a guest up is cleaned up after at its next start.
 *
 * Pure, so the rule can be tested without a phone: [step] reads the list as it is NOW every
 * time, and a person changing it meanwhile is respected.
 */
object NoRestrictList {

    /** [list]: what to write, null for nothing. [ours]: the marker's new contents. */
    data class Step(val list: String?, val ours: List<String>)

    fun parse(raw: String?): List<String> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "null") return emptyList()
        return s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun marker(ours: List<String>): String = ours.joinToString(",")

    /** [raw] the list now, [ours] what we added and have not yet taken off, [guest] on the back now. */
    fun step(raw: String?, ours: List<String>, guest: String?): Step {
        val now = parse(raw)
        val off = ours.filter { it != guest }
        val on = guest?.takeIf { it !in now }
        val next = now.filter { it !in off } + listOfNotNull(on)
        val stillOurs = ours.filter { it == guest && it in now } + listOfNotNull(on)
        return Step(if (next == now) null else next.joinToString(", "), stillOurs)
    }
}
