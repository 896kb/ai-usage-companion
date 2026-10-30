package dev.kb896.aiusage.rear

/**
 * Parses `am stack list`. Verified still present and in this format on
 * Android 16 / HyperOS V816 during the M0 probe.
 */
object StackList {
    private val TASK_ID = Regex("""taskId=(\d+)""")

    /**
     * The task of [pkg]'s [activitySuffix], or -1. [pkg]'s and nobody else's: the release and
     * the dev build share the activity's class, and matching its name alone took the other
     * build's panel for ours (see [OwnPanel]).
     */
    fun parseTaskId(output: String, pkg: String, activitySuffix: String): Int {
        for (line in output.lineSequence()) {
            if (!OwnPanel.names(line, pkg, activitySuffix)) continue
            val id = TASK_ID.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (id != null) return id
        }
        return -1
    }

    /**
     * Whether the top task of [displayId] is the OTHER build's panel (another
     * dev.kb896.aiusage* package than [ours], its RearActivity on top) — the back is the
     * other install's then, and this one stands down: no recovery re-places over it, no wake
     * lights it. Read from the task order, top first, so it holds on a dark rear too, where
     * SurfaceFlinger composes nothing to ask.
     */
    fun otherBuildOnTop(output: String, ours: String, displayId: Int): Boolean {
        val top = GuestTask.entries(output).firstOrNull { it.display == displayId } ?: return false
        return OwnPanel.isOtherBuild(top.pkg, ours) &&
            (top.top.isEmpty() || OwnPanel.names(top.top, top.pkg))
    }

    private val TASK_OF = Regex("""taskId=(\d+): ([^/\s]+)/""")

    /**
     * Every task of [pkg], in dump order. The camera opened on the back has two — the rear
     * Camera and its front-screen companion, MainScreenSelfieActivity — and closing it from
     * the back means both.
     */
    fun taskIdsOf(output: String, pkg: String): List<Int> =
        TASK_OF.findAll(output).filter { it.groupValues[2] == pkg }.map { it.groupValues[1].toInt() }.toList()
}
