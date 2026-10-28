package dev.kb896.aiusage.rear

/**
 * The guest app's task, read from `am stack list`, and when it is safe to move.
 *
 * SETTLED BEFORE MOVED. Measured: Shazam moved 1.5 s after launch was still on its
 * ConfigurationActivity; the move to the rear's density relaunched that, it finished
 * itself, and the task went with it. An app's startup trampolines are done once its top
 * activity has stopped changing, so the move waits for [Settle].
 */
object GuestTask {

    /** [bounds]: the task's, as `am stack list` gives them; null where the line has none. */
    data class Entry(val task: Int, val pkg: String, val display: Int, val top: String, val bounds: GuestShape.Bounds? = null)

    private val DISPLAY = Regex("""\bdisplay=(\d+)""")
    private val BOUNDS = Regex("""\bbounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
    private val TASK = Regex("""taskId=(\d+): ([^/\s]+)/\S+(?:.*?topActivity=ComponentInfo\{([^}]+)\})?""")

    fun entries(stackList: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var display = -1
        for (line in stackList.lineSequence()) {
            val t = TASK.find(line)
            if (t != null) {
                val b = BOUNDS.find(line)?.groupValues?.drop(1)?.map { it.toInt() }
                out += Entry(t.groupValues[1].toInt(), t.groupValues[2], display, t.groupValues[3],  // "" while no top activity yet
                    b?.let { GuestShape.Bounds(it[0], it[1], it[2], it[3]) })
                continue
            }
            DISPLAY.find(line)?.let { display = it.groupValues[1].toInt() }
        }
        return out
    }

    /** The first match in dump order (top of the stack first). */
    fun taskOf(stackList: String, pkg: String): Entry? = entries(stackList).firstOrNull { it.pkg == pkg }

    fun byId(stackList: String, task: Int): Entry? = entries(stackList).firstOrNull { it.task == task }

    /** What Send to back may take: not the launcher, not the shade, not this app. */
    fun sendable(pkg: String, ourPkg: String): Boolean = pkg != ourPkg && pkg !in NEVER

    private val NEVER = setOf("com.miui.home", "com.android.systemui", "com.android.launcher3")

    /** Per guest task (make a new one per launch); expects a monotonic clock. True once [feed] has seen the same top activity for [SETTLE_MS]. */
    class Settle {
        private var top: String? = null
        private var since = 0L
        fun feed(top: String, nowMs: Long): Boolean {
            if (top.isEmpty()) { this.top = null; return false }
            if (top != this.top) { this.top = top; since = nowMs; return false }
            return nowMs - since >= SETTLE_MS
        }
    }

    const val SETTLE_MS = 1_000L
}
