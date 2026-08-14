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
}
