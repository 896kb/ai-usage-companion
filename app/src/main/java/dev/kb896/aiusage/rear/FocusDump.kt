package dev.kb896.aiusage.rear

/**
 * Parses `dumpsys window displays` for the app that has focus on one display.
 *
 * Each display's section opens with its id and carries the focused activity a few lines
 * further down, as on Android 16:
 *
 *     Display: mDisplayId=1 (organized)
 *       ...
 *       mFocusedApp=ActivityRecord{210822154 u0 com.android.camera/.Camera t72093}
 *
 * Used before sending Back to the rear display, so a Back meant for the camera never
 * lands on whatever else might be there.
 */
object FocusDump {

    private val displayLine = Regex("""mDisplayId=(\d+)""")
    private val focusLine = Regex("""mFocusedApp=ActivityRecord\{\S+ u\d+ ([^\s/}]+)/""")
    private val focusTask = Regex("""mFocusedApp=ActivityRecord\{.* t(\d+)\}""")

    /**
     * The package of the focused app on [displayId], or null when the dump does not say.
     * Only the lines between that display's `mDisplayId=` and the next one count.
     */
    fun focusedPackage(dump: String, displayId: Int): String? {
        var inDisplay = false
        for (line in dump.lineSequence()) {
            val id = displayLine.find(line)?.groupValues?.get(1)
            if (id != null) inDisplay = id == displayId.toString()
            if (inDisplay) focusLine.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** The task id of the focused app on [displayId] — the `t<id>` its record ends with. */
    fun focusedTask(dump: String, displayId: Int): Int? {
        var inDisplay = false
        for (line in dump.lineSequence()) {
            val id = displayLine.find(line)?.groupValues?.get(1)
            if (id != null) inDisplay = id == displayId.toString()
            if (inDisplay) focusTask.find(line)?.let { return it.groupValues[1].toIntOrNull() }
        }
        return null
    }
}
