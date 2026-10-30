package dev.kb896.aiusage.rear

/**
 * A guest that closes itself when started the full-screen way behind the lock, and what is done
 * about it. Pure.
 *
 * Measured on the phone (2026-10-30): with the front locked, Calculator started on display 0 for
 * the move to the rear closes itself within about 50 ms (`setShowWhenLocked from true to false`,
 * `isKeyguardLocked:true`, its ActivityRecord removed), and the launch used to wait out its six
 * seconds and say "never started". Unlocked it opens; on a private display (ALWAYS_UNLOCKED), locked
 * or not, it opens. So such a launch is tried once there, for this open only: the config is not
 * written, and the front's switch goes on saying what it said.
 */
object LockRetry {

    /** How long a task once seen must stay gone: a trampoline's between two tasks is not closing. */
    const val SEEN_GONE_MS = 400L

    /**
     * How long after a start that took no task must show. A task is made within `am start`, however
     * cold the app, but one living 50 ms can fall between two looks at `am stack list`.
     */
    const val NEVER_SEEN_MS = 1_000L

    /**
     * The start's task is gone, for [goneForMs] since last seen ([sawTask]) or since the start. Not
     * for an `am start` that refused ([started] false): no such activity, say, is nothing closing.
     */
    fun closedItself(started: Boolean, sawTask: Boolean, goneForMs: Long): Boolean =
        started && goneForMs >= if (sawTask) SEEN_GONE_MS else NEVER_SEEN_MS

    /**
     * Whether to try on a private display instead. Not for an app [confined] already (that is where
     * it just was), nor where a display was [refused], nor [retried] already in this open, or asked
     * for the whole rear by the owner's own switch. [frontLocked] costs a dumpsys: asked last.
     */
    fun applies(closed: Boolean, confined: Boolean, refused: Boolean, retried: Boolean, frontLocked: () -> Boolean): Boolean =
        closed && !confined && !refused && !retried && frontLocked()
}
