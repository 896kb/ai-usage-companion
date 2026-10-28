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

    data class Entry(val task: Int, val pkg: String, val display: Int, val top: String)

    private val DISPLAY = Regex("""\bdisplay=(\d+)""")
    private val TASK = Regex("""taskId=(\d+): ([^/\s]+)/\S+.*?topActivity=ComponentInfo\{([^}]+)\}""")

    fun entries(stackList: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var display = -1
        for (line in stackList.lineSequence()) {
            val t = TASK.find(line)
            if (t != null) {
                out += Entry(t.groupValues[1].toInt(), t.groupValues[2], display, t.groupValues[3])
                continue
            }
            DISPLAY.find(line)?.let { display = it.groupValues[1].toInt() }
        }
        return out
    }

    fun taskOf(stackList: String, pkg: String): Entry? = entries(stackList).firstOrNull { it.pkg == pkg }

    fun byId(stackList: String, task: Int): Entry? = entries(stackList).firstOrNull { it.task == task }

    /** What Send to back may take: not the launcher, not the shade, not this app. */
    fun sendable(pkg: String, ourPkg: String): Boolean = pkg != ourPkg && pkg !in NEVER

    private val NEVER = setOf("com.miui.home", "com.android.systemui", "com.android.launcher3")

    /** True once [feed] has seen the same top activity for [SETTLE_MS]. */
    class Settle {
        private var top: String? = null
        private var since = 0L
        fun feed(top: String, nowMs: Long): Boolean {
            if (top != this.top) { this.top = top; since = nowMs; return false }
            return nowMs - since >= SETTLE_MS
        }
    }

    const val SETTLE_MS = 1_000L
}
