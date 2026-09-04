package dev.kb896.aiusage.rear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.kb896.aiusage.Panel

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
    /** The hundreds among the coordinate labels, brighter — see onDraw. */
    private val labelBright = Paint().apply {
        color = Panel.Palette.FG; textSize = 13f; isAntiAlias = true
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
        // y=44, not 20: pulled further down from the top edge — see the label loop below
        // for why the row it used to sit six px from moved too.
        canvas.drawText("x=${Panel.CUTOUT_RIGHT} reported cutout edge", Panel.CUTOUT_RIGHT + 8f, 44f, label)

        // One label per FINE line (every 50, not just every 100) — a corner cutout needs
        // reading to the nearest tick, not the nearest hundred. The hundreds stay bright so
        // the coarse scale still reads at a glance; the fifties between them are dimmer.
        //
        // Both axes pulled inward from the physical edge — a Flip cover's rounded corners
        // and its own camera cutout eat into exactly the row/column a flush label would
        // have sat in, which is the one part of this screen precision matters most.
        var gx = 0
        while (gx <= w.toInt()) {
            if (gx > 0) {
                val tx = (gx - 14f).coerceAtMost(w - 32f)
                canvas.drawText("$gx", tx, 24f, if (gx % 100 == 0) labelBright else label)
            }
            gx += 50
        }
        var gy = 50
        while (gy <= h.toInt()) {
            val ty = gy.toFloat().coerceAtLeast(38f)
            canvas.drawText("$gy", 10f, ty, if (gy % 100 == 0) labelBright else label)
            gy += 50
        }

        // No lens to rule against on a device with no rings — see Panel.HAS_RINGS. Drawing
        // them anyway would overlay Xiaomi-tuned positions that mean nothing here and just
        // clutter the one screen meant to be read cleanly.
        if (Panel.HAS_RINGS) {
            drawRuler(canvas, geometry.personal, ringPersonal, "PERSONAL")
            drawRuler(canvas, geometry.work, ringWork, "WORK")
        }
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
