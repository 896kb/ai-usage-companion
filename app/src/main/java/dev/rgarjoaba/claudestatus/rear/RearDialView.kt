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
import dev.rgarjoaba.claudestatus.data.MascotMood
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
        textSize = 40f; typeface = label.typeface; color = Panel.Palette.WORK
        // Centred: the masthead now sits on the bar column's axis and carries no mark
        // beside it, so it is measured from its middle rather than a left edge.
        textAlign = Paint.Align.CENTER
    }

    /**
     * Plan tag. Right-aligned to the value column so it sits over the percentages
     * rather than crowding the account name, and green — the masthead's colour, marking
     * it as fixed context about the account rather than another live reading.
     */
    /**
     * Time-to-reset column, in the palette's foreground white.
     *
     * It was DIM (#555555), which is the palette's "this is disabled" tone and was
     * barely legible against #1D1F21 on the physical panel — the hierarchy was being
     * carried entirely by colour, and it overshot into invisible. The separation the
     * dimming was after is still there, just done with SIZE instead: 18f against the
     * percentage's 21f. Both readings stay legible, the percentage still leads.
     *
     * Palette.FG rather than pure #FFFFFF, so the panel keeps to the Tomorrow Night set
     * it is drawn from — every other bright element on the display is a palette colour.
     */
    private val timeTag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f; typeface = label.typeface
        textAlign = Paint.Align.RIGHT; color = Panel.Palette.FG
    }

    /** No anti-aliasing: the mascot is pixel art, and crisp edges are the look. */
    private val mascotPaint = Paint()

    private val planTag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f; typeface = label.typeface
        textAlign = Paint.Align.RIGHT; color = Panel.Palette.WORK
    }

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
        // The ring is a CLOCK, not a gauge: it sweeps toward the 5-hour reset, filling
        // as the window runs out of time. Utilisation for the same window is the 5H bar
        // on the canvas side — the two were the other way round until the user swapped
        // them, and a ring around a lens reads far more naturally as a dial than as a
        // percentage.
        //
        // Accent-coloured, never the ramp, and that follows directly from what it now
        // plots. On the bars, a full bar means a limit is nearly spent and the
        // green-to-red ramp says so. A full ring means the window is about to RESET,
        // which is good news; ramping it to red would invert the panel's one colour
        // language. This is the same reasoning that used to apply to the 5H bar back
        // when the bar was the thing showing time.
        val shown = springTo("ring:${account.name}", sessionElapsed(session.resetsAt)).coerceIn(0f, 1f)
        arc.color = if (status.health == Health.OK) account.accent else Panel.Palette.DIM
        canvas.drawArc(scratch, -90f, 360f * shown, false, arc)
    }

    /** Drawn after the rings, inside onDraw: bars, the reset footer and the mark. */
    private fun drawCanvasSide(canvas: Canvas) {
        drawTitle(canvas)
        drawBars(canvas)
        drawMascots(canvas)
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
        // Centred over the BAR COLUMN, not the whole canvas side — the same rule the
        // maker's mark follows, so the masthead and the artwork share one axis instead
        // of each being centred on something different.
        canvas.drawText("CLAUDE USAGE", (BAR_LEFT + BAR_RIGHT) / 2f, TITLE_BASELINE, title)
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
        sessionBars.clear()
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
            // Plan bracket, right-aligned to the value column. Static information, so
            // it is never ramp- or health-coloured: it says what you are entitled to,
            // not how much of it is left. Only the tier is shown — the profile response
            // also carries a name, an email and an org, none of which belong on a
            // panel that faces outward from the back of the phone.
            status?.planTier?.let { canvas.drawText(it, right, y, planTag) }
            val tagX = left + heading.measureText(account.label) + 14f
            // Kept alongside the dimming: grey alone says "something is wrong" without
            // saying what, which is what made an all-grey panel read as broken rather
            // than informative. The tag names the actual failure.
            healthTag(status?.health)?.let { tag ->
                label.color = Panel.Palette.DIM
                canvas.drawText(tag, tagX, y, label)
            }
            y += headingAdvance

            for (w in rows) {
                // Row labels take the account accent while the data is good, and only
                // go grey when it cannot be vouched for — same rule as the heading and
                // the bar. Previously they were permanently DIM, which read as
                // "disabled" even on a healthy account.
                label.color = if (status?.health == Health.OK) account.accent else Panel.Palette.DIM
                canvas.drawText(w.label, left, y + barH - 4f, label)
                val barLeft = BAR_LEFT
                val barRight = BAR_RIGHT
                canvas.drawRect(barLeft, y, barRight, y + barH, barTrack)
                // EVERY bar is utilisation, including the session row. It used to plot
                // time elapsed through the 5h window, which meant the same bar shape
                // carried two different meanings on one panel — and disagreed with the
                // ring drawn around the lens for that very window, which has always
                // shown utilisation. The countdown is not lost: it stays in the value
                // column, which is the only place a reset time appears at all.
                //
                // Keyed per account AND per window kind so each bar keeps its own
                // momentum — one shared spring would drag every row along with
                // whichever value moved.
                val fraction = springTo("bar:${account.name}:${w.kind.name}", w.utilization)
                // Same "dim when we can't vouch for it" rule as the rings: a
                // stale/error/auth account still shows its last-known bar widths,
                // just uniformly dimmed rather than accent/warn/crit colored.
                // Same continuous ramp as the rings, so the whole panel speaks one
                // colour language: green through amber to red as a window fills.
                // Replaced the earlier accent-until-75%-then-escalate rule, which made
                // a bar at 60% look identical to one at 10%.
                // One rule for every bar, now that they all plot the same quantity.
                // The session row used to be special-cased to the account accent, which
                // was right while it plotted TIME — filling up then meant a reset was
                // near, which is good news, and the green-to-red ramp would have
                // inverted that meaning. Plotting utilisation, it means what every other
                // bar means, so it is coloured the same way.
                //
                // The ramp reads the animated value, like the ring, so the colour
                // travels with the bar rather than snapping ahead of it.
                barFill.color =
                    if (status?.health != Health.OK) Panel.Palette.DIM else RampColor.at(fraction)
                canvas.drawRect(
                    barLeft, y,
                    barLeft + (barRight - barLeft) * fraction.coerceIn(0f, 1f),
                    y + barH, barFill,
                )
                // Percentage for EVERY row, including the session one, which used to put
                // its countdown here — the value column meant two different things
                // depending on the row, exactly the inconsistency the bars had.
                //
                // The number counts with the bar: springing the bar while the figure
                // jumps straight to its new value would make them disagree for the whole
                // animation.
                value.color = barFill.color
                canvas.drawText("${(fraction * 100).toInt()}%", PCT_RIGHT, y + barH - 4f, value)
                // Time-to-reset in its own column, so every window says when it turns
                // over rather than only the session one.
                canvas.drawText(remaining(w.resetsAt), TIME_RIGHT, y + barH - 4f, timeTag)
                if (w.kind == WindowKind.SESSION) {
                    sessionBars[account] = SessionBar(barLeft, barRight, y, fraction)
                }
                y += rowPitch
            }
            y += gap
        }
    }

    /**
     * One mascot per account, standing on that account's 5-hour bar.
     *
     * Which clip plays is NOT decided here — it arrives on the payload as
     * AccountStatus.mascot, chosen by MascotMood in the app process. This view is
     * destroyed and recreated constantly by the placement machinery, so it cannot hold
     * the usage history that decision needs.
     *
     * Feet sit on the bar's top edge, so the figure reads as standing on the progress it
     * describes. Drawn AFTER the bars for that reason — it should overlap them rather
     * than hide behind the track.
     *
     * Positions are FIXED along the bar, one per account, rather than tracking the
     * fill. Riding the fill was tried and reads badly here: at 0% both figures pile up
     * against the left edge on top of the row label, and two accounts at similar usage
     * put them at the same place on stacked rows, which looks like a mistake rather
     * than data. Fixed offsets keep them staggered and legible at every value, and the
     * bar underneath already shows the progress.
     */
    private fun drawMascots(canvas: Canvas) {
        for ((index, entry) in sessionBars.entries.withIndex()) {
            val account = entry.key
            val bar = entry.value
            // Fall back to standing for anything unresolvable. A payload restored from
            // disk can name a clip this build no longer ships — "walk" is exactly that
            // case, being withheld until it is finished — and silently drawing nothing
            // would look like the mascot feature had broken.
            val clip = MascotFrames.CLIPS.firstOrNull { it.name == accountMascot(account) }
                ?: MascotFrames.CLIPS.firstOrNull { it.name == MascotMood.STAND }
                ?: continue
            val h = MASCOT_HEIGHT * clip.scale
            val w = h * clip.aspect
            val at = MASCOT_STOPS.getOrElse(index) { MASCOT_STOPS.last() }
            val x = (bar.left + at * (bar.right - bar.left) - w / 2f)
                .coerceIn(bar.left, bar.right - w)
            val top = bar.top - h
            // Per-clip frame time: the confetti burst reads as frantic at the pace that
            // suits a walk or a lift, so it keeps the original 125ms while the rest run
            // at 83ms.
            val frame = clip.frames[
                ((System.currentTimeMillis() / clip.frameMs) % clip.frames.size).toInt()
            ]
            for (r in frame) {
                mascotPaint.color = accentFor(account, r.color)
                canvas.drawRect(
                    x + r.x * w, top + r.y * h,
                    x + (r.x + r.w) * w, top + (r.y + r.h) * h,
                    mascotPaint,
                )
            }
        }
        if (sessionBars.isNotEmpty()) animating = true
    }

    /** ACRNM maker's mark, bottom-right. White mask, tinted at draw time — never edit
     *  the drawable. */
    private fun drawMark(canvas: Canvas) {
        ray.setTint(Panel.Palette.PERSONAL)
        ray.setBounds(
            BAR_LEFT.toInt(), (Panel.HEIGHT - MARK_BOTTOM - MARK_H).toInt(),
            BAR_RIGHT.toInt(), Panel.HEIGHT - MARK_BOTTOM,
        )
        ray.draw(canvas)
    }

    /**
     * Re-tints the mascot's HARDWARE per account: headband, dumbbells and flag pole.
     *
     * The work account's kit is the company blue, the personal account's stays
     * Anthropic orange, so a glance at which figure is which needs no label. Applied at
     * draw time rather than baked into MascotFrames because the frame data is shared by
     * both accounts — baking it would mean generating and holding two copies of every
     * clip to change one colour.
     *
     * Only the accent is swapped. The body stays the palette green in both, because the
     * green is the panel's own language and two differently-coloured mascots would read
     * as two different characters rather than one character on two accounts.
     */
    private fun accentFor(account: Account, color: Int): Int =
        if (account == Account.WORK && color == ACCENT_ORANGE) ACCENT_WORK else color

    private fun accountMascot(account: Account): String =
        state.statuses.firstOrNull { it.account == account }?.mascot ?: MascotMood.STAND

    /**
     * Geometry of one account's 5h bar, captured by drawBars for the mascots to stand
     * on. Recorded during the bar pass rather than recomputed, so the figure's feet and
     * the bar it stands on cannot drift apart when the row layout changes.
     */
    private class SessionBar(val left: Float, val right: Float, val top: Float, val fill: Float)

    private val sessionBars = LinkedHashMap<Account, SessionBar>()

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
     * How far through the 5-hour session window we are, 0..1. Drives the rings.
     *
     * Derived from resetsAt because that is the only anchor the API gives us — there is
     * no window-start field — and the window's length is fixed at five hours, which is
     * what the row is labelled.
     *
     * Returns 0 when resetsAt is null: no session is running, and an empty ring reads
     * correctly as "nothing counting down", where a full one would read as "about to
     * reset".
     */
    private fun sessionElapsed(resetsAt: Long?): Float {
        if (resetsAt == null) return 0f
        val remaining = resetsAt - System.currentTimeMillis()
        return ((SESSION_WINDOW_MS - remaining).toFloat() / SESSION_WINDOW_MS).coerceIn(0f, 1f)
    }

    /**
     * Compact time until a window turns over.
     *
     * Days are a separate unit rather than rolled into hours: a 7-day window is ~168
     * hours out, and "167h02m" is both wider than the column and unreadable at a
     * glance. Two units is always enough — "3d17h", "4h51m", "22m".
     *
     * resetsAt is nullable: a window that has never been used has nothing to reset.
     */
    private fun remaining(resetsAt: Long?): String {
        if (resetsAt == null) return "—"
        val ms = resetsAt - System.currentTimeMillis()
        if (ms <= 0) return "now"
        val mins = ms / 60_000
        val hours = mins / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d${hours % 24}h"
            hours > 0 -> "${hours}h${(mins % 60).toString().padStart(2, '0')}m"
            else -> "${mins}m"
        }
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
        /**
         * 112, not 92. The column was sized for "5H"/"7D"/"FABLE"; once the labels
         * gained their scope and window ("ALL 5H", "FABLE 7D") the longest of them ran
         * straight into the bar. Measure against the LONGEST label, which is the scoped
         * one — and that one is only as long as the model name the API returns, so
         * leave slack for a longer name than FABLE.
         */
        private const val BAR_LEFT = CONTENT_LEFT + 112f
        /**
         * Each row is four columns: label, bar, percentage, time-to-reset. The last two
         * are derived backwards from the panel edge so the bar absorbs the remaining
         * width — adding the time column had to take its space from somewhere, and the
         * bar is the only element that reads fine at any length.
         *
         * Widths are sized against the worst case each column can hold: "100%" at 21f
         * for the percentage, "3d17h" at 18f for the time.
         */
        private const val TIME_RIGHT = CONTENT_RIGHT
        private const val PCT_RIGHT = CONTENT_RIGHT - 82f
        private const val BAR_RIGHT = PCT_RIGHT - 68f

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

        /**
         * Mascot size and gait. Raising the frame rate raises the redraw rate of the
         * WHOLE view, which is the real cost on an always-on display — 83ms is 12fps.
         */
        /**
         * Where each account's mascot stands along its bar, as a fraction. Staggered on
         * purpose: the two bars are directly above one another, so equal positions would
         * line the figures up in a column and read as a rendering artefact.
         */
        private val MASCOT_STOPS = floatArrayOf(0.45f, 0.80f)

        /**
         * Mascot hardware accents. The orange is Anthropic's brand colour and is what
         * MascotFrames bakes in; the blue is the company's, swapped in for the work
         * account at draw time. Neither belongs in Panel.Palette — that is the Tomorrow
         * Night set the whole panel is drawn from, and these two are brand colours that
         * appear on exactly one element each.
         */
        private const val ACCENT_ORANGE = 0xFFD97757.toInt()
        private const val ACCENT_WORK = 0xFF006EFF.toInt()

        private const val MASCOT_HEIGHT = 62f
        // 1.5x the original 125ms pace, at the user's request. Every clip shares it, so
        // the 8-frame and 36-frame animations stay in step rather than one appearing to
        // play in slow motion.
        private const val MASCOT_FRAME_MS = 83L

        /** The session window's fixed length — see sessionElapsed. */
        private const val SESSION_WINDOW_MS = 5f * 60f * 60f * 1000f

        private const val TITLE_BASELINE = 54f
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
