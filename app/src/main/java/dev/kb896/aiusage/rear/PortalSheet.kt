package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import dev.kb896.aiusage.Panel

/**
 * The App Portal's sheet: the pinned apps as a grid of icons, and — while a guest app is
 * up — a strip above it with the guest's three actions. It rises from the bottom over the
 * blurred panel (or the guest's frozen frame) as the pull opens it; RearDialView owns the
 * pull and the blur, this owns what is on the sheet.
 *
 * Laid out right of the lens column, in panel pixels, as plain numbers: the hit tests are
 * unit-tested, the drawing is checked on the phone.
 */
object PortalSheet {

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
        fun rect() = RectF(left, top, right, bottom)
    }

    sealed interface Hit {
        data class App(val index: Int) : Hit
        /** 0 Panel or Resume, 1 Front, 2 Close. */
        data class Button(val index: Int) : Hit
        data object Outside : Hit
    }

    /** Three rows of four fit under the strip; more would run off the bottom. */
    const val MAX_APPS = 12
    private const val COLUMNS = 4
    private const val CELL = 120f
    private const val GAP = 12f
    private const val STRIP = 64f
    private val LEFT = Panel.CUTOUT_RIGHT + 24f
    private const val TOP = 40f

    fun cells(count: Int, withStrip: Boolean): List<Box> {
        val top = TOP + if (withStrip) STRIP + GAP else 0f
        val n = count.coerceAtMost(MAX_APPS)
        return (0 until n).map { i ->
            val col = i % COLUMNS; val row = i / COLUMNS
            val l = LEFT + col * (CELL + GAP); val t = top + row * (CELL + GAP)
            Box(l, t, l + CELL, t + CELL)
        }
    }

    fun buttons(): List<Box> {
        val w = (COLUMNS * CELL + (COLUMNS - 1) * GAP - 2 * GAP) / 3
        return (0 until 3).map { i ->
            val l = LEFT + i * (w + GAP)
            Box(l, TOP, l + w, TOP + STRIP)
        }
    }

    /**
     * Valid only for the fully open sheet: [draw] translates by (1 - progress) * HEIGHT * 0.4,
     * so callers must ignore taps until the sheet has settled open.
     */
    fun hit(x: Float, y: Float, count: Int, withStrip: Boolean): Hit {
        if (withStrip) buttons().forEachIndexed { i, b -> if (b.contains(x, y)) return Hit.Button(i) }
        cells(count, withStrip).forEachIndexed { i, c -> if (c.contains(x, y)) return Hit.App(i) }
        return Hit.Outside
    }

    // Lazy: the unit tests load this object on the JVM, where android.graphics cannot be built.
    private val pane by lazy { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    /**
     * Names over the blur. Light ink alone vanished on a light backdrop, so every word on
     * the sheet carries a soft dark shadow: it reads over a white page and a black one.
     */
    private val text by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; textSize = 20f
            setShadowLayer(4f, 0f, 1.5f, SHADOW)
        }
    }
    private const val SHADOW = 0xCC000000.toInt()

    private fun ellipsize(s: String, width: Float): String {
        if (text.measureText(s) <= width) return s
        val n = text.breakText(s, true, width - text.measureText("…"), null)
        return s.take(n).trimEnd() + "…"
    }

    /** The first button's name, and the other two's. */
    fun buttonNames(guestOnTop: Boolean) = listOf(if (guestOnTop) "Panel" else "Resume", "Front", "Close")

    /**
     * The sheet at [progress] (0 shut, 1 open): it slides up by what is left to open and
     * fades in, as the control centre arrives. [icons] and [labels] are the pinned apps',
     * standing straight on the blur with nothing behind them; [guestOnTop] picks Panel or
     * Resume for the first button.
     *
     * [button] draws a button's body — the theme's own, glass or ORIGINAL, as the control
     * centre's — at its place this frame (index 0 to 2, 2 Close); the name goes over it
     * here, in [ink]. Every rect is moved by the slide rather than the canvas, so a pane of
     * glass bends the blur where it actually stands.
     */
    fun draw(
        canvas: Canvas, progress: Float, icons: List<Bitmap?>, labels: List<String>,
        guest: String?, guestOnTop: Boolean, ink: Int, typeface: Typeface? = null,
        button: (Canvas, RectF, Int) -> Unit,
    ) {
        if (progress <= 0.004f) return
        val withStrip = guest != null
        val dy = (1f - progress) * Panel.HEIGHT * 0.4f
        val alpha = (255 * progress).toInt().coerceIn(0, 255)
        // Faded as one picture, as the control centre is: glass cannot take a paint's alpha.
        val layer = if (alpha < 255) canvas.saveLayerAlpha(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(), alpha) else -1
        try {
            if (typeface != null && text.typeface != typeface) text.typeface = typeface
            text.color = ink
            val m = text.fontMetrics
            val middle = -(m.ascent + m.descent) / 2f
            if (labels.isEmpty() && !withStrip) {
                canvas.drawText("Pin apps in the app on the front", LEFT + (COLUMNS * CELL + (COLUMNS - 1) * GAP) / 2f, Panel.HEIGHT / 2f + dy, text)
            }
            if (withStrip) {
                val names = buttonNames(guestOnTop)
                buttons().forEachIndexed { i, b ->
                    val r = RectF(b.left, b.top + dy, b.right, b.bottom + dy)
                    button(canvas, r, i)
                    canvas.drawText(ellipsize(names[i], r.width() - 12f), r.centerX(), r.centerY() + middle, text)
                }
            }
            val shown = labels.take(MAX_APPS)
            pane.alpha = 255
            cells(shown.size, withStrip).forEachIndexed { i, c ->
                icons.getOrNull(i)?.let { bmp ->
                    val s = 72f; val l = c.centerX - s / 2; val t = c.top + 14f + dy
                    canvas.drawBitmap(bmp, null, RectF(l, t, l + s, t + s), pane)
                }
                canvas.drawText(ellipsize(shown[i], CELL - 8f), c.centerX, c.bottom - 12f + dy, text)
            }
        } finally {
            if (layer >= 0) canvas.restoreToCount(layer)
        }
    }
}
