package dev.kb896.aiusage.rear

/**
 * Where an activity's task actually is on screen, read from `dumpsys activity
 * activities` — a fallback for devices whose `dumpsys SurfaceFlinger` has no per-display
 * HWC layers section for [RearUserService.panelWhere] to parse.
 *
 * Confirmed absent on a Galaxy Z Flip 6 running Android 16: no "HWC layers:" text
 * anywhere in the dump, of any kind — a newer SurfaceFlinger dump format entirely.
 * Without it panelWhere() always falls through to its own NOWHERE branch, so the
 * watchdog can never detect the one failure this whole mechanism exists for: something
 * else — on Samsung, `com.android.systemui/.subscreen.SubHomeActivity`, the cover
 * display's own SECONDARY_HOME role — sitting in front of a panel that is alive and
 * correctly placed, just not on top. Observed live: locking and unlocking the phone
 * sends that home task to the front every time, and RearActivity's own task is left
 * behind it with `visible=false`.
 *
 * ActivityTaskManager answers the composition question directly instead: a task's own
 * `visible=` flag, printed on its `Task{...}` line under the `Display #<id>` section
 * that owns it. No ambiguity to resolve the way SurfaceFlinger's silence has to be —
 * "not visible" means exactly that.
 */
enum class TaskDisplay {
    /** On the rear display, and the frontmost thing there. */
    REAR,

    /** On the main display, over whatever the user is actually doing. */
    MAIN,

    /** On the rear display, but not the frontmost thing there. Alive and invisible. */
    DISPLACED,

    /** Not found under either display in this dump. */
    NOWHERE,
}

object ActivityDump {
    private val displayHeader = Regex("""^Display #(\d+)""")
    private val taskVisible = Regex("""Task\{[^}]*\bvisible=(true|false)\b""")

    /**
     * [activityMarker] identifies the activity's own lines — e.g. "RearActivity" —
     * matching [RearUserService.panelWhere]'s own convention of a plain substring match.
     *
     * Reads a `Task{...}` line's `visible=` value and carries it forward to whatever
     * activity lines follow, up to the next `Task{` or `Display #`: in this dump format
     * a task's header always precedes the `ActivityRecord` lines inside it, with no
     * other task's header between them, so the most recently seen value is always the
     * right one by construction — this is not a heuristic guess.
     *
     * Takes the FIRST match, matching panelWhere()'s "main wins" rule structurally:
     * `Display #0`'s section is printed before `Display #1`'s, so a stray leftover
     * under the main display is still reported as MAIN rather than masked by a later,
     * correct entry on the rear display.
     */
    fun taskDisplay(
        dump: String,
        activityMarker: String,
        mainDisplayId: Int,
        rearDisplayId: Int,
    ): TaskDisplay {
        var currentDisplay = -1
        var pendingVisible: Boolean? = null
        for (line in dump.lineSequence()) {
            displayHeader.find(line)?.let {
                currentDisplay = it.groupValues[1].toIntOrNull() ?: -1
            }
            taskVisible.find(line)?.let { pendingVisible = it.groupValues[1] == "true" }
            if (!line.contains(activityMarker)) continue
            val visible = pendingVisible ?: continue
            return when {
                currentDisplay == mainDisplayId -> TaskDisplay.MAIN
                currentDisplay == rearDisplayId && visible -> TaskDisplay.REAR
                currentDisplay == rearDisplayId -> TaskDisplay.DISPLACED
                else -> TaskDisplay.NOWHERE
            }
        }
        return TaskDisplay.NOWHERE
    }
}
