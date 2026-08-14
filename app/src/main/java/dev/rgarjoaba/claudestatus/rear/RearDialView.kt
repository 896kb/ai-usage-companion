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
        canvas.drawColor(Panel.Palette.BG)
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
        // the worst offender. drawColor above is unaffected (it fills the canvas's
        // clip, not a shape, so it ignores the transform) which is correct: the
        // background is uniform, it has nothing to protect against ghosting.
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
        arc.color = if (status.health == Health.OK) {
            RampColor.at(session.utilization)
        } else {
            Panel.Palette.DIM
        }
        canvas.drawArc(scratch, -90f, 360f * session.utilization.coerceIn(0f, 1f), false, arc)
    }

    /** Drawn after the rings, inside onDraw: bars, the reset footer and the mark. */
    private fun drawCanvasSide(canvas: Canvas) {
        drawBars(canvas)
        drawFooter(canvas)
        drawMark(canvas)
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
        val left = Panel.CUTOUT_RIGHT + 34f
        val right = Panel.WIDTH - 34f
        var y = 60f

        label.color = Panel.Palette.DIM
        canvas.drawText("7-DAY WINDOWS", left, y, label)
        y += 34f

        for (account in Account.entries) {
            val status = state.statuses.firstOrNull { it.account == account }
            heading.color = if (status?.health == Health.OK) account.accent else Panel.Palette.DIM
            canvas.drawText(account.label, left, y, heading)
            y += 20f

            val rows = status?.snapshot?.windows.orEmpty()
                .filter { it.kind != WindowKind.SESSION }
            for (w in rows) {
                canvas.drawText(w.label, left, y + 11f, label)
                val barLeft = left + 62f
                val barRight = right - 52f
                canvas.drawRect(barLeft, y, barRight, y + 13f, barTrack)
                // Same "dim when we can't vouch for it" rule as the rings: a
                // stale/error/auth account still shows its last-known bar widths,
                // just uniformly dimmed rather than accent/warn/crit colored.
                barFill.color = if (status?.health == Health.OK) {
                    barColor(w.utilization, account.accent)
                } else Panel.Palette.DIM
                canvas.drawRect(
                    barLeft, y,
                    barLeft + (barRight - barLeft) * w.utilization.coerceIn(0f, 1f), y + 13f, barFill,
                )
                value.color = barFill.color
                canvas.drawText("${(w.utilization * 100).toInt()}%", right, y + 11f, value)
                y += 22f
            }
            y += 18f
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
        val left = Panel.CUTOUT_RIGHT + 34f
        val session = state.statuses.firstOrNull { it.account == Account.PERSONAL }
            ?.snapshot?.windows?.firstOrNull { it.kind == WindowKind.SESSION }
        label.color = Panel.Palette.DIM
        canvas.drawText("5H RESETS ${countdown(session?.resetsAt)}", left, Panel.HEIGHT - 40f, label)

        val stale = staleLabel(state.fetchedAt, System.currentTimeMillis())
        if (stale != null) {
            value.color = Panel.Palette.DIM
            canvas.drawText(stale, Panel.WIDTH - 34f - 260f - 20f, Panel.HEIGHT - 40f, value)
        }
    }

    /** ACRNM maker's mark, bottom-right. White mask, tinted at draw time — never edit the drawable. */
    private fun drawMark(canvas: Canvas) {
        val w = 260
        val h = 66
        ray.setTint(Panel.Palette.PERSONAL)
        ray.setBounds(Panel.WIDTH - 34 - w, Panel.HEIGHT - 26 - h, Panel.WIDTH - 34, Panel.HEIGHT - 26)
        ray.draw(canvas)
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
        private const val REDRAW_INTERVAL_MS = 15_000L

        /** Accent until it matters, then the ramp's upper stops. Mirrors waybar's battery rule. */
        @JvmStatic
        fun barColor(utilization: Float, accent: Int): Int = when {
            utilization >= 0.90f -> Panel.Palette.CRIT
            utilization >= 0.75f -> Panel.Palette.WARN
            else -> accent
        }

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
