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

    /**
     * The idle state: the mascot walks its bar, at the user's request.
     *
     * Rendered by WalkRig rather than a MascotFrames clip — it is the only state without
     * baked frames, because the source has none either. Costs no extra redraws: the panel
     * already invalidates every frame for the other clips.
     */
    const val WALK = "walk"

    /** The rig at rest. No longer an idle state of its own — see [WALK] — but still what
     *  [DEAD] is drawn from, and the fallback for a clip name this build cannot resolve. */
    const val STAND = "stand"
    const val MUSCLE = "muscle"
    const val FLAG = "flag"
    /**
     * Nothing started yet — the whole window is ahead of you.
     *
     * Keyed on the session window having no reset time, NOT on it reading 0%. A 5-hour
     * window begins at first use, so until you use something there is no window and the
     * API sends `resets_at: null`; once one is running it carries a real timestamp.
     *
     * Those are two different situations and 0% conflated them. A window that has
     * started and rounds to 0% is a clock already ticking with a few minutes gone —
     * throwing confetti at it celebrates a fresh start that has, quietly, already
     * begun. Waiting for the reset time to disappear means the celebration marks the
     * thing worth marking: a clean slate, with the full five hours still to spend.
     */
    const val CONFETTI = "confetti"

    /**
     * The WEEK is gone. Rendered as the standing rig on its side with X-ed eyes — the
     * mascot has died of overuse.
     *
     * Keyed on the 7-day all-models window alone, and that is the whole point: it is the
     * limit you cannot wait out. A 5-hour window refills every five hours, so at the
     * moment one rolls over the session reads 0% while the week is still exhausted —
     * and the mascot threw CONFETTI at a dead account, because zero looked like a fresh
     * start. It was not; there was nothing left to spend it on.
     *
     * Distinct from FLAG, which is the 5-hour window alone being nearly gone.
     */
    const val DEAD = "dead"

    /**
     * The 5-hour window is gone. Nothing left to do until it rolls over, so the mascot
     * sleeps it off.
     *
     * Ranked ABOVE the flag, which is the warning that this was coming. Past [SPENT]
     * the warning has no purpose — there is nothing left to spend carefully — and above
     * the burst hold too, because a burst cannot be happening against a window with
     * nothing in it.
     *
     * Distinct from [DEAD]: this window refills in a few hours, and the pose says so.
     * The week does not, which is why that one is a body on its side.
     */
    const val SLEEP = "sleep"

    /**
     * Nobody home. The account has no usable credential — never connected, signed out,
     * or its refresh token expired — so there is no reading to show at all.
     *
     * Distinct from [DEAD], and the difference is worth keeping straight: DEAD is a
     * fact about usage, the week spent, the numbers real and all bad. GHOST is the
     * absence of numbers. Rendering a disconnected account as dead would state a limit
     * had been reached when nothing had been measured.
     */
    const val GHOST = "ghost"

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
        weeklySpent: Boolean = false,
        sessionStarted: Boolean = true,
    ): Decision {
        val burst = earlier != null && utilization - earlier >= BURST - EPSILON
        val hold = if (burst) now + HOLD_MS else holdUntil
        val clip = when {
            // Outranks everything, including a live burst hold and a session at 0.
            // If the week is gone, what the 5-hour window says is beside the point.
            weeklySpent -> DEAD
            !sessionStarted -> CONFETTI
            utilization >= SPENT -> SLEEP
            utilization >= HIGH -> FLAG
            now < hold -> MUSCLE
            else -> WALK
        }
        return Decision(clip, hold)
    }

    /**
     * Whether the 7-day all-models window is spent.
     *
     * A MISSING weekly window is not a spent one. An account whose response carries no
     * weekly limit has no data, not no allowance, and reporting it dead would turn a
     * gap in the API into a verdict about the user.
     *
     * Only WEEKLY_ALL counts. The scoped weekly is one model's share and can be
     * exhausted while the account still has plenty; treating it as fatal would kill the
     * mascot over a limit its owner can simply route around by using another model.
     */
    fun weeklySpent(windows: List<UsageWindow>): Boolean =
        windows.firstOrNull { it.kind == WindowKind.WEEKLY_ALL }
            ?.let { it.utilization >= SPENT } ?: false

    /**
     * @param utilization current 5h usage, 0..1
     * @param earlier the same account's usage as of at least [WINDOW_MS] ago, or null
     *   when no sample that old exists yet
     * @param sessionStarted whether the 5-hour window is running at all — the session
     *   window carrying a reset time. Defaults true: a caller that cannot tell should
     *   get the ordinary rules rather than a celebration it did not ask for.
     *
     * Order matters and is not arbitrary:
     *
     *  - DEAD first: a spent WEEK outranks whatever the 5-hour window happens to say,
     *    including a fresh one reading 0.
     *  - NOT STARTED second. A window that has not begun is unambiguous: there is no
     *    burn to measure and nothing to warn about.
     *  - SPENT next: the 5-hour window is gone, so the mascot sleeps until it rolls
     *    over. Above the flag, because past 99% the warning has nothing left to warn
     *    about.
     *  - HIGH after that, so a near-exhausted window shows the flag even while the user
     *    is still burning through it. Checking the burst first would hide the warning at
     *    exactly the moment it matters most, which is why the spec's "and total < 90%"
     *    qualifier exists.
     *  - BURST next — working hard, with room left.
     *  - WALK otherwise: idling somewhere in the middle.
     *
     * A null [earlier] cannot produce a burst. With no history the honest reading is
     * "unknown", and inventing one from a single sample would fire the lifting animation
     * on the first poll after every restart.
     */
    fun of(
        utilization: Float,
        earlier: Float?,
        weeklySpent: Boolean = false,
        sessionStarted: Boolean = true,
    ): String = when {
        weeklySpent -> DEAD
        !sessionStarted -> CONFETTI
        utilization >= SPENT -> SLEEP
        utilization >= HIGH -> FLAG
        earlier != null && utilization - earlier >= BURST - EPSILON -> MUSCLE
        else -> WALK
    }
}
