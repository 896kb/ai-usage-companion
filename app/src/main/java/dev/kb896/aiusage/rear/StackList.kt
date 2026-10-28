package dev.kb896.aiusage.rear

/**
 * Parses `am stack list`. Verified still present and in this format on
 * Android 16 / HyperOS V816 during the M0 probe.
 */
object StackList {
    private val TASK_ID = Regex("""taskId=(\d+)""")

    fun parseTaskId(output: String, activitySuffix: String): Int {
        for (line in output.lineSequence()) {
            if (!line.contains(activitySuffix)) continue
            val id = TASK_ID.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (id != null) return id
        }
        return -1
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
