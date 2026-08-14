package dev.rgarjoaba.claudestatus.rear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.rgarjoaba.claudestatus.Panel

/** Measurement grid: read the lens centres straight off the glass. */
class CalibrationView(context: Context) : View(context) {

    var geometry: PanelGeometry = PanelGeometry.DEFAULT
        set(value) { field = value; invalidate() }

    private val fine = Paint().apply { color = Panel.Palette.GRID_FINE; strokeWidth = 1f }
    private val coarse = Paint().apply { color = Panel.Palette.GRID_COARSE; strokeWidth = 1f }
    private val edge = Paint().apply {
        color = Panel.Palette.CRIT
        // 2px, not the usual 1px rule: this is the single most important reference
        // line on a calibration-only screen (the reported cutout edge), and it needs
        // to stand out from the 1px grid at a glance, not blend into it.
        strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val label = Paint().apply {
        color = Panel.Palette.DIM; textSize = 13f; isAntiAlias = true
    }
    private val ringPersonal = Paint().apply {
        color = Panel.Palette.PERSONAL; style = Paint.Style.STROKE
        strokeWidth = 1.5f; isAntiAlias = true
    }
    private val ringWork = Paint().apply {
        color = Panel.Palette.WORK; style = Paint.Style.STROKE
        strokeWidth = 1.5f; isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Panel.Palette.BG)
        val w = width.toFloat(); val h = height.toFloat()

        var x = 0f
        while (x <= w) { canvas.drawLine(x, 0f, x, h, if (x % 100f == 0f) coarse else fine); x += 50f }
        var y = 0f
        while (y <= h) { canvas.drawLine(0f, y, w, y, if (y % 100f == 0f) coarse else fine); y += 50f }

        canvas.drawLine(Panel.CUTOUT_RIGHT.toFloat(), 0f, Panel.CUTOUT_RIGHT.toFloat(), h, edge)
        // y=34, not 20: the x=300 grid-coordinate label already sits at y=14, six px away —
        // any closer and the two labels overlap right where they matter most.
        canvas.drawText("x=${Panel.CUTOUT_RIGHT} reported cutout edge", Panel.CUTOUT_RIGHT + 8f, 34f, label)

        for (gx in 100..800 step 100) canvas.drawText("$gx", gx + 4f, 14f, label)
        for (gy in 100..500 step 100) canvas.drawText("$gy", 4f, gy - 4f, label)

        drawRuler(canvas, geometry.personal, ringPersonal, "PERSONAL")
        drawRuler(canvas, geometry.work, ringWork, "WORK")
    }

    private fun drawRuler(canvas: Canvas, g: RingGeometry, paint: Paint, name: String) {
        for (dr in listOf(-30f, 0f, 30f)) canvas.drawCircle(g.cx, g.cy, g.radius + dr, paint)
        canvas.drawLine(g.cx - g.radius - 40f, g.cy, g.cx + g.radius + 40f, g.cy, paint)
        canvas.drawLine(g.cx, g.cy - g.radius - 40f, g.cx, g.cy + g.radius + 40f, paint)
        // Prefer the label above the ring, but flip below when there isn't room (e.g. the
        // brief's own PERSONAL default — cy=152, radius=108 — puts "above" at y=-6, off
        // the top edge and invisible). Clamp x too so a nudged-near-the-edge ring never
        // pushes the text off-screen.
        val above = g.cy - g.radius - 50f
        val labelY = if (above >= 12f) above else (g.cy + g.radius + 60f).coerceAtMost(height - 4f)
        val labelX = (g.cx - g.radius).coerceIn(4f, width - 4f)
        canvas.drawText("$name  cx=${g.cx.toInt()} cy=${g.cy.toInt()} r=${g.radius.toInt()}",
            labelX, labelY, label)
    }
}
