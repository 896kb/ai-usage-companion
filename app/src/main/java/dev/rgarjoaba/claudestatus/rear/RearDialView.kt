package dev.rgarjoaba.claudestatus.rear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import dev.rgarjoaba.claudestatus.Panel
import dev.rgarjoaba.claudestatus.R
import dev.rgarjoaba.claudestatus.data.Account
import dev.rgarjoaba.claudestatus.data.DashboardState
import dev.rgarjoaba.claudestatus.data.Health
import dev.rgarjoaba.claudestatus.data.WindowKind

/**
 * Draws only — no parsing, no networking, no placement logic. Task 14 adds bars, the
 * footer and the ACRNM mark to this same view; [onDraw] and [drawRing] are kept
 * separate so that work lands as additions here, not a rewrite.
 */
class RearDialView(context: Context) : View(context) {

    var state: DashboardState = DashboardState.EMPTY
        set(value) { field = value; invalidate() }

    var geometry: PanelGeometry = PanelGeometry.DEFAULT
        set(value) { field = value; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Panel.Palette.TRACK; strokeCap = Paint.Cap.BUTT
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT
    }
    private val scratch = RectF()

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Panel.Palette.DIM; textSize = 18f
        typeface = resources.getFont(R.font.departure_mono)
    }
    private val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22.5f; typeface = label.typeface
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 21f; typeface = label.typeface; textAlign = Paint.Align.RIGHT
    }
    private val barTrack = Paint().apply { color = Panel.Palette.TRACK }
    private val barFill = Paint()
    private val ray = resources.getDrawable(R.drawable.acrnm_ray, null)

    /** Masthead type. Green rather than accent-coloured — see drawTitle. */
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f; typeface = label.typeface; color = Panel.Palette.WORK
    }

    /** White mask, tinted at draw time — same contract as [ray]. */
    private val logo = resources.getDrawable(R.drawable.claude_code, null)

    /**
     * One damped spring per animated quantity, keyed by what it drives.
     *
     * Values arrive as steps — a poll completes and a bar jumps — which on an
     * always-on panel reads as a glitch rather than as news. Springing them makes a
     * change something you notice out of the corner of your eye, which is the entire
     * job of a display nobody is looking at directly.
     */
    private class Spring {
        /** NaN until the first frame, so the panel opens on the real values rather
         *  than animating up from zero every time the activity is re-placed — and
         *  respawns are frequent here. Only genuine CHANGES animate. */
        var value = Float.NaN
        var velocity = 0f

        fun advance(target: Float, dt: Float): Float {
            if (value.isNaN()) { value = target; velocity = 0f; return value }
            val accel = STIFFNESS * (target - value) - DAMPING * velocity
            velocity += accel * dt
            value += velocity * dt
            return value
        }

        fun settled(target: Float): Boolean =
            kotlin.math.abs(target - value) < EPSILON && kotlin.math.abs(velocity) < EPSILON
    }

    private val springs = mutableMapOf<String, Spring>()
    private var lastFrameNanos = 0L
    private var frameDt = 0f
    private var animating = false

    /**
     * Current animated value for [key], advancing toward [target] by this frame's dt.
     * Flags the frame as animating so onDraw knows to schedule another one.
     */
    private fun springTo(key: String, target: Float): Float {
        val spring = springs.getOrPut(key) { Spring() }
        val v = spring.advance(target, frameDt)
        if (!spring.settled(target)) animating = true
        return v
    }

    /**
     * Self-scheduling redraw, independent of `state`/`geometry` pushes. Both of
     * Task 16's features (staleLabel, driftOffset) are computed live at draw time
     * from the clock, but onDraw only ever runs when something calls invalidate() —
     * and normally that "something" is UsagePollService pushing a fresh
     * DashboardState roughly once a minute (the `state` setter above invalidates).
     *
     * RearActivity runs in its own `:rear` process (see the manifest), reachable
     * from UsagePollService only via the ACTION_STATE broadcast. If that service's
     * tick loop has died — precisely the condition staleLabel exists to surface —
     * no more broadcasts arrive and invalidate() stops firing from that path. Without
     * an independent trigger the view would freeze on its last frame: the stale
     * marker would never appear, and the burn-in drift would freeze at one fixed
     * offset for as long as the service stays dead, which is unbounded and turns the
     * one failure this task defends against into the worst case for it. This timer
     * is what keeps both alive with no other input.
     */
    private val redrawTick: Runnable = Runnable {
        invalidate()
        postDelayed(redrawTick, REDRAW_INTERVAL_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(redrawTick, REDRAW_INTERVAL_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(redrawTick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        // Real elapsed time, clamped. The idle redraw is every 15s, so an unclamped dt
        // would integrate a 15-second step in one go and teleport every spring past its
        // target — the clamp makes a long gap behave like a resumed animation rather
        // than an explosion.
        val now = System.nanoTime()
        frameDt = if (lastFrameNanos == 0L) 0f
            else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, MAX_FRAME_DT)
        lastFrameNanos = now
        animating = false

        canvas.drawColor(BG_ALPHA shl 24 or (Panel.Palette.BG and 0xFFFFFF))
        // Partial fill, so the system's own rear-screen background shows through while
        // this surface still occludes. RearTheme's translucency does nothing on its own
        // — it was in place for a full build while this line painted Palette.BG at full
        // opacity over the whole surface, hiding everything behind it.
        //
        // DO NOT take this to zero. It was tried, and the panel stops working: with
        // nothing occluding it, MIUI's SubScreenLauncher resumes and takes the display,
        // and RearActivity's window leaves the window list entirely while its process
        // and task record stay alive. The panel then shows MIUI's clock-and-steps
        // widget and none of our marks. That failure is also invisible to the respawn
        // watchdog, which asks isAlive() about the TASK — and the task is still there.
        //
        // The cost of any alpha below 255 is contrast: the bar TRACKS are a near-black
        // #2A2C2E picked against #1D1F21, so over a light background they are the first
        // thing to disappear. Filled bars, rings and type are bright palette colours
        // and hold up.
        //
        // Burn-in mitigation, applied unconditionally. The panel is always-on at the
        // user's explicit request (RearActivity holds FLAG_KEEP_SCREEN_ON with no
        // toggle — MIUI otherwise kills the rear display in about five seconds), so
        // there is no "off" state during which this composition isn't lit. Two fixed
        // rings, four fixed bars, a fixed corner mark, all saturated colour, never
        // otherwise moving, is close to worst-case burn-in geometry for an OLED panel.
        // This translate is the only thing standing between that and irreversible
        // ghosting — it is load-bearing, not cosmetic. Do not gate it behind a flag,
        // do not make it conditional, and do not remove it to "simplify" this method.
        //
        // Applied before drawRing/drawCanvasSide so it moves the *entire* composition,
        // including the ACRNM mark drawn at the end of drawCanvasSide — small, bright,
        // high-contrast and pinned to one corner, the single highest burn-in risk on
        // the panel. A drift that moved the bars but left the mark pinned would miss
        // the worst offender. The drawColor above is unaffected (it fills the canvas's
        // clip, not a shape, so it ignores the transform) which is correct: a uniform
        // wash has nothing to protect against ghosting.
        //
        // SystemClock.elapsedRealtime(), not System.currentTimeMillis(): this must be
        // monotonic so an NTP correction or timezone change can't produce a sudden,
        // meaningless jump in drawn position. Same reasoning as RearActivity's
        // armEvictionTimer() guard, which uses the same clock for the same reason.
        val (dx, dy) = driftOffset(SystemClock.elapsedRealtime())
        canvas.translate(dx, dy)
        drawRing(canvas, geometry.personal, Account.PERSONAL)
        drawRing(canvas, geometry.work, Account.WORK)
        drawCanvasSide(canvas)

        // Next frame only while something is still moving. When everything has settled
        // this stops entirely and the view falls back to the 15s idle tick — an
        // always-on panel must not hold a 60fps loop for a display that is not changing.
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawRing(canvas: Canvas, g: RingGeometry, account: Account) {
        val status = state.statuses.firstOrNull { it.account == account }
        val session = status?.snapshot?.windows?.firstOrNull { it.kind == WindowKind.SESSION }

        track.strokeWidth = g.stroke
        scratch.set(g.cx - g.radius, g.cy - g.radius, g.cx + g.radius, g.cy + g.radius)
        canvas.drawArc(scratch, 0f, 360f, false, track)

        // No data, or data we can't vouch for: dim ring, no arc. Never render
        // a stale or absent reading as if it were live.
        if (status == null || session == null || status.health == Health.AUTH) return

        arc.strokeWidth = g.stroke
        // Coloured while the data is good, dimmed once a fetch has actually failed.
        // The tag beside the account label says which failure it was, so grey means
        // "this is history" rather than the unexplained grey it used to mean.
        // Sweep AND colour both follow the animated value, so the ramp travels with the
        // arc instead of snapping to the new colour while the arc is still moving.
        val shown = springTo("ring:${account.name}", session.utilization).coerceIn(0f, 1f)
        arc.color = if (status.health == Health.OK) RampColor.at(shown) else Panel.Palette.DIM
        canvas.drawArc(scratch, -90f, 360f * shown, false, arc)
    }

    /** Drawn after the rings, inside onDraw: bars, the reset footer and the mark. */
    private fun drawCanvasSide(canvas: Canvas) {
        drawTitle(canvas)
        drawBars(canvas)
        drawFooter(canvas)
        drawMark(canvas)
    }

    /**
     * Masthead: the Claude Code mark and "CLAUDE USAGE", across the top of the canvas
     * side. Replaces the "7-DAY WINDOWS" section label that used to sit here — the
     * bars are self-describing (each row is named, and the 5-hour figures are on the
     * rings), so that label spent a line of a 572px panel restating the obvious.
     *
     * Fixed green, never health-tinted. Everything else on this panel changes colour to
     * mean something — the ramp on the rings and bars, DIM when a fetch failed — so a
     * masthead that also changed colour would read as a fifth status signal. It is
     * branding, and it stays put.
     *
     * The mark is tinted to the same green from the same constant, not a second literal:
     * title and logo drifting apart is exactly what a copied hex value causes.
     */
    private fun drawTitle(canvas: Canvas) {
        val left = CONTENT_LEFT
        val size = TITLE_LOGO_SIZE
        logo.setTint(Panel.Palette.WORK)
        // The drawable's box is not its ink: the glyph occupies rows 5..20 of a 24-unit
        // viewBox, so the bottom sixth of the box is empty. Bottom-aligning the BOX to
        // the text baseline therefore leaves the mark visibly floating, by a gap that
        // grows every time the size does. Pushing the box down by that empty fraction
        // puts the glyph's own bottom edge on the baseline instead, which is what
        // "aligned with the text" means and what stays true at any size.
        val bottom = TITLE_BASELINE + size * LOGO_BOTTOM_PAD
        logo.setBounds(
            left.toInt(), (bottom - size).toInt(),
            (left + size).toInt(), bottom.toInt(),
        )
        logo.draw(canvas)
        canvas.drawText("CLAUDE USAGE", left + size + 16f, TITLE_BASELINE, title)
    }

    /**
     * 7-day window bars, one block per account. A window row is only drawn when the
     * account actually reported it — an account with no snapshot yet gets no rows at
     * all (not zeroed bars), and an account with no WEEKLY_SCOPED window (e.g. it has
     * never used a scoped model) simply has no third row. `w.label` is rendered
     * verbatim: the third window's name is whatever the live response resolved
     * (`scope.model.display_name`), never a hardcoded model name.
     */
    private fun drawBars(canvas: Canvas) {
        val left = CONTENT_LEFT
        val right = CONTENT_RIGHT

        // Layout metrics scale with the type. These were originally tuned for 12/15/14pt
        // text; when the sizes went to 18/22.5/21 the rows kept their old pitch and
        // crowded. Keep them proportional to the fonts rather than as loose literals.
        val barH = 20f
        val rowPitch = 33f
        val headingAdvance = 30f

        // Measure both account blocks before drawing anything, so the leftover space
        // can be shared out evenly instead of accumulating at the bottom.
        val blocks = Account.entries.map { account ->
            val status = state.statuses.firstOrNull { it.account == account }
            // The session window is included now. It used to be filtered out because the
            // rings already show its UTILISATION — but this row shows its TIME, which
            // nothing else on the panel did: the footer carried a single countdown for
            // the personal account only, so the work account's window was invisible.
            // The two accounts' 5h windows start independently, on each account's first
            // use, so one countdown can never stand for both.
            val rows = status?.snapshot?.windows.orEmpty()
            Triple(account, status, rows)
        }
        val contentH = blocks.fold(0f) { acc, (_, _, rows) ->
            acc + headingAdvance + rows.size * rowPitch
        }

        // The region the bars own: below the masthead, above the ACRNM mark.
        val regionTop = TITLE_BASELINE + 26f
        val regionBottom = Panel.HEIGHT - MARK_BOTTOM - MARK_H
        // One gap above the first block, one between, one below the last — so the
        // blocks sit evenly rather than hugging the top.
        val gap = ((regionBottom - regionTop - contentH) / (blocks.size + 1))
            .coerceAtLeast(0f)

        var y = regionTop + gap

        for ((account, status, rows) in blocks) {
            heading.color = if (status?.health == Health.OK) account.accent else Panel.Palette.DIM
            canvas.drawText(account.label, left, y, heading)
            // Plan bracket, dim and to the right of the name. Static information, so it
            // is never ramp- or health-coloured: it says what you are entitled to, not
            // how much of it is left. Only the tier is shown — the profile response
            // also carries a name, an email and an org, none of which belong on a
            // panel that faces outward from the back of the phone.
            var tagX = left + heading.measureText(account.label) + 14f
            status?.planTier?.let { tier ->
                label.color = Panel.Palette.DIM
                canvas.drawText(tier, tagX, y, label)
                tagX += label.measureText(tier) + 14f
            }
            // Kept alongside the dimming: grey alone says "something is wrong" without
            // saying what, which is what made an all-grey panel read as broken rather
            // than informative. The tag names the actual failure.
            healthTag(status?.health)?.let { tag ->
                label.color = Panel.Palette.DIM
                canvas.drawText(tag, tagX, y, label)
            }
            y += headingAdvance

            for (w in rows) {
                val isSession = w.kind == WindowKind.SESSION
                // Row labels take the account accent while the data is good, and only
                // go grey when it cannot be vouched for — same rule as the heading and
                // the bar. Previously they were permanently DIM, which read as
                // "disabled" even on a healthy account.
                label.color = if (status?.health == Health.OK) account.accent else Panel.Palette.DIM
                canvas.drawText(w.label, left, y + barH - 4f, label)
                val barLeft = BAR_LEFT
                val barRight = BAR_RIGHT
                canvas.drawRect(barLeft, y, barRight, y + barH, barTrack)
                // Time for the session row, utilisation for the rest. Keyed per account
                // AND per window kind so each bar keeps its own momentum — one shared
                // spring would drag every row along with whichever value moved.
                val target = if (isSession) sessionElapsed(w.resetsAt) else w.utilization
                val fraction = springTo("bar:${account.name}:${w.kind.name}", target)
                // Same "dim when we can't vouch for it" rule as the rings: a
                // stale/error/auth account still shows its last-known bar widths,
                // just uniformly dimmed rather than accent/warn/crit colored.
                // Same continuous ramp as the rings, so the whole panel speaks one
                // colour language: green through amber to red as a window fills.
                // Replaced the earlier accent-until-75%-then-escalate rule, which made
                // a bar at 60% look identical to one at 10%.
                barFill.color = when {
                    status?.health != Health.OK -> Panel.Palette.DIM
                    // Ramp reads the animated value, like the ring, so the colour
                    // travels with the bar rather than snapping ahead of it.
                    // Deliberately NOT the ramp. On a usage bar, filling up means
                    // approaching a limit, and the green-to-red ramp says so. On this
                    // bar filling up means the window is about to RESET, which is good
                    // news — painting it red would invert the meaning of the panel's
                    // one colour language. The account accent keeps it legible and
                    // marks it as a different kind of measure from the rows beneath.
                    isSession -> account.accent
                    else -> RampColor.at(fraction)
                }
                canvas.drawRect(
                    barLeft, y,
                    barLeft + (barRight - barLeft) * fraction.coerceIn(0f, 1f),
                    y + barH, barFill,
                )
                value.color = barFill.color
                canvas.drawText(
                    // The number counts with the bar. Springing the bar while the label
                    // jumps straight to the new figure would make them disagree for the
                    // whole animation. The session row is exempt: its label is a
                    // countdown, not a share of the bar's length.
                    if (isSession) countdown(w.resetsAt) else "${(fraction * 100).toInt()}%",
                    right, y + barH - 4f, value,
                )
                y += rowPitch
            }
            y += gap
        }
    }

    /**
     * Reset countdown for the personal session window, or an em dash. Also carries
     * the staleness marker (Task 16), right-aligned on the same row, when the data
     * is too old to present as live.
     *
     * The marker's right edge is anchored to the ACRNM mark's *left* edge (mark box
     * is Panel.WIDTH - 34 - 260 .. Panel.WIDTH - 34, same 260/66 as drawMark below),
     * not to the panel's own right margin. This row shares its y-band with the mark
     * (mark bottom sits at HEIGHT - 26, this text's baseline at HEIGHT - 40, both
     * inside the mark's 66px height), so right-aligning to the panel edge would draw
     * the marker underneath the mark itself. Aligning to the mark's left edge instead
     * keeps the two in their own horizontal lanes on the shared row.
     */
    private fun drawFooter(canvas: Canvas) {
        val stale = staleLabel(state.fetchedAt, System.currentTimeMillis())
        if (stale != null) {
            value.color = Panel.Palette.DIM
            canvas.drawText(stale, BAR_LEFT - 20f, Panel.HEIGHT - 40f, value)
        }
    }

    /** ACRNM maker's mark, bottom-right. White mask, tinted at draw time — never edit the drawable. */
    private fun drawMark(canvas: Canvas) {
        ray.setTint(Panel.Palette.PERSONAL)
        ray.setBounds(
            BAR_LEFT.toInt(), (Panel.HEIGHT - MARK_BOTTOM - MARK_H).toInt(),
            BAR_RIGHT.toInt(), Panel.HEIGHT - MARK_BOTTOM,
        )
        ray.draw(canvas)
    }

    /**
     * Short marker shown beside an account whose numbers cannot be vouched for.
     * null when the account is healthy, so nothing is drawn in the common case.
     */
    private fun healthTag(health: Health?): String? = when (health) {
        null -> null
        Health.OK -> null
        Health.AUTH -> "SIGN IN"
        Health.STALE -> "STALE"
        Health.ERROR -> "ERROR"
    }

    /**
     * How far through the 5-hour session window we are, 0..1.
     *
     * Derived from resetsAt because that is the only anchor the API gives us — there is
     * no window-start field — and the window's length is fixed at five hours, which is
     * what the row is labelled. Returns 0 when resetsAt is null: that means no session
     * is running at all (the work account is in exactly that state whenever it has not
     * been used), and an empty bar beside an em dash reads correctly as "nothing
     * counting down", where a full bar would read as "about to reset".
     */
    private fun sessionElapsed(resetsAt: Long?): Float {
        if (resetsAt == null) return 0f
        val remaining = resetsAt - System.currentTimeMillis()
        return ((SESSION_WINDOW_MS - remaining).toFloat() / SESSION_WINDOW_MS).coerceIn(0f, 1f)
    }

    /** resetsAt is nullable: an unused window has nothing to reset. */
    private fun countdown(resetsAt: Long?): String {
        if (resetsAt == null) return "—"
        val ms = resetsAt - System.currentTimeMillis()
        if (ms <= 0) return "now"
        val mins = ms / 60_000
        return if (mins >= 60) "${mins / 60}h${(mins % 60).toString().padStart(2, '0')}m" else "${mins}m"
    }

    companion object {
        /**
         * How often [redrawTick] reschedules itself. Chosen to be frequent enough
         * that the ±4px/10-minute drift reads as a slow wander rather than a single
         * jump (40 steps per cycle) and that a fresh `⟳ Nm` reading never lags more
         * than this far behind the real minute it crossed — but far from tight
         * enough to matter for battery or CPU on a canvas this simple. Not something
         * StalenessTest can cover: it drives a real Handler/Looper tied to this
         * View's window, which is UI glue, not the pure clock math in staleLabel/
         * driftOffset — same split this file already draws between companion
         * functions (tested) and onDraw/drawRing/drawBars/etc. (not).
         */
        /**
         * Masthead baseline, and the size of the square the mark is drawn into.
         * The mark's box is bottom-aligned to the text baseline so the two sit on one
         * line regardless of the font's own metrics.
         */
        /**
         * Opacity of the background fill, 0..255.
         *
         * Not a style knob — it trades two things off against each other. At 255 the
         * panel is the Tomorrow Night ground the palette was designed against, and
         * nothing shows through. At 0 the system wallpaper shows through completely and
         * the panel stops working entirely (see onDraw for what that failure looks
         * like). This sits between: enough coverage to keep occluding the sub-screen
         * launcher and to keep the near-black bar tracks legible, while the wallpaper
         * still reads through it.
         */
        private const val BG_ALPHA = 0xB4

        /**
         * The canvas side's two vertical rules. Everything on the right of the panel
         * lines up on one or the other: labels and the masthead at CONTENT_LEFT, bars
         * and the maker's mark between BAR_LEFT and BAR_RIGHT.
         *
         * These were four separate literals spread across drawBars, drawTitle and
         * drawMark. Sharing them is what makes "the mark is the same width as the bars"
         * a fact of the layout rather than a coincidence that survives until someone
         * retunes one of the margins.
         */
        private const val CONTENT_LEFT = Panel.CUTOUT_RIGHT + 34f
        private const val CONTENT_RIGHT = Panel.WIDTH - 34f
        private const val BAR_LEFT = CONTENT_LEFT + 92f
        private const val BAR_RIGHT = CONTENT_RIGHT - 72f

        /**
         * Maker's mark. Spans exactly the bar column (BAR_LEFT..BAR_RIGHT) at the
         * user's request, so its edges line up vertically with every bar above it.
         *
         * The height is derived from the drawable's own 405x102 viewport rather than
         * chosen, so the fan is never stretched: fixing the width to the bar column and
         * also hand-picking a height is exactly how an aspect-ratio distortion gets
         * baked in unnoticed.
         */
        private const val MARK_H = (BAR_RIGHT - BAR_LEFT) * 102f / 405f
        private const val MARK_BOTTOM = 26

        /** The session window's fixed length — see sessionElapsed. */
        private const val SESSION_WINDOW_MS = 5f * 60f * 60f * 1000f

        private const val TITLE_BASELINE = 54f
        private const val TITLE_LOGO_SIZE = 48f

        /**
         * Fraction of the drawable's box that sits BELOW the glyph's ink — the source
         * path spans rows 5..20 of a 24-unit viewBox, leaving 4/24 empty at the bottom.
         * Used to seat the mark on the text baseline; see drawTitle.
         */
        private const val LOGO_BOTTOM_PAD = 4f / 24f

        /**
         * Spring constants, in SI-ish units against a seconds-based dt.
         *
         * Damping ratio is DAMPING / (2 * sqrt(STIFFNESS)) = 4.2 / 7.48 = 0.56 —
         * underdamped, so a change overshoots by roughly a tenth and settles back.
         * That overshoot is the point: it is what makes the movement register as a
         * spring rather than a slow lerp. Settling time is about 4 / (zeta * omega) =
         * 4 / 2.1, near enough two seconds.
         */
        private const val STIFFNESS = 14f
        private const val DAMPING = 4.2f

        /** Below this, in both value and velocity, a spring counts as arrived. */
        private const val EPSILON = 0.0005f

        /** Longest dt any single frame may integrate. See onDraw. */
        private const val MAX_FRAME_DT = 0.05f

        private const val REDRAW_INTERVAL_MS = 15_000L

        /** Accent until it matters, then the ramp's upper stops. Mirrors waybar's battery rule. */

        /**
         * Null while the data is fresh enough to present as live. `fetchedAt` is
         * [dev.rgarjoaba.claudestatus.data.DashboardState.fetchedAt] — wall-clock
         * epoch millis stamped by UsageRepository.refresh() and carried across the
         * ACTION_STATE broadcast into this process, so `now` must be wall-clock too
         * (System.currentTimeMillis(), as drawFooter calls this) to compare against
         * it meaningfully; a monotonic clock on one side and wall-clock on the other
         * would compare unrelated epochs.
         */
        @JvmStatic
        fun staleLabel(fetchedAt: Long, now: Long): String? {
            val age = now - fetchedAt
            if (age < 2 * 60_000L) return null
            val mins = age / 60_000L
            return if (mins >= 60) "⟳ ${mins / 60}h" else "⟳ ${mins}m"
        }

        /**
         * Slow sub-pixel wander so a permanently-lit panel doesn't ghost. 10-minute
         * period, ±4px, applied to the whole composition including the ACRNM mark —
         * see onDraw for why this is load-bearing rather than cosmetic now that the
         * panel is always-on unconditionally.
         *
         * `now` should be a monotonic clock (SystemClock.elapsedRealtime()) at the
         * call site — see onDraw's KDoc. This function itself is clock-agnostic; it
         * just needs a steadily-increasing Long, verified by StalenessTest.
         *
         * ±4px and a 10-minute period are the brief's numbers, written for the
         * original opt-in/default-off toggle design. They still hold here — see
         * task-16-report.md for the tradeoff analysis now that the panel runs this
         * unconditionally, 24/7. Do not change these without checking that analysis
         * and the ring geometry margins in PanelGeometry.DEFAULT first: this
         * function's contract (StalenessTest's `dx in -4f..4f && dy in -4f..4f`)
         * pins the amplitude, and PanelGeometry's default ring placement has as
         * little as ~1-2px of margin from the panel edge at this amplitude already.
         */
        @JvmStatic
        fun driftOffset(now: Long): Pair<Float, Float> {
            val period = 600_000.0
            val phase = (now % period.toLong()) / period * 2 * Math.PI
            return (Math.sin(phase) * 4).toFloat() to (Math.cos(phase) * 4).toFloat()
        }
    }
}
