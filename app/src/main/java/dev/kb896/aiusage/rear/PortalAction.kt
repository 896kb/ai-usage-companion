package dev.kb896.aiusage.rear

/**
 * What the shell does for the portal sheet's choices and for a Back on the rear: pure, so
 * the rules are tested away from the shell (see specs/2026-10-28-app-portal-shell-sheet-design.md,
 * "Pull, release, taps", "Back", "Settled on the device" 1–2).
 */
object PortalAction {

    /** What a strip button comes to. The commands are RearUserService's. */
    enum class Step {
        /** Panel, front locked: the guest to display 0, behind the keyguard (invisible); the panel is revealed. */
        HIDE_ON_FRONT,
        /** Panel, front unlocked: the panel started over the guest on the rear. */
        START_PANEL,
        /** Resume of a guest hidden on display 0 by [HIDE_ON_FRONT]: straight back to the rear. */
        UNHIDE,
        /**
         * Resume of a guest behind the panel on the rear: the panel moves its own task to the
         * back, so the guest is on top without leaving the rear, locked or not. If it is not
         * on top a moment later, out to display 0 and back instead.
         */
        PANEL_STEPS_BACK,
        /** Front: the guest to display 0, and it is no longer the guest. */
        FRONT,
        /** Close: force-stopped. */
        CLOSE,
    }

    /**
     * The strip's button [index] — 0 Panel when the guest was on top ([guestOnTop]) else
     * Resume, 1 Front, 2 Close — as a [Step]. [frontLocked] is only asked for Panel (it
     * costs a dumpsys). Null for an index the strip does not have.
     */
    fun strip(index: Int, guestOnTop: Boolean, hiddenOnFront: Boolean, frontLocked: () -> Boolean): Step? = when (index) {
        0 -> when {
            guestOnTop -> if (frontLocked()) Step.HIDE_ON_FRONT else Step.START_PANEL
            hiddenOnFront -> Step.UNHIDE
            else -> Step.PANEL_STEPS_BACK
        }
        1 -> Step.FRONT
        2 -> Step.CLOSE
        else -> null
    }

    private val topFocused = Regex("""mTopFocusedDisplayId=(-?\d+)""")

    /** The display with input focus, from `dumpsys window | grep -m1 mTopFocusedDisplayId`; null when it does not say. */
    fun topFocusedDisplay(dump: String): Int? = topFocused.find(dump)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Whether the shell sends its own Back to the rear for a right-edge swipe. Only to a
     * guest on top and never with the sheet up; and not when the rear ([rear]) has input
     * focus, because Xiaomi's strip then delivers its own Back to the guest — two would go
     * back twice. Any other focus (the front's, or unknown) gets ours: Xiaomi's went to the
     * front app, and the guest would otherwise not go back at all.
     */
    fun sendBack(guestOnTop: Boolean, sheetUp: Boolean, focusedDisplay: Int?, rear: Int): Boolean =
        guestOnTop && !sheetUp && focusedDisplay != rear

    /**
     * Whether a bottom pull opens the sheet. Over a guest on top always; over the panel
     * not while the panel is guarded (its lock slider up, pocket lock, always-on) — a pull
     * there is most likely a pocket or a hand, and the panel is refusing touches anyway.
     */
    fun sheetOpens(panelGuarded: Boolean, guestOnTop: Boolean): Boolean = guestOnTop || !panelGuarded
}
