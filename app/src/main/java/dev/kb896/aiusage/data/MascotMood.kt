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

    /**
     * Every limit spent. Rendered as the standing rig on its side with X-ed eyes — the
     * mascot has died of overuse.
     *
     * Distinct from FLAG, which is the 5-hour window alone being nearly gone. This one
     * means there is nothing left anywhere: session, weekly and the scoped weekly all
     * at [SPENT] or above.
     */
    const val DEAD = "dead"

    /** Above this, the window is effectively spent. */
    const val HIGH = 0.90f

    /** Above this, a window has nothing meaningful left. */
    const val SPENT = 0.99f

    /**
     * Recent burn that counts as "working hard", INCLUSIVE.
     *
     * Inclusive is not a detail here, it is the difference between the rule working and
     * never firing. The API reports whole percentages, so utilisation only ever moves in
     * steps of 0.01 — a live trail read off the device went 0.34, 0.35, 0.35, 0.36. With
     * a strictly-greater test, "more than 1%" therefore means "2% or more", and a steady
     * 1%-per-minute burn never triggers anything. Compared with >= so that one step of
     * the smallest observable unit is enough.
     */
    const val BURST = 0.01f

    /**
     * The SHORTEST gap that counts as a comparison. A baseline even one minute old is
     * enough — if 1% has gone in that time, the mascot lifts.
     *
     * Being this small does not make the panel react within a minute, and it is worth
     * being clear why: the trail only gains a sample when a poll completes, and polls
     * are five minutes apart by rate-limit discipline. So in practice the newest usable
     * baseline is the previous poll. What this constant really controls is that a gap
     * is never rejected for being too SHORT — if the cadence ever tightens, or a manual
     * refresh lands, a one-minute comparison is accepted immediately rather than
     * discarded.
     */
    const val WINDOW_MS = 60 * 1000L

    /**
     * The LONGEST gap that still counts — the threshold beyond which a baseline is too
     * stale to mean anything.
     *
     * Ten minutes, at the user's direction: 1% burned any time in the last ten minutes
     * counts as working. Distinct from [HOLD_MS], which is how long the animation then
     * PLAYS — this one is how far back the comparison may reach.
     *
     * A ceiling is still needed at all because without one the measurement stretches
     * silently: whatever the oldest surviving sample happens to be becomes the baseline,
     * so a gap in polling would quietly turn "1% in ten minutes" into "1% in an hour"
     * without anything looking wrong. Beyond the ceiling the honest answer is "no
     * comparable baseline", not a different question answered confidently.
     */
    const val MAX_BASELINE_MS = 10 * 60 * 1000L

    /**
     * How long the lifting animation holds after a burst.
     *
     * Without a hold the mascot would lift for exactly one poll and drop back, because
     * the next comparison is against the reading the burst produced — so a genuine
     * stretch of work would flicker between lifting and standing rather than showing as
     * sustained effort.
     */
    const val HOLD_MS = 5 * 60 * 1000L

    /**
     * Float slack for the comparison. 0.35f - 0.34f is 0.010000005 and 0.36f - 0.35f is
     * 0.009999990 — the same 1% step lands either side of the threshold depending on the
     * values, so an exact >= would fire on some steps and not others.
     */
    private const val EPSILON = 1e-4f

    /** The chosen clip, plus the hold deadline to carry into the next poll. */
    data class Decision(val clip: String, val holdUntil: Long)

    /**
     * The full rule, including the latch.
     *
     * A burst does not merely start the animation, it (re)starts the clock: holdUntil
     * is set to now + [HOLD_MS] EVERY time one is seen, so working steadily keeps the
     * mascot lifting continuously instead of the hold expiring mid-effort. Stop working
     * and it lapses five minutes after the last burst.
     *
     * The value-based outcomes still outrank the hold. An account that hits 0 or 90%
     * while the latch is live shows that instead — the latch describes what you are
     * doing, those describe where you stand, and where you stand wins.
     */
    fun decide(
        utilization: Float,
        earlier: Float?,
        holdUntil: Long,
        now: Long,
        allSpent: Boolean = false,
    ): Decision {
        val burst = earlier != null && utilization - earlier >= BURST - EPSILON
        val hold = if (burst) now + HOLD_MS else holdUntil
        val clip = when {
            // Outranks everything, including a live burst hold. If nothing is left
            // anywhere, what the user is currently doing is beside the point.
            allSpent -> DEAD
            utilization <= 0f -> CONFETTI
            utilization >= HIGH -> FLAG
            now < hold -> MUSCLE
            else -> STAND
        }
        return Decision(clip, hold)
    }

    /**
     * Whether EVERY window is spent. An empty list is not "all spent" — that is an
     * account with no data, which must not be reported as dead.
     */
    fun allSpent(utilizations: List<Float>): Boolean =
        utilizations.isNotEmpty() && utilizations.all { it >= SPENT }

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
    fun of(utilization: Float, earlier: Float?, allSpent: Boolean = false): String = when {
        allSpent -> DEAD
        utilization <= 0f -> CONFETTI
        utilization >= HIGH -> FLAG
        earlier != null && utilization - earlier >= BURST - EPSILON -> MUSCLE
        else -> STAND
    }
}
