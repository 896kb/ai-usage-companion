package dev.kb896.aiusage.data

/**
 * Picks which mascot animation an account's 5-hour window deserves.
 *
 * Pure logic, no Android types, so the rules are testable — and they need to be, because
 * on the panel a wrong mood is indistinguishable from a bug in the renderer.
 *
 * The decision is made HERE, in the app process, rather than in the view: it depends on
 * usage history, and the view runs in `:rear`, is destroyed and recreated constantly by
 * the placement machinery, and would lose that history every respawn. The panel receives
 * the already-chosen clip name and only draws it.
 */
object MascotMood {

    /** The idle state. Deliberately the STILL pose, not the walk: the panel spends
     *  nearly all its time here, and a mascot marching on the spot forever is motion
     *  with nothing to say — and needless redraws on an always-on display. */
    const val STAND = "stand"
    const val MUSCLE = "muscle"
    const val FLAG = "flag"
    const val CONFETTI = "confetti"

    /** Above this, the window is effectively spent. */
    const val HIGH = 0.90f

    /** Recent burn that counts as "working hard". */
    const val BURST = 0.05f

    /** How far back "recently" reaches. */
    const val WINDOW_MS = 10 * 60 * 1000L

    /**
     * @param utilization current 5h usage, 0..1
     * @param earlier the same account's usage as of at least [WINDOW_MS] ago, or null
     *   when no sample that old exists yet
     *
     * Order matters and is not arbitrary:
     *
     *  - ZERO first. An untouched window is the only state that is unambiguous, and it
     *    outranks everything: at 0 there is no burn to measure and nothing to warn about.
     *  - HIGH second, so a near-exhausted window shows the flag even while the user is
     *    still burning through it. Checking the burst first would hide the warning at
     *    exactly the moment it matters most, which is why the spec's "and total < 90%"
     *    qualifier exists.
     *  - BURST third — working hard, with room left.
     *  - STAND otherwise: idling somewhere in the middle.
     *
     * A null [earlier] cannot produce a burst. With no history the honest reading is
     * "unknown", and inventing one from a single sample would fire the lifting animation
     * on the first poll after every restart.
     */
    fun of(utilization: Float, earlier: Float?): String = when {
        utilization <= 0f -> CONFETTI
        utilization >= HIGH -> FLAG
        earlier != null && utilization - earlier > BURST -> MUSCLE
        else -> STAND
    }
}
