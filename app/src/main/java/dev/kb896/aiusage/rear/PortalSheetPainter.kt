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
 * Paints the App Portal's sheet — the buttons, the pinned apps' icons and names a page at
 * a time with their page dots, and the guest's DPI slider — over a
 * backdrop someone else has drawn. Needs no View, Context or Resources: the panel's view
 * uses it, and so can the shell, which draws into a surface's hardware canvas from
 * app_process. Everything comes in: the [theme], the panel's [face] (app_process has no
 * default typeface, and text drawn without one aborts), the icons as bitmaps, and the
 * node the glass bends, if any.
 *
 * Its buttons are the control centre's (see CcButton): glass under GLASS — bending the
 * backdrop they stand on when there is one to bend, else unbacked panes — and under
 * ORIGINAL outlined as its tiles are. Close is the one filled, in the critical red. The
 * slider's track, thumb and value are the same buttons, pill-shaped and round.
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

    /** The buttons' glyphs (Glyphs.draw sets its colour, style and smoothing). */
    private val glyphPaint = Paint()
    private val markClip = Path()

    init { applyFace() }

    private val buttonRect = RectF()
    private val fillRect = RectF()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonShape = Path()
    private val iconRect = RectF()
    private val markBox = RectF()

    private fun ellipsize(s: String, width: Float): String {
        if (text.measureText(s) <= width) return s
        val n = text.breakText(s, true, width - text.measureText("…"), null)
        return s.take(n).trimEnd() + "…"
    }

    /** The DPI slider as it is to be drawn: the thumb at [thumbDpi], the value saying [label]. */
    class SliderLook(val thumbDpi: Int, val label: String)

    /**
     * The sheet standing [height] high (0 shut to PortalSheet.FULL), laid out as [layout]
     * has it, showing page [page] (fractional while the pages move). [icons] and [labels]
     * are the pinned apps', standing straight on the blur with nothing behind them; with a
     * guest the layout's three buttons stand above them, [guestOnTop] picking Panel or
     * Resume for the first; over a guest on top, the strip with the DPI [slider] along the
     * bottom, [guestIcon] at its start.
     *
     * The contents arrive as the control centre's do (Arrival), each part over its own
     * stage of the rise (PortalSheet.stripStage, .upperStage): fading in as one layer,
     * sliding up into place, each piece — a button, an app's icon and name, the slider, its
     * value — growing about its own middle. The grid's part goes with the sheet's top edge
     * besides (PortalSheet.upperShift), cut off at the top edge and at the strip; the strip
     * rises with the edge until it is all in, then stays at the bottom
     * (PortalSheet.stripShift). So in PEEK the strip alone shows.
     *
     * [backdrop] is what the glass bends — the blurred ground drawn under the sheet, in
     * the canvas's current space. Null, or a node with nothing recorded in it, gives glass
     * without a lens (the shell's sheet: SurfaceFlinger blurs the live rear under it, and
     * this process has no picture of it to bend). Every rect is moved rather than the
     * canvas, so a pane of glass bends the blur where it actually stands. Always sampled in
     * local space (the panes' and the backdrop's are the same), never in root space.
     *
     * [pressed] (0 to 2, -1 none) is a button under a finger: half lit, as a control centre
     * tile coming on. Close is lit already. [PRESS_VALUE] is the slider's value, [PRESS_THUMB]
     * the thumb held.
     *
     * Each button wears a mark left of its name, the two centred together
     * (PortalSheet.labelled): Panel the dashboard glyph, Front flip-to-front, Close a
     * cross — in the theme's own set (Glyphs.draw). Resume wears [guestIcon], the app it
     * brings back, as a small rounded icon; the dashboard glyph without one.
     */
    fun draw(
        canvas: Canvas, height: Float, layout: PortalSheet.Layout, page: Float, backdrop: RenderNode?,
        icons: List<Bitmap?>, labels: List<String>,
        guestOnTop: Boolean, pressed: Int = -1,
        guestIcon: Bitmap? = null, slider: SliderLook? = null,
    ) {
        if (height <= 0.5f) return
        glass.beginFrame(canvas.width, canvas.height)
        text.color = ink
        val top = Panel.HEIGHT - height
        val w = Panel.WIDTH.toFloat()
        val withSlider = layout.slider != null
        val upper = PortalSheet.upperStage(height, withSlider)
        val upperIn = Arrival.of(upper)
        if (upperIn > 0.004f && top < layout.upperBottom) {
            val saved = canvas.save()
            try {
                canvas.clipRect(0f, top, w, layout.upperBottom)
                // Faded as one picture, as the control centre is: glass cannot take a paint's alpha.
                if (upperIn < 0.996f) canvas.saveLayerAlpha(0f, top, w, layout.upperBottom, (255 * upperIn).toInt())
                drawUpper(
                    canvas, PortalSheet.upperShift(height, withSlider), Arrival.scale(upper),
                    layout, page, backdrop, icons, labels, guestOnTop, pressed, guestIcon,
                )
            } finally {
                canvas.restoreToCount(saved)
            }
        }
        val s = layout.slider ?: return
        val look = slider ?: return
        val strip = PortalSheet.stripStage(height)
        val stripIn = Arrival.of(strip)
        if (stripIn <= 0.004f) return
        val saved = canvas.save()
        try {
            if (stripIn < 0.996f) canvas.saveLayerAlpha(0f, top, w, Panel.HEIGHT.toFloat(), (255 * stripIn).toInt())
            drawSlider(canvas, s, PortalSheet.stripShift(height), Arrival.scale(strip), look, pressed, guestIcon, backdrop)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    /** [block] drawn grown to [g] of its size about [cx], [cy]: one piece of the sheet arriving. */
    private inline fun grown(canvas: Canvas, cx: Float, cy: Float, g: Float, block: () -> Unit) {
        if (g >= 0.9999f) { block(); return }
        val saved = canvas.save()
        try {
            canvas.scale(g, g, cx, cy)
            block()
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    /** The buttons, the pages of apps and their dots, [dy] down, each piece grown to [g]. */
    private fun drawUpper(
        canvas: Canvas, dy: Float, g: Float, layout: PortalSheet.Layout, page: Float, backdrop: RenderNode?,
        icons: List<Bitmap?>, labels: List<String>, guestOnTop: Boolean, pressed: Int, guestIcon: Bitmap?,
    ) {
        val m = text.fontMetrics
        val middle = -(m.ascent + m.descent) / 2f
        if (labels.isEmpty() && layout.buttons.isEmpty()) {
            canvas.drawText("Pin apps in the app on the front", layout.emptyX, layout.emptyY + dy, text)
        }
        val names = PortalSheet.buttonNames(guestOnTop)
        layout.buttons.forEachIndexed { i, b ->
            grown(canvas, b.centerX, b.centerY + dy, g) {
                val r = buttonRect
                r.set(b.left, b.top + dy, b.right, b.bottom + dy)
                val close = i == 2
                drawButton(canvas, r, if (close) 1f else if (pressed == i) 0.5f else 0f, close, backdrop)
                val app = if (i == 0 && !guestOnTop) guestIcon else null
                val mark = if (app != null) PortalSheet.APP_MARK else PortalSheet.MARK_H
                val name = ellipsize(names[i], PortalSheet.labelRoom(b, mark))
                val at = PortalSheet.labelled(b, mark, mark, text.measureText(name))
                if (app != null) drawAppMark(canvas, app, at.markLeft, at.markTop + dy, PortalSheet.APP_MARK)
                else drawGlyph(canvas, BUTTON_GLYPHS[i], at.markLeft, at.markTop + dy)
                canvas.drawText(name, at.textCenterX, r.centerY() + middle, text)
            }
        }
        // The page under the finger and the next one in, each its stride across.
        iconPaint.alpha = 255
        val first = kotlin.math.floor(page).toInt()
        for (p in first..first + 1) {
            if (p !in 0 until layout.pages) continue
            val dx = (p - page) * PortalSheet.PAGE_STRIDE
            if (kotlin.math.abs(dx) >= PortalSheet.PAGE_STRIDE) continue
            layout.slots.forEachIndexed { slot, c ->
                val i = layout.appAt(p, slot)
                if (i < 0) return@forEachIndexed
                // The icon and its name, one piece.
                grown(canvas, c.centerX + dx, c.centerY + dy, g) {
                    icons.getOrNull(i)?.let { bmp ->
                        val ic = PortalSheet.icon(c)
                        iconRect.set(ic.left + dx, ic.top + dy, ic.right + dx, ic.bottom + dy)
                        canvas.drawBitmap(bmp, null, iconRect, iconPaint)
                    }
                    labels.getOrNull(i)?.let { name ->
                        canvas.drawText(ellipsize(name, c.width - 8f), c.centerX + dx, PortalSheet.nameBaseline(c) + dy, text)
                    }
                }
            }
        }
        // The dots: the page showing lit, the others faint, lit as it comes in.
        if (layout.dots.isNotEmpty()) {
            dotPaint.setShadowLayer(4f, 0f, 1.5f, SHADOW)
            layout.dots.forEachIndexed { i, d ->
                val near = (1f - kotlin.math.abs(i - page)).coerceIn(0f, 1f)
                dotPaint.color = (ink and 0x00FFFFFF) or (Math.round(255 * (DOT_FAINT + (1f - DOT_FAINT) * near)) shl 24)
                canvas.drawCircle(d.centerX, d.centerY + dy, DOT_R, dotPaint)
            }
        }
    }

    /**
     * The strip's slider, [dy] down: the guest's icon, the track with its fill up to the
     * thumb, the thumb, and the value as a button (a tap on it is Default) — three pieces,
     * each grown to [g] about its middle.
     */
    private fun drawSlider(
        canvas: Canvas, s: PortalSheet.Slider, dy: Float, g: Float, look: SliderLook,
        pressed: Int, guestIcon: Bitmap?, backdrop: RenderNode?,
    ) {
        guestIcon?.let { icon ->
            grown(canvas, s.icon.centerX, s.icon.centerY + dy, g) {
                drawAppMark(canvas, icon, s.icon.left, s.icon.top + dy, PortalSheet.SLIDER_ICON)
            }
        }
        grown(canvas, s.track.centerX, s.track.centerY + dy, g) { drawTrack(canvas, s, dy, look, pressed, backdrop) }
        grown(canvas, s.value.centerX, s.value.centerY + dy, g) {
            val m = text.fontMetrics
            val middle = -(m.ascent + m.descent) / 2f
            buttonRect.set(s.value.left, s.value.top + dy, s.value.right, s.value.bottom + dy)
            drawButton(canvas, buttonRect, if (pressed == PRESS_VALUE) 0.5f else 0f, false, backdrop)
            canvas.drawText(ellipsize(look.label, buttonRect.width() - 12f), buttonRect.centerX(), buttonRect.centerY() + middle, text)
        }
    }

    /** The slider's track, its fill up to the thumb, and the thumb, [dy] down. */
    private fun drawTrack(canvas: Canvas, s: PortalSheet.Slider, dy: Float, look: SliderLook, pressed: Int, backdrop: RenderNode?) {
        val cy = s.track.centerY + dy
        val thumbX = PortalSheet.sliderX(s.track, look.thumbDpi)
        // The track: the thumb's whole travel, a pill.
        buttonRect.set(s.track.left - TRACK_H / 2, cy - TRACK_H / 2, s.track.right + TRACK_H / 2, cy + TRACK_H / 2)
        val glassy = drawButton(canvas, buttonRect, 0f, false, backdrop, radius = TRACK_H / 2)
        // Filled up to the thumb, in the "on" colour.
        fillRect.set(buttonRect.left, buttonRect.top, thumbX, buttonRect.bottom)
        buttonShape.rewind()
        buttonShape.addRoundRect(fillRect, TRACK_H / 2, TRACK_H / 2, Path.Direction.CW)
        buttonPaint.style = Paint.Style.FILL
        buttonPaint.color = if (glassy) CcButton.glassFill(0x00FFFFFF, 1f) else CcButton.flatFill(0x00FFFFFF, 0.6f)
        canvas.drawPath(buttonShape, buttonPaint)
        // The thumb: lit, a little bigger while held.
        val r = PortalSheet.THUMB / 2 * if (pressed == PRESS_THUMB) 1.15f else 1f
        buttonRect.set(thumbX - r, cy - r, thumbX + r, cy + r)
        drawButton(canvas, buttonRect, 1f, false, backdrop, radius = r)
    }

    /** A button's glyph in the ink, its square box's top left at [x], [y]. Shadowed as the words are, under GLASS only:
     *  ORIGINAL's are stacks of pixel rows, and each row's shadow would band the row above it. */
    private fun drawGlyph(canvas: Canvas, icon: PanelIcons.Icon, x: Float, y: Float) {
        if (theme.isGlass) glyphPaint.setShadowLayer(4f, 0f, 1.5f, SHADOW) else glyphPaint.clearShadowLayer()
        Glyphs.draw(canvas, icon, theme, x, y, PortalSheet.MARK_H, ink, glyphPaint)
    }

    /** The guest's own icon, [s] square at [x], [y], its corners rounded. */
    private fun drawAppMark(canvas: Canvas, icon: Bitmap, x: Float, y: Float, s: Float) {
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
     * name drawn over it by [draw]. [close] is Close's, filled in the critical red. Its
     * corners the control centre's, or [radius]. True when it is a pane of glass.
     */
    private fun drawButton(
        canvas: Canvas, rect: RectF, on: Float, close: Boolean, backdrop: RenderNode?,
        radius: Float = minOf(rect.width(), rect.height()) * CcButton.CORNER,
    ): Boolean {
        buttonShape.rewind()
        buttonShape.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val tint = if (close) Panel.Palette.CRIT and 0x00FFFFFF else 0x00FFFFFF
        val glassy = theme.isGlass && glass.drawPane(canvas, rect, radius, paneTint(rect), shape = buttonShape, source = backdrop, local = true)
        // No mark: the name is the button's, drawn over it in the ink.
        CcButton.drawBody(canvas, buttonShape, on, tint, glassy, background = 0, paint = buttonPaint) { _, _ -> }
        return glassy
    }

    companion object {
        /** [draw]'s pressed: the slider's value, and its thumb held. */
        const val PRESS_VALUE = 10
        const val PRESS_THUMB = 11

        private const val SHADOW = 0xCC000000.toInt()

        /** The slider's track, this thick. */
        private const val TRACK_H = 12f

        /** A page dot's radius, and how faint the dots of the pages not showing are. */
        private const val DOT_R = 4f
        private const val DOT_FAINT = 0.35f

        /** The names' size in panel px, before the face's [PanelType.Face.scale]. */
        private const val TEXT_SIZE = 20f

        /** The guest's icon on Resume and on the strip: corners rounded by this much of its side. */
        private const val APP_CORNER = 0.24f

        /** Panel (or Resume without an icon), Front, Close. */
        private val BUTTON_GLYPHS = listOf(PanelIcons.DASHBOARD, PanelIcons.FRONT, PanelIcons.CLOSE)
    }
}
