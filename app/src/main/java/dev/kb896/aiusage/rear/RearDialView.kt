package dev.kb896.aiusage.rear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.Health
import dev.kb896.aiusage.data.WindowKind

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
        color = Panel.Palette.DIM; textSize = 12f
        typeface = resources.getFont(R.font.departure_mono)
    }
    private val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f; typeface = label.typeface
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f; typeface = label.typeface; textAlign = Paint.Align.RIGHT
    }
    private val barTrack = Paint().apply { color = Panel.Palette.TRACK }
    private val barFill = Paint()
    private val ray = resources.getDrawable(R.drawable.acrnm_ray, null)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Panel.Palette.BG)
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

    /** Reset countdown for the personal session window, or an em dash. */
    private fun drawFooter(canvas: Canvas) {
        val left = Panel.CUTOUT_RIGHT + 34f
        val session = state.statuses.firstOrNull { it.account == Account.PERSONAL }
            ?.snapshot?.windows?.firstOrNull { it.kind == WindowKind.SESSION }
        label.color = Panel.Palette.DIM
        canvas.drawText("5H RESETS ${countdown(session?.resetsAt)}", left, Panel.HEIGHT - 40f, label)
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
        /** Accent until it matters, then the ramp's upper stops. Mirrors waybar's battery rule. */
        @JvmStatic
        fun barColor(utilization: Float, accent: Int): Int = when {
            utilization >= 0.90f -> Panel.Palette.CRIT
            utilization >= 0.75f -> Panel.Palette.WARN
            else -> accent
        }
    }
}
