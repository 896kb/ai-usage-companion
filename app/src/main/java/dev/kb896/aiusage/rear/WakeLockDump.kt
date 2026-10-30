package dev.kb896.aiusage.rear

/**
 * Whether the window manager holds a display's screen on for a window asking it to
 * (FLAG_KEEP_SCREEN_ON), read off `dumpsys power`'s "Wake Locks:" list: DisplayContent's own
 * lock, tagged `WindowManager/displayId:<id>` (seen so on the phone's log, 2026-10-30, for display
 * 1: "SCREEN_BRIGHT_WAKE_LOCK 'WindowManager/displayId:1' ON_AFTER_RELEASE"). Nothing typed says
 * this to uid 2000: IPowerManager lists no locks. Pure.
 *
 * Only that section, and that tag to its closing quote: the dump goes on to logs of past locks,
 * and display 1's is not display 12's. One marked DISABLED counts for nothing, as to the power
 * manager (PowerManagerService's WakeLock.toString, its uid cached by the freezer).
 */
object WakeLockDump {

    fun keepsScreenOn(dump: String, displayId: Int): Boolean {
        val tag = "'WindowManager/displayId:$displayId'"
        var inList = false
        for (line in dump.lineSequence()) {
            if (line.startsWith("Wake Locks:")) { inList = true; continue }
            if (!inList) continue
            if (line.isBlank()) return false
            if (line.contains(tag) && !line.contains(" DISABLED")) return true
        }
        return false
    }
}
