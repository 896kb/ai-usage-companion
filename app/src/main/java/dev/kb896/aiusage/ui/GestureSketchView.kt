package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.ui.GestureZones.Arrow
import dev.kb896.aiusage.ui.GestureZones.Kind

/**
 * A small drawing of the back for the Portal tab's guide: the rear display at its own
 * proportions, the camera column on its left as a dark band with its lenses, and the
 * portal's touch zones over the rest, each tinted, labelled and with an arrow for the way
 * the finger goes. Where everything goes is GestureZones'; this only paints it.
 *
 * As wide as it is given, and as tall as that makes it. The labels keep a readable size
 * (sp) rather than shrinking with the drawing.
 */
class GestureSketchView(context: Context) : View(context) {

    /** The zones' colour: the front's accent under glass, the panel's green under the original look. */
    private val accent = if (dev.kb896.aiusage.ui.glass.UiTheme.isGlass(context)) dev.kb896.aiusage.ui.glass.Ink.ACCENT else Panel.Palette.WORK

    private val density = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val screen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF141618.toInt() }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0x40FFFFFF
    }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050506.toInt() }
    private val lensBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1D20.toInt() }
    private val lensRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0x55FFFFFF
    }
    private val lensEye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0x00FFFFFF) or 0x38000000 }
    private val zoneEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density; color = (accent and 0x00FFFFFF) or (0xB0 shl 24)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; color = accent
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = sp(11f); typeface = UiType.of(context)
    }

    private val rect = RectF()
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, GestureZones.heightFor(w))
    }

    override fun onDraw(canvas: Canvas) {
        val fit = GestureZones.fit(width.toFloat(), height.toFloat())
        val corner = 40f * fit.scale
        set(fit.box(GestureZones.Box(0f, 0f, GestureZones.WIDTH, GestureZones.HEIGHT)))
        val outline = Path().apply { addRoundRect(rect, corner, corner, Path.Direction.CW) }
        canvas.drawPath(outline, screen)
        canvas.save()
        canvas.clipPath(outline)
        set(fit.box(GestureZones.cameras))
        canvas.drawRect(rect, band)
        for (l in GestureZones.lenses) {
            val x = fit.x(l.x); val y = fit.y(l.y); val r = l.r * fit.scale
            canvas.drawCircle(x, y, r, lensBody)
            canvas.drawCircle(x, y, r, lensRing)
            canvas.drawCircle(x, y, r * 0.42f, lensEye)
        }
        for (z in GestureZones.zones) if (z.kind != Kind.DOUBLE_TAP) {
            set(fit.box(z.box))
            canvas.drawRect(rect, zoneFill)
            canvas.drawRect(rect, zoneEdge)
        }
        canvas.restore()
        canvas.drawPath(outline, rim)
        for (z in GestureZones.zones) drawZone(canvas, z, fit.box(z.box))
    }

    /** The zone's arrow and label, placed by which edge it is on. */
    private fun drawZone(canvas: Canvas, z: GestureZones.Zone, b: GestureZones.Box) {
        val reach = 22f * density
        val gap = 6f * density
        when (z.arrow) {
            // The bottom band: the label in it, the arrow rising from it.
            Arrow.UP -> {
                arrow(canvas, b.centerX, b.top - gap * 0.5f, b.centerX, b.top - reach)
                label(canvas, z.label, b.centerX, b.centerY, Paint.Align.CENTER)
            }
            // The top band: the label in it, the arrow falling from it.
            Arrow.DOWN -> {
                arrow(canvas, b.centerX, b.bottom + gap * 0.5f, b.centerX, b.bottom + reach)
                label(canvas, z.label, b.centerX, b.centerY, Paint.Align.CENTER)
            }
            // The right edge: too thin for words, so they go left of the arrow coming out of it.
            Arrow.LEFT -> {
                arrow(canvas, b.left - gap * 0.5f, b.centerY, b.left - reach, b.centerY)
                label(canvas, z.label, b.left - reach - gap, b.centerY, Paint.Align.RIGHT)
            }
            // Two rings, a tap and its echo, the label under them.
            Arrow.NONE -> {
                val r = 7f * density
                canvas.drawCircle(b.centerX, b.centerY, r, stroke)
                canvas.drawCircle(b.centerX, b.centerY, r * 1.9f, zoneEdge)
                label(canvas, z.label, b.centerX, b.centerY + r * 1.9f + gap + text.textSize * 0.5f, Paint.Align.CENTER)
            }
        }
    }

    private fun arrow(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val head = 5f * density
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy).takeIf { it > 0f } ?: return
        val ux = dx / len; val uy = dy / len
        path.reset()
        path.moveTo(x0, y0); path.lineTo(x1, y1)
        path.moveTo(x1 - ux * head - uy * head, y1 - uy * head + ux * head)
        path.lineTo(x1, y1)
        path.lineTo(x1 - ux * head + uy * head, y1 - uy * head - ux * head)
        canvas.drawPath(path, stroke)
    }

    /** [s] with its middle at [y]. */
    private fun label(canvas: Canvas, s: String, x: Float, y: Float, align: Paint.Align) {
        text.textAlign = align
        canvas.drawText(s, x, y - (text.ascent() + text.descent()) / 2f, text)
    }

    private fun set(b: GestureZones.Box) = rect.set(b.left, b.top, b.right, b.bottom)
}
