package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.PanelTheme

/**
 * Paints the App Portal's sheet — the buttons, the pinned apps' icons and names a page at
 * a time with their page dots, and the guest's DPI slider — over the blur SurfaceFlinger
 * lays under the shell's layer. Needs no View, Context or Resources: the shell draws with
 * it into a surface's hardware canvas from app_process, and the front's preview of the
 * portal (ui.PortalSheetPreview) into its view's. Everything comes in: the [theme],
 * the panel's [face] (app_process has no default typeface, and text drawn without one
 * aborts), and the icons as bitmaps.
 *
 * Its buttons are the control centre's (see CcButton): unbacked glass panes under GLASS —
 * there is no picture of the rear here to bend — and under ORIGINAL outlined as its tiles
 * are. Close is the one filled, in the critical red. The slider is the front's: its glass
 * slider under GLASS, its flat one under ORIGINAL (drawTrack); its value is a button.
 *
 * Laid out by PortalSheet, in panel pixels.
 *
 * Not thread-safe; all painters in a process must draw one at a time (ShellSheet
 * serialises with its DRAW lock; the front draws them all on its main thread) — they share
 * Glass's shaders (uniforms, highlight matrix).
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
     * The sheet at [progress] (0 shut, 1 open), its contents scrolled up by [scroll] (0 to
     * PortalSheet.STRIP, the slider's row coming in under them), laid out as [layout] has
     * it, showing page [page] (fractional while the pages move). [icons] and [labels] are
     * the pinned apps', standing straight on the blur with nothing behind them, their names
     * left out without [names] (the layout's icons then grow into their room); with a guest
     * the layout's three buttons stand above them, [guestOnTop] picking Panel or Resume for
     * the first, and the DPI [slider] under them, [guestIcon] at its start.
     *
     * The contents arrive as the control centre's do (Arrival, on [progress]): fading in as
     * one layer, sliding up into place (PortalSheet.arrivalShift), each piece — a button, an
     * app's icon and name, the slider, its value — growing about its own middle; the
     * buttons and the grid on the one curve. Pages sliding sideways fade into the sides,
     * and the contents scrolled up fade into the top edge, sharp to blurred to nothing
     * (EdgeFade, [faded]).
     *
     * Glass panes are unbacked — the shell has no picture of the rear to bend — and every
     * rect is moved rather than the canvas.
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
        canvas: Canvas, progress: Float, scroll: Float, layout: PortalSheet.Layout, page: Float,
        icons: List<Bitmap?>, labels: List<String>,
        guestOnTop: Boolean, pressed: Int = -1,
        guestIcon: Bitmap? = null, slider: SliderLook? = null, names: Boolean = true,
    ) {
        val arrived = Arrival.of(progress)
        if (arrived <= 0.004f) return
        glass.beginFrame(canvas.width, canvas.height)
        text.color = ink
        val w = Panel.WIDTH.toFloat(); val h = Panel.HEIGHT.toFloat()
        val g = Arrival.scale(progress)
        val dy = PortalSheet.arrivalShift(progress) - scroll
        val saved = canvas.save()
        try {
            // Faded as one picture, as the control centre is: glass cannot take a paint's alpha.
            if (arrived < 0.996f) canvas.saveLayerAlpha(0f, 0f, w, h, (255 * arrived).toInt())
            val paging = kotlin.math.abs(page - Math.round(page)) > 0.001f
            faded(canvas, sides = paging, top = PortalSheet.topFade(scroll)) { c ->
                drawUpper(c, dy, g, layout, page, icons, labels, guestOnTop, pressed, guestIcon, names)
            }
            val s = layout.slider
            if (s != null && slider != null && scroll > 0.5f) drawSlider(canvas, s, dy, g, slider, pressed, guestIcon)
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

    /**
     * [block] drawn within the pages' width, faded at the [sides] (pages moving) and at the
     * [top] (on at that strength: the scroll's) as EdgeFade has it: sharp, then a blurred
     * copy over it on the way, then nothing. With neither, drawn as it is. The blur needs a
     * hardware canvas (a RenderNode with a RenderEffect); on a software one the fade alone.
     */
    private fun faded(canvas: Canvas, sides: Boolean, top: Float, block: (Canvas) -> Unit) {
        val l = PortalSheet.PAGES_LEFT; val r = PortalSheet.PAGES_RIGHT
        val w = Panel.WIDTH.toFloat(); val h = Panel.HEIGHT.toFloat()
        val clip = canvas.save()
        try {
            canvas.clipRect(l, 0f, r, h)
            if (!sides && top <= 0.001f) { block(canvas); return }
            if (!canvas.isHardwareAccelerated) {
                canvas.saveLayer(l, 0f, r, h, null)
                block(canvas)
                mask(canvas, sides, top, blurred = false)
                return
            }
            val node = content
            node.setPosition(0, 0, w.toInt(), h.toInt())
            val rc = node.beginRecording(w.toInt(), h.toInt())
            try { block(rc) } finally { node.endRecording() }
            // Sharp: whole inside, gone at the edges.
            var layer = canvas.saveLayer(l, 0f, r, h, null)
            canvas.drawRenderNode(node)
            mask(canvas, sides, top, blurred = false)
            canvas.restoreToCount(layer)
            // Blurred, over it, on the way out.
            blurredContent.setPosition(0, 0, w.toInt(), h.toInt())
            blurredContent.setRenderEffect(blurEffect)
            val bc = blurredContent.beginRecording(w.toInt(), h.toInt())
            try { bc.drawRenderNode(node) } finally { blurredContent.endRecording() }
            layer = canvas.saveLayer(l, 0f, r, h, null)
            canvas.drawRenderNode(blurredContent)
            mask(canvas, sides, top, blurred = true)
            canvas.restoreToCount(layer)
        } finally {
            canvas.restoreToCount(clip)
        }
    }

    /**
     * The masks over a layer of the contents, DST_IN: the sides' across the pages' width
     * and the top's down from the top edge — for the sharp copy, or the [blurred] one, which
     * is blurred at one edge only (the sides' while pages move) and fades as the sharp does
     * at the other.
     */
    private fun mask(canvas: Canvas, sides: Boolean, top: Float, blurred: Boolean) {
        val l = PortalSheet.PAGES_LEFT; val r = PortalSheet.PAGES_RIGHT
        val h = Panel.HEIGHT.toFloat()
        if (sides) {
            maskPaint.shader = sideShader(blurred)
            canvas.drawRect(l, 0f, r, h, maskPaint)
        }
        if (top > 0.001f) {
            // The blurred copy is the sides' when they fade; the top's only without them.
            val colours = if (blurred && !sides) EdgeFade.blurMask(top) else EdgeFade.sharpMask(top)
            maskPaint.shader = LinearGradient(0f, 0f, 0f, EdgeFade.BAND, colours, EdgeFade.positions(), Shader.TileMode.CLAMP)
            canvas.drawRect(l, 0f, r, h, maskPaint)
        }
    }

    /** The sides' mask across the pages' width: the band's stops at the left, mirrored at the right. */
    private fun sideShader(blurred: Boolean): Shader {
        cachedSides[if (blurred) 1 else 0]?.let { return it }
        val l = PortalSheet.PAGES_LEFT; val r = PortalSheet.PAGES_RIGHT
        val band = EdgeFade.BAND / (r - l)
        val stops = EdgeFade.positions()
        val colours = if (blurred) EdgeFade.blurMask() else EdgeFade.sharpMask()
        val n = stops.size
        val pos = FloatArray(2 * n) { i -> if (i < n) stops[i] * band else 1f - stops[2 * n - 1 - i] * band }
        val col = IntArray(2 * n) { i -> if (i < n) colours[i] else colours[2 * n - 1 - i] }
        return LinearGradient(l, 0f, r, 0f, col, pos, Shader.TileMode.CLAMP).also { cachedSides[if (blurred) 1 else 0] = it }
    }

    private val content = RenderNode("portal-sheet-content")
    private val blurredContent = RenderNode("portal-sheet-blurred")
    private val blurEffect = RenderEffect.createBlurEffect(EdgeFade.BLUR, EdgeFade.BLUR, Shader.TileMode.DECAL)
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val cachedSides = arrayOfNulls<Shader>(2)

    /** The buttons, the pages of apps and their dots, [dy] down, each piece grown to [g]. */
    private fun drawUpper(
        canvas: Canvas, dy: Float, g: Float, layout: PortalSheet.Layout, page: Float,
        icons: List<Bitmap?>, labels: List<String>, guestOnTop: Boolean, pressed: Int, guestIcon: Bitmap?,
        names: Boolean,
    ) {
        val m = text.fontMetrics
        val middle = -(m.ascent + m.descent) / 2f
        if (labels.isEmpty() && layout.buttons.isEmpty()) {
            canvas.drawText("Pin apps in the app on the front", layout.emptyX, layout.emptyY + dy, text)
        }
        val buttonNames = PortalSheet.buttonNames(guestOnTop)
        layout.buttons.forEachIndexed { i, b ->
            grown(canvas, b.centerX, b.centerY + dy, g) {
                val r = buttonRect
                r.set(b.left, b.top + dy, b.right, b.bottom + dy)
                val close = i == 2
                drawButton(canvas, r, if (close) 1f else if (pressed == i) 0.5f else 0f, close)
                val app = if (i == 0 && !guestOnTop) guestIcon else null
                val mark = if (app != null) PortalSheet.APP_MARK else PortalSheet.MARK_H
                val name = ellipsize(buttonNames[i], PortalSheet.labelRoom(b, mark))
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
                        val ic = PortalSheet.icon(c, names)
                        iconRect.set(ic.left + dx, ic.top + dy, ic.right + dx, ic.bottom + dy)
                        canvas.drawBitmap(bmp, null, iconRect, iconPaint)
                    }
                    if (names) labels.getOrNull(i)?.let { name ->
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
     * The slider's row, [dy] down: the guest's icon, the track with its fill up to the
     * thumb, the thumb, and the value as a button (a tap on it is Default) — three pieces,
     * each grown to [g] about its middle.
     */
    private fun drawSlider(
        canvas: Canvas, s: PortalSheet.Slider, dy: Float, g: Float, look: SliderLook,
        pressed: Int, guestIcon: Bitmap?,
    ) {
        guestIcon?.let { icon ->
            grown(canvas, s.icon.centerX, s.icon.centerY + dy, g) {
                drawAppMark(canvas, icon, s.icon.left, s.icon.top + dy, PortalSheet.SLIDER_ICON)
            }
        }
        grown(canvas, s.track.centerX, s.track.centerY + dy, g) { drawTrack(canvas, s, dy, look, pressed) }
        grown(canvas, s.value.centerX, s.value.centerY + dy, g) {
            val m = text.fontMetrics
            val middle = -(m.ascent + m.descent) / 2f
            buttonRect.set(s.value.left, s.value.top + dy, s.value.right, s.value.bottom + dy)
            drawButton(canvas, buttonRect, if (pressed == PRESS_VALUE) 0.5f else 0f, false)
            canvas.drawText(ellipsize(look.label, buttonRect.width() - 12f), buttonRect.centerX(), buttonRect.centerY() + middle, text)
        }
    }

    /**
     * The slider's track, its fill up to the thumb, and the thumb, [dy] down — the front's
     * sliders, at the rear's size. GLASS: GlassSlider's — a capsule track in its grey, filled
     * in the front's accent, and a pill thumb, white at rest and, held, one and a half times
     * as big and clear glass. ORIGINAL: the front's PanelSeekBar — square track in the
     * panel's TRACK, filled and thumbed in its green.
     */
    private fun drawTrack(canvas: Canvas, s: PortalSheet.Slider, dy: Float, look: SliderLook, pressed: Int) {
        val cy = s.track.centerY + dy
        val thumbX = PortalSheet.sliderX(s.track, look.thumbDpi)
        val held = pressed == PRESS_THUMB
        val glassy = theme.isGlass
        val th = if (glassy) TRACK_H else TRACK_H_FLAT
        val radius = if (glassy) th / 2 else 0f
        buttonPaint.style = Paint.Style.FILL
        fillRect.set(s.track.left, cy - th / 2, s.track.right, cy + th / 2)
        buttonPaint.color = if (glassy) GLASS_TRACK else Panel.Palette.TRACK
        canvas.drawRoundRect(fillRect, radius, radius, buttonPaint)
        fillRect.right = thumbX
        buttonPaint.color = if (glassy) GLASS_ACCENT else Panel.Palette.WORK
        canvas.drawRoundRect(fillRect, radius, radius, buttonPaint)
        if (!glassy) {
            val half = PortalSheet.THUMB_H / 2 * if (held) FLAT_HELD else 1f
            buttonPaint.color = Panel.Palette.WORK
            canvas.drawRect(thumbX - half, cy - half, thumbX + half, cy + half, buttonPaint)
            return
        }
        val scale = if (held) GLASS_HELD else 1f
        val tw = PortalSheet.THUMB * scale; val ht = PortalSheet.THUMB_H * scale
        buttonRect.set(thumbX - tw / 2, cy - ht / 2, thumbX + tw / 2, cy + ht / 2)
        drawButton(canvas, buttonRect, 0f, false, radius = ht / 2)
        // Plain white at rest, clear glass while held — GlassSlider's thumb.
        if (!held) {
            buttonPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawRoundRect(buttonRect, ht / 2, ht / 2, buttonPaint)
        }
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
        canvas: Canvas, rect: RectF, on: Float, close: Boolean,
        radius: Float = minOf(rect.width(), rect.height()) * CcButton.CORNER,
    ): Boolean {
        buttonShape.rewind()
        buttonShape.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val tint = if (close) Panel.Palette.CRIT and 0x00FFFFFF else 0x00FFFFFF
        val glassy = theme.isGlass && glass.drawPane(canvas, rect, radius, paneTint(rect), shape = buttonShape, source = null, local = true)
        // No mark: the name is the button's, drawn over it in the ink.
        CcButton.drawBody(canvas, buttonShape, on, tint, glassy, background = 0, paint = buttonPaint) { _, _ -> }
        return glassy
    }

    companion object {
        /** [draw]'s pressed: the slider's value, and its thumb held. */
        const val PRESS_VALUE = 10
        const val PRESS_THUMB = 11

        private const val SHADOW = 0xCC000000.toInt()

        /** The slider's track, this thick: GlassSlider's 6 dp to its 24 dp thumb; PanelSeekBar's to its 18 dp one. */
        private const val TRACK_H = 7f
        private const val TRACK_H_FLAT = 9f

        /** GlassSlider's track and fill over a dark ground (ui.glass.Ink.track, .ACCENT). */
        private const val GLASS_TRACK = 0x5C787880
        private const val GLASS_ACCENT = 0xFFE7FF00.toInt()

        /** A held thumb's size: GlassSlider's swell; the flat square's a little. */
        private const val GLASS_HELD = 1.5f
        private const val FLAT_HELD = 1.15f

        /** A page dot's radius, and how faint the dots of the pages not showing are. */
        private const val DOT_R = 4f
        private const val DOT_FAINT = 0.35f

        /** The names' size in panel px, before the face's [PanelType.Face.scale]. */
        private const val TEXT_SIZE = 20f

        /** The guest's icon on Resume and on the slider's row: corners rounded by this much of its side. */
        private const val APP_CORNER = 0.24f

        /** Panel (or Resume without an icon), Front, Close. */
        private val BUTTON_GLYPHS = listOf(PanelIcons.DASHBOARD, PanelIcons.FRONT, PanelIcons.CLOSE)
    }
}
