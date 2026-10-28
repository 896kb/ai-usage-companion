package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.PanelTheme

/**
 * Paints the App Portal's sheet — the buttons, the pinned apps' icons and names — over a
 * backdrop someone else has drawn. Needs no View, Context or Resources: the panel's view
 * uses it, and so can the shell, which draws into a surface's hardware canvas from
 * app_process. Everything comes in: the [theme], the panel's [face] (app_process has no
 * default typeface, and text drawn without one aborts), the icons as bitmaps, and the
 * node the glass bends.
 *
 * Its buttons are the control centre's (see CcButton): glass under GLASS, bending the
 * backdrop they stand on, and under ORIGINAL outlined as its tiles are. Close is the one
 * filled, in the critical red.
 *
 * Laid out by PortalSheet, in panel pixels.
 *
 * Not thread-safe; all painters in a process must draw one at a time (ShellSheet
 * serialises with its DRAW lock) — they share Glass's shaders (uniforms, highlight matrix).
 */
class PortalSheetPainter(var theme: PanelTheme, face: PanelType.Face) {

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
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 1.5f, SHADOW)
    }

    /**
     * The panel's face, as RearDialView sets its body text: at [PanelType.BODY] (a variable
     * face takes it on its axis) and [TEXT_SIZE] times the face's [PanelType.Face.scale] —
     * Outfit under GLASS, Departure Mono under ORIGINAL, or the one chosen in General.
     */
    var face: PanelType.Face = face
        set(value) {
            if (value === field) return
            field = value
            applyFace()
        }

    private fun applyFace() {
        face.set(text, PanelType.BODY)
        text.textSize = TEXT_SIZE * face.scale
    }

    /** The strip's glyphs (Glyphs.draw sets its colour, style and smoothing). */
    private val glyphPaint = Paint()
    private val markClip = Path()

    init { applyFace() }

    private val buttonRect = RectF()
    private val buttonShape = Path()
    private val iconRect = RectF()
    private val markBox = RectF()

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
     * [backdrop] is what the glass bends — the blurred ground drawn under the sheet, in
     * the canvas's current space. A node with nothing recorded in it gives glass without a
     * lens. Every rect is moved by the slide rather than the canvas, so a pane of glass
     * bends the blur where it actually stands. Always sampled in local space (the panes'
     * and the backdrop's are the same), never in root space: on the 17 Pro the panel's
     * canvas is the root at identity and both mappings agree, and the shell's surface has
     * no root of anyone else's to agree with.
     *
     * [pressed] (0 to 2, -1 none) is a button under a finger: half lit, as a control
     * centre tile coming on. Close is lit already. From [PortalSheet.DPI_PRESS] up it is a
     * button of the DPI row.
     *
     * [dpiApp] (-1 none) has its DPI row open — `-  [dpiLabel]  +  Default`, under or over
     * its cell as PortalSheet.dpiRow places it — its cell lit as a pressed tile, and the
     * cells the row stands over left out.
     *
     * Each strip button wears a mark left of its name, the two centred together
     * (PortalSheet.labelled): Panel the dashboard glyph, Front flip-to-front, Close a
     * cross — in the theme's own set (Glyphs.draw). Resume wears [guestIcon], the app it
     * brings back, as a small rounded icon; the dashboard glyph without one.
     */
    fun draw(
        canvas: Canvas, progress: Float, backdrop: RenderNode,
        icons: List<Bitmap?>, labels: List<String>,
        guestOnTop: Boolean, withStrip: Boolean, pressed: Int = -1,
        dpiApp: Int = -1, dpiLabel: String = "",
        guestIcon: Bitmap? = null,
    ) {
        if (progress <= 0.004f) return
        glass.beginFrame(canvas.width, canvas.height)
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
                    val close = i == 2
                    drawButton(canvas, r, if (close) 1f else if (pressed == i) 0.5f else 0f, close, backdrop)
                    val app = if (i == 0 && !guestOnTop) guestIcon else null
                    val mark = if (app != null) PortalSheet.APP_MARK else PortalSheet.MARK_H
                    val name = ellipsize(names[i], PortalSheet.labelRoom(b, mark))
                    val at = PortalSheet.labelled(b, mark, mark, text.measureText(name))
                    if (app != null) drawAppMark(canvas, app, at.markLeft, at.markTop + dy)
                    else drawGlyph(canvas, BUTTON_GLYPHS[i], at.markLeft, at.markTop + dy)
                    canvas.drawText(name, at.textCenterX, r.centerY() + middle, text)
                }
            }
            val shown = labels.take(PortalSheet.MAX_APPS)
            iconPaint.alpha = 255
            val row = if (dpiApp >= 0) PortalSheet.dpiRow(dpiApp, shown.size, withStrip) else null
            val covered = if (row != null) PortalSheet.dpiCovered(dpiApp, shown.size, withStrip) else emptySet()
            PortalSheet.cells(shown.size, withStrip).forEachIndexed { i, c ->
                if (i in covered) return@forEachIndexed
                if (row != null && i == dpiApp) {
                    buttonRect.set(c.left, c.top + dy, c.right, c.bottom + dy)
                    drawButton(canvas, buttonRect, 0.5f, false, backdrop)
                }
                icons.getOrNull(i)?.let { bmp ->
                    val s = 72f; val l = c.centerX - s / 2; val t = c.top + 14f + dy
                    iconRect.set(l, t, l + s, t + s)
                    canvas.drawBitmap(bmp, null, iconRect, iconPaint)
                }
                canvas.drawText(ellipsize(shown[i], PortalSheet.CELL - 8f), c.centerX, c.bottom - 12f + dy, text)
            }
            row?.forEachIndexed { i, b ->
                val part = PortalSheet.DpiPart.entries[i]
                val r = buttonRect
                r.set(b.left, b.top + dy, b.right, b.bottom + dy)
                // The value is words on the blur, not a button: nothing happens on it.
                if (part != PortalSheet.DpiPart.VALUE) {
                    drawButton(canvas, r, if (pressed == PortalSheet.dpiPress(part)) 0.5f else 0f, false, backdrop)
                }
                val word = when (part) {
                    PortalSheet.DpiPart.MINUS -> "-"
                    PortalSheet.DpiPart.VALUE -> dpiLabel
                    PortalSheet.DpiPart.PLUS -> "+"
                    PortalSheet.DpiPart.DEFAULT -> "Default"
                }
                canvas.drawText(ellipsize(word, r.width() - 12f), r.centerX(), r.centerY() + middle, text)
            }
        } finally {
            if (layer >= 0) canvas.restoreToCount(layer)
        }
    }

    /** A strip glyph in the ink, its square box's top left at [x], [y]. Shadowed as the words are, under GLASS only:
     *  ORIGINAL's are stacks of pixel rows, and each row's shadow would band the row above it. */
    private fun drawGlyph(canvas: Canvas, icon: PanelIcons.Icon, x: Float, y: Float) {
        if (theme.isGlass) glyphPaint.setShadowLayer(4f, 0f, 1.5f, SHADOW) else glyphPaint.clearShadowLayer()
        Glyphs.draw(canvas, icon, theme, x, y, PortalSheet.MARK_H, ink, glyphPaint)
    }

    /** The guest's own icon, [PortalSheet.APP_MARK] square at [x], [y], its corners rounded. */
    private fun drawAppMark(canvas: Canvas, icon: Bitmap, x: Float, y: Float) {
        val s = PortalSheet.APP_MARK
        markBox.set(x, y, x + s, y + s)
        markClip.rewind()
        markClip.addRoundRect(markBox, s * APP_CORNER, s * APP_CORNER, Path.Direction.CW)
        val saved = canvas.save()
        try {
            canvas.clipPath(markClip)
            iconPaint.alpha = 255
            canvas.drawBitmap(icon, null, markBox, iconPaint)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    /**
     * A button at [rect], lit [on] (0 off, 0.5 pressed, 1 on): the control centre's, its
     * name drawn over it by [draw]. [close] is Close's, filled in the critical red.
     */
    private fun drawButton(canvas: Canvas, rect: RectF, on: Float, close: Boolean, backdrop: RenderNode) {
        val radius = minOf(rect.width(), rect.height()) * CcButton.CORNER
        buttonShape.rewind()
        buttonShape.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val tint = if (close) Panel.Palette.CRIT and 0x00FFFFFF else 0x00FFFFFF
        val glassy = theme.isGlass && glass.drawPane(canvas, rect, radius, paneTint(rect), shape = buttonShape, source = backdrop, local = true)
        // No mark: the name is the button's, drawn over it in the ink.
        CcButton.drawBody(canvas, buttonShape, on, tint, glassy, background = 0, paint = buttonPaint) { _, _ -> }
    }

    private companion object {
        const val SHADOW = 0xCC000000.toInt()

        /** The names' size in panel px, before the face's [PanelType.Face.scale]. */
        const val TEXT_SIZE = 20f

        /** The guest's icon on Resume: corners rounded by this much of its side. */
        const val APP_CORNER = 0.24f

        /** Panel (or Resume without an icon), Front, Close. */
        val BUTTON_GLYPHS = listOf(PanelIcons.DASHBOARD, PanelIcons.FRONT, PanelIcons.CLOSE)
    }
}
