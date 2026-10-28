package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Typeface
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.PanelTheme

/**
 * Paints the App Portal's sheet — the buttons, the pinned apps' icons and names — over a
 * backdrop someone else has drawn. Needs no View, Context or Resources: the panel's view
 * uses it, and so can the shell, which draws into a surface's hardware canvas from
 * app_process. Everything comes in: the [theme], the [typeface] (app_process has no
 * default typeface, and text drawn without one aborts), the icons as bitmaps, and the
 * node the glass bends.
 *
 * Its buttons are the control centre's (see CcButton): glass under GLASS, bending the
 * backdrop they stand on, and under ORIGINAL outlined as its tiles are. Close is the one
 * filled, in the critical red.
 *
 * Laid out by PortalSheet, in panel pixels.
 */
class PortalSheetPainter(var theme: PanelTheme, typeface: Typeface) {

    /** Its own panes: its own node pool and lens cache, reset at every [draw]. */
    private val glass = GlassPainter()

    /**
     * The body of a clear pane at a rect. The panel's reacts to how bright it is behind
     * it, and to the glass chosen in Glass settings; with nothing to say, clear.
     */
    var paneTint: (RectF) -> Int = { Glass.DISC_TINT }

    /** The names' colour. */
    var ink: Int = Panel.Palette.FG

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Names over the blur. Light ink alone vanished on a light backdrop, so every word on
     * the sheet carries a soft dark shadow: it reads over a white page and a black one.
     */
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 20f
        setShadowLayer(4f, 0f, 1.5f, SHADOW)
        this.typeface = typeface
    }

    private val buttonRect = RectF()
    private val buttonShape = Path()
    private val iconRect = RectF()

    private fun ellipsize(s: String, width: Float): String {
        if (text.measureText(s) <= width) return s
        val n = text.breakText(s, true, width - text.measureText("…"), null)
        return s.take(n).trimEnd() + "…"
    }

    /**
     * The sheet at [progress] (0 shut, 1 open): it slides up by what is left to open and
     * fades in, as the control centre arrives. [icons] and [labels] are the pinned apps',
     * standing straight on the blur with nothing behind them; [withStrip] puts the guest's
     * three buttons above them, [guestOnTop] picking Panel or Resume for the first.
     *
     * [backdrop] is what the glass bends — the blurred ground drawn under the sheet. A
     * node with nothing recorded in it gives glass without a lens. Every rect is moved by
     * the slide rather than the canvas, so a pane of glass bends the blur where it
     * actually stands. A backdrop spanning [rootWidth] by [rootHeight] from the origin is
     * sampled in root space (see GlassPainter.rootSpace).
     *
     * [pressed] (0 to 2, -1 none) is a button under a finger: half lit, as a control
     * centre tile coming on. Close is lit already.
     */
    fun draw(
        canvas: Canvas, progress: Float, backdrop: RenderNode,
        icons: List<Bitmap?>, labels: List<String>,
        guestOnTop: Boolean, withStrip: Boolean, pressed: Int = -1,
        rootWidth: Int = canvas.width, rootHeight: Int = canvas.height,
    ) {
        if (progress <= 0.004f) return
        glass.beginFrame(rootWidth, rootHeight)
        val dy = PortalSheet.slide(progress)
        val alpha = (255 * progress).toInt().coerceIn(0, 255)
        // Faded as one picture, as the control centre is: glass cannot take a paint's alpha.
        val layer = if (alpha < 255) canvas.saveLayerAlpha(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(), alpha) else -1
        try {
            text.color = ink
            val m = text.fontMetrics
            val middle = -(m.ascent + m.descent) / 2f
            if (labels.isEmpty() && !withStrip) {
                canvas.drawText("Pin apps in the app on the front", PortalSheet.gridCenterX, Panel.HEIGHT / 2f + dy, text)
            }
            if (withStrip) {
                val names = PortalSheet.buttonNames(guestOnTop)
                PortalSheet.buttons().forEachIndexed { i, b ->
                    val r = buttonRect
                    r.set(b.left, b.top + dy, b.right, b.bottom + dy)
                    drawButton(canvas, r, i, pressed == i, backdrop)
                    canvas.drawText(ellipsize(names[i], r.width() - 12f), r.centerX(), r.centerY() + middle, text)
                }
            }
            val shown = labels.take(PortalSheet.MAX_APPS)
            iconPaint.alpha = 255
            PortalSheet.cells(shown.size, withStrip).forEachIndexed { i, c ->
                icons.getOrNull(i)?.let { bmp ->
                    val s = 72f; val l = c.centerX - s / 2; val t = c.top + 14f + dy
                    iconRect.set(l, t, l + s, t + s)
                    canvas.drawBitmap(bmp, null, iconRect, iconPaint)
                }
                canvas.drawText(ellipsize(shown[i], PortalSheet.CELL - 8f), c.centerX, c.bottom - 12f + dy, text)
            }
        } finally {
            if (layer >= 0) canvas.restoreToCount(layer)
        }
    }

    /** Button [i] (2 Close) at [rect]: the control centre's, its name drawn over it by [draw]. */
    private fun drawButton(canvas: Canvas, rect: RectF, i: Int, pressed: Boolean, backdrop: RenderNode) {
        val radius = minOf(rect.width(), rect.height()) * CcButton.CORNER
        buttonShape.rewind()
        buttonShape.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val close = i == 2
        val on = if (close) 1f else if (pressed) 0.5f else 0f
        val tint = if (close) Panel.Palette.CRIT and 0x00FFFFFF else 0x00FFFFFF
        val glassy = theme.isGlass && glass.drawPane(canvas, rect, radius, paneTint(rect), shape = buttonShape, source = backdrop)
        // No mark: the name is the button's, drawn over it in the ink.
        CcButton.drawBody(canvas, buttonShape, on, tint, glassy, background = 0, paint = buttonPaint) { _, _ -> }
    }

    private companion object {
        const val SHADOW = 0xCC000000.toInt()
    }
}
