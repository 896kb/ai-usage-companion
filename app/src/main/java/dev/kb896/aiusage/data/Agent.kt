package dev.kb896.aiusage.data

import dev.kb896.aiusage.BuildConfig

/**
 * How this app introduces itself to a server.
 *
 * Derived from the build's own version rather than written out, because it was written
 * out: four headers went on saying 0.1 through every release after it, which is a request
 * quietly misreporting who is making it. A version that has to be updated in five places
 * is a version that will be right in one of them.
 *
 * It carries the name, the version and the platform, and deliberately nothing else. A
 * User-Agent is the one string every server sees, and the more it says about a particular
 * phone the more it does to tell one user apart from another.
 */
object Agent {

    /** The plain form, for a request that needs no further explanation. */
    val VALUE: String = "claude-status/" + BuildConfig.VERSION_NAME + " (android)"

    /**
     * The same, with a note about which part of the app is asking.
     *
     * Used where a project asks callers to identify themselves — LRCLIB does — so its
     * logs can tell a lyric lookup from anything else this app might one day send.
     */
    fun saying(note: String): String =
        "claude-status/" + BuildConfig.VERSION_NAME + " (android; " + note + ")"
}
