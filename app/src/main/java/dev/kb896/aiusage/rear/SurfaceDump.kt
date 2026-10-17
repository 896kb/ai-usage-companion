package dev.kb896.aiusage.rear

/**
 * Parses `dumpsys SurfaceFlinger` for where RearActivity is actually being COMPOSITED.
 *
 * This is the only signal on this device that has not lied. Every window-manager
 * source — Activity.getDisplay(), `dumpsys window displays`, `am stack list` bounds
 * — reports display 1 while the panel renders visibly on the FRONT screen, and
 * panel power state cannot distinguish "our activity lit it" from "MIUI lit it".
 *
 * SurfaceFlinger is different in kind: `Display <id> (active|inactive) HWC layers:`
 * sections list what each display is composing. That is where pixels physically go,
 * not what the window manager believes about task ownership.
 *
 * The <id> there is the PHYSICAL display id, not the logical 1, and which one is the
 * rear display is the caller's to say. It used to be decided here, by the tail of the
 * 17 Pro's own rear panel id — and on a Pro Max, whose rear panel is another panel with
 * another id, no section was the rear one, so a panel sitting correctly on the back read
 * as on the MAIN display. Every tick then moved a task already on display 1 to display
 * 1, which the system refuses (moveRootTaskToDisplay throws), and after three refusals
 * the panel was finished as stranded and relaunched: the Pro Max's panel dying every
 * twenty seconds on a display that was on the whole time.
 */
object SurfaceDump {

    /**
     * "Nowhere" USED TO BE ONE ANSWER and it was two, which is the blind spot this enum
     * exists to close. The panel not being composited means either that it is asleep —
     * ordinary, and nothing to act on — or that something else has taken the rear display
     * while our task sits behind it, alive and invisible. Both read as null, so the
     * keep-alive's "composited nowhere AND no task" branch never fired for the second one:
     * the task is precisely what still exists. Observed on device with the rear display
     * showing HyperOS's own clock-and-steps widget, RearActivity paused in the background,
     * the service running, and every health check reporting a healthy panel.
     *
     * The two are told apart by what ELSE the rear section lists. A display composing
     * another app's layers is a display that is on and showing something; a display
     * composing nothing at all is asleep.
     */
    enum class Where {
        /** On the rear display. The only healthy answer. */
        REAR,

        /** On the main display, over whatever the user is actually doing. */
        MAIN,

        /** Nowhere, while something else holds the rear display. Alive and invisible. */
        DISPLACED,

        /** Nowhere, and nothing else is there either: the panel is asleep. */
        NOWHERE,
    }

    /**
     * A section's header, in either of the two shapes seen on this phone:
     *
     *     Display <id> (active) HWC layers:            HyperOS 3, Android 16
     *     Display <id> HWC layers (top to bottom):     HyperOS 4, Android 17
     *
     * The second no longer says whether the display is active. There, an unlit display's
     * section lists nothing at all, so a section that lists anything is taken as lit —
     * measured on the 17 Pro on OS4.0.4.0, rear display off and on.
     */
    private val header = Regex(
        """Display (\d+) (?:\((active|inactive)\) HWC layers:|HWC layers \(top to bottom\):)""",
    )

    /**
     * A layer name line in a HWC section: a window name ending in `#<id>`.
     *
     * Matched rather than counting lines, because a section always carries column
     * headings and rules whether or not it is composing anything. Names are truncated
     * with `[...]` in the middle when long, so this anchors on the end.
     */
    private val layerName = Regex("""^\s*\S+#\d+\s*$""", RegexOption.MULTILINE)

    /**
     * Where RearActivity is composited in [dump], with [isRear] saying which physical
     * display id is the rear display.
     *
     * Deliberately asymmetric: MAIN is returned whenever the layer is on a display that
     * is not the rear one — see the comment on the return.
     */
    fun where(dump: String, isRear: (physicalId: String) -> Boolean): Where {
        if (!dump.contains("HWC layers")) return Where.NOWHERE

        val matches = header.findAll(dump).toList()
        if (matches.isEmpty()) return Where.NOWHERE

        var onRear = false
        var onMain = false
        // Whether the rear display is composing ANYTHING, ours or not. This is what
        // separates an asleep panel from a hijacked one.
        var rearBusy = false
        // And whether that display is powered at all. SurfaceFlinger says so in the same
        // header — measured: the front display read "(inactive)" with mScreenState=OFF
        // while the rear read "(active)" with mScreenState=ON, in one dump.
        var rearActive = false
        for ((i, m) in matches.withIndex()) {
            val start = m.range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else dump.length
            val section = dump.substring(start, end)
            val rear = isRear(m.groupValues[1])
            if (rear) {
                val busy = layerName.containsMatchIn(section)
                if (busy) rearBusy = true
                // No word on it in the newer header: listing anything is being lit.
                if (m.groupValues[2] == "active" || (m.groupValues[2].isEmpty() && busy)) {
                    rearActive = true
                }
            }
            if (!section.contains("RearActivity")) continue
            if (rear) onRear = true else onMain = true
        }
        // ON MAIN WINS, even when it is also on the rear. Observed on device: the panel
        // drawing as a 904x572 box over the user's foreground app while
        // Activity.getDisplay() and `dumpsys window` both reported display 1 and the
        // layer appeared under BOTH display sections. Whatever the bookkeeping says, a
        // layer on the main display is pixels the user is looking at, and that is the
        // condition worth acting on.
        //
        // This reverses the earlier rule, which preferred "present on rear" to avoid
        // acting on a stale leftover. That caution was wrong for THIS layer: it made
        // the one genuinely visible failure undetectable. The cost of being wrong is a
        // redundant move, which is cheap and invisible; the cost of missing it is the
        // user staring at the panel on the wrong screen.
        return when {
            onMain -> Where.MAIN
            onRear -> Where.REAR
            // Our layer is nowhere. If the rear display is composing someone else's, it
            // is on and showing something that is not us, which is a failure that used to
            // be invisible. If it is composing nothing, the panel is simply asleep.
            //
            // "IS ON" IS NOT AN ASSUMPTION ANY MORE, IT IS CHECKED. A powered-down display
            // is composing nothing of ours and often something of MIUI's, which read as
            // "taken" — so the moment the panel deliberately let the display doze, the
            // watchdog re-placed it, and re-placing calls wake(). The panel woke itself
            // up about ten seconds after asking to sleep, every time, and the sleep looked
            // like it simply did not work.
            //
            // Nothing is composing on a dark display in any sense worth acting on. Falling
            // through to NOWHERE is the safe answer: that branch relaunches only when the
            // task is genuinely gone, so a panel that really did die while the display was
            // out is still recovered, and one that is merely dark is left alone.
            rearBusy && rearActive -> Where.DISPLACED
            else -> Where.NOWHERE
        }
    }
}
