package dev.kb896.aiusage.rear

/**
 * What the shell does for the portal sheet's choices and for a Back on the rear: pure, so
 * the rules are tested away from the shell (see specs/2026-10-28-app-portal-shell-sheet-design.md,
 * "Pull, release, taps", "Back on the rear", "Settled on the device" 1).
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

    /**
     * Whether a right-edge swipe is the guest's Back: only to a guest on top, and never with
     * the sheet up. Always ours to deliver (SystemBack): a BACK key — Xiaomi's strip's, or
     * any `input keyevent` — never reaches an app on display 1 on HyperOS (see the spec,
     * "Back on the rear"), whichever display has focus.
     */
    fun wantsBack(guestOnTop: Boolean, sheetUp: Boolean): Boolean = guestOnTop && !sheetUp

    /** What a system back navigation comes to (SystemBack). */
    enum class BackStep {
        /** The guest's own callback: started, triggered, invoked; the navigation finished as done. */
        INVOKE,
        /** The system had no navigation to give: nothing to do. */
        NO_NAVIGATION,
        /** The focused window is another task's — the front's app, most often: finished undone, nothing invoked. */
        NOT_THE_GUEST,
        /** The guest's, but with no callback: finished undone. */
        NO_CALLBACK,
    }

    /**
     * Whether to invoke the callback of a back navigation ([navigation] false: none was
     * given) that targets [focusedTask], for the guest's [guestTask]. The navigation is for
     * the GLOBALLY focused window, which may be an app on the front: a callback that is not
     * the guest's is never invoked. Whether it had one ([callback]) matters only then.
     */
    fun backStep(navigation: Boolean, focusedTask: Int, guestTask: Int, callback: Boolean): BackStep = when {
        !navigation -> BackStep.NO_NAVIGATION
        focusedTask != guestTask -> BackStep.NOT_THE_GUEST
        !callback -> BackStep.NO_CALLBACK
        else -> BackStep.INVOKE
    }

    /**
     * Whether a bottom pull opens the sheet. Over a guest on top always; over the panel
     * not while the panel is guarded (its lock slider up, pocket lock, always-on) — a pull
     * there is most likely a pocket or a hand, and the panel is refusing touches anyway.
     */
    fun sheetOpens(panelGuarded: Boolean, guestOnTop: Boolean): Boolean = guestOnTop || !panelGuarded
    /**
     * The guest as the portal sheet shows it: null none (no Panel/Resume · Front · Close, no
     * slider), else whether it is on top ([onTop]). A launched guest counts from the moment it
     * [landed] on the rear — moved there, its frame up — not only once it has survived the
     * move ([placed], two seconds later): a pull in between used to open the sheet without
     * the guest's buttons, which then came in late, a second or more after the rest (seen on
     * the phone, 2026-10-28). A guest sent to the back is [placed] at once.
     */
    fun sheetGuest(landed: Boolean, placed: Boolean, onTop: Boolean): Boolean? = if (landed || placed) onTop else null
    /** What a tap on an app's icon on the sheet comes to. */
    enum class AppTap {
        /** Launched as the guest (RearUserService.launchGuest): covered, moved in, revealed. */
        LAUNCH,
        /** It is the guest, on top already: the sheet shuts, and nothing else happens. */
        SHUT,
        /** It is the guest, behind the panel or hidden on the front: brought back as Resume brings it. */
        RESUME,
    }

    /**
     * A tap on [pkg]'s icon, with the sheet showing [guestPkg] as its guest ([guestOnTop]
     * as PortalAction.sheetGuest has it; null for none). Opening the app that is already
     * the guest relaunched it over itself and the rear glitched ("no action should be
     * taken … just the panel should be closed"): on top, the sheet only shuts; behind,
     * the tap is its Resume — the one thing opening it could mean.
     */
    fun appTap(pkg: String, guestPkg: String?, guestOnTop: Boolean?): AppTap = when {
        guestOnTop == null || pkg != guestPkg -> AppTap.LAUNCH
        guestOnTop -> AppTap.SHUT
        else -> AppTap.RESUME
    }
    /** BackNavigationInfo's types (TYPE_*), as the navigation reports them. */
    const val BACK_DIALOG_CLOSE = 0
    const val BACK_RETURN_TO_HOME = 1
    const val BACK_CROSS_ACTIVITY = 2
    const val BACK_CROSS_TASK = 3
    const val BACK_CALLBACK = 4

    /**
     * Whether a Back of [type] closes the guest's task: to home, or across to another task —
     * the system plays its close as it is invoked, and the frame and the camera strip fade
     * with it. An app's own callback (CALLBACK) may finish it at its root or may not, and
     * says nothing in advance: the checks after it ([BACK_CHECKS_MS]) tell.
     */
    fun backMayClose(type: Int): Boolean = type == BACK_RETURN_TO_HOME || type == BACK_CROSS_TASK

    /**
     * After a Back invoked on the guest, when (ms) to look whether its task is still on the
     * rear: soon, then less soon. Gone, the guest is over at once — not at the keep-alive's
     * next tick, two seconds of a frame and a strip round nothing (seen on the phone).
     */
    val BACK_CHECKS_MS = listOf(120L, 300L, 700L)
    /** What a guest's task gone in the launch's survive wait comes to. */
    enum class Vanished {
        /** Started again once: a trampoline the move relaunched (Shazam, measured). */
        RETRY,
        /** The guest is over: the user closed it (a Back at its root), and it is not relaunched. */
        OVER,
    }

    /**
     * A guest's task gone within the survive wait after its move: retried only when nothing
     * we sent could have closed it ([closedByUser] false). A Back invoked on it in that wait
     * closes it at its root — and the wait used to take that for a failed move and launch
     * the app again (seen on the phone). Close, Front and Panel take the launch's lock, so
     * they cannot happen inside it.
     */
    fun vanished(closedByUser: Boolean): Vanished = if (closedByUser) Vanished.OVER else Vanished.RETRY
}
