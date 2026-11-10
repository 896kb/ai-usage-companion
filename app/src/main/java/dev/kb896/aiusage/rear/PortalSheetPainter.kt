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
 * a time with their page dots, and the guest's DPI slider and camera switch — over the blur SurfaceFlinger
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

    /** The apps as a dome turned to this (PortalDome), or null for the grid: set before [draw]. */
    var dome: PortalDome.Pose? = null
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

    /**
     * The back's lock as it is to be drawn (BackLock): [title] over a dot for each of the [entered]
     * characters, the keypad, and [message] under it — red when [wrong]. [pressedKey] is the key
     * under a finger (PortalSheet.keypad's index), -1 none.
     */
    class LockLook(
        val title: String, val entered: Int, val message: String?, val wrong: Boolean, val pressedKey: Int,
        /** The keypad shown: a PIN's. Without it only the words (a lock the back cannot take yet). */
        val keys: Boolean = true,
        /** What takes the code: a PIN's keypad, a pattern's dots, a password's keyboard. */
        val kind: BackLock.Kind = BackLock.Kind.PIN,
        /** A pattern's dots so far, in order, and where the finger is (null: lifted). */
        val pattern: List<Int> = emptyList(),
        val finger: Pair<Float, Float>? = null,
        /** The keyboard's page (PortalSheet.KB_LOWER and the rest). */
        val page: Int = PortalSheet.KB_LOWER,
        /**
         * The code was right: how far through its leaving the lock is, 0 to 1 ([drawUnlock]) —
         * its keys run together into one pill, a tick is drawn in it, and it goes. Under 0: not
         * leaving.
         */
        val unlocking: Float = -1f,
    ) {
        fun pressing(key: Int) = LockLook(title, entered, message, wrong, key, keys, kind, pattern, finger, page, unlocking)
        fun unlocking(t: Float) = LockLook(title, entered, message, wrong, -1, keys, kind, pattern, finger, page, t)
    }

    /** A slider as it is to be drawn: the thumb [fraction] of the way along, the value saying [label]. */
    class SliderLook(val fraction: Float, val label: String)

    /**
     * The sheet at [progress] (0 shut, 1 open), its contents scrolled up by [scroll] (0 to
     * PortalSheet.STRIP, the slider's row coming in under them), laid out as [layout] has
     * it, showing page [page] (fractional while the pages move). [icons] and [labels] are
     * the pinned apps', standing straight on the blur with nothing behind them, their names
     * left out without [names] (the layout's icons then grow into their room); with a guest
     * the layout's three buttons stand above them, [guestOnTop] picking Panel or Resume for
     * the first, and the DPI [slider] under them, [guestIcon] at its start, and under that
     * the "Keep right of the cameras" switch, on with [keepRight].
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
        width: SliderLook? = null,
        keepRight: Boolean = false,
        keepRightHidden: Boolean = false,
        lock: LockLook? = null,
        keypad: PortalSheet.Keypad? = null,
        grid: PortalSheet.PatternGrid? = null,
        keyboard: PortalSheet.Keyboard? = null,
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
            if (lock != null && keypad != null) {
                when {
                    lock.kind == BackLock.Kind.PATTERN && grid != null -> drawPattern(canvas, keypad, grid, dy + scroll, lock)
                    lock.kind == BackLock.Kind.PASSWORD && keyboard != null -> drawKeyboard(canvas, keyboard, dy + scroll, g, lock)
                    else -> drawLock(canvas, keypad, dy + scroll, g, lock)
                }
                return
            }
            // With more apps than a page holds they scroll sideways and stop anywhere: the
            // sides always fade, so a column part-way in is not cut off hard.
            // The dome fades its own edges, all four (drawDome): not the grid's sides over them.
            val paging = layout.pages > 1 && dome == null
            faded(canvas, layout.pagesLeft, sides = paging, top = PortalSheet.topFade(scroll)) { c ->
                drawUpper(c, dy, g, layout, page, icons, labels, guestOnTop, pressed, guestIcon, names)
            }
            val s = layout.slider
            if (s != null && slider != null && scroll > 0.5f) drawSlider(canvas, s, dy, g, slider, pressed, PRESS_VALUE, PRESS_THUMB) { c, box ->
                guestIcon?.let { drawAppMark(c, it, box.left, box.top, PortalSheet.SLIDER_ICON) }
            }
            val wr = layout.width
            if (wr != null && width != null && scroll > 0.5f) drawSlider(canvas, wr, dy, g, width, pressed, PRESS_WIDTH_VALUE, PRESS_WIDTH_THUMB) { c, box ->
                drawWidthMark(c, box)
            }
            val t = layout.toggle
            if (t != null && !keepRightHidden && scroll > 0.5f) drawToggle(canvas, t, dy, g, keepRight, pressed)
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
     * [block] drawn within the pages' width (from [l], the layout's, to PortalSheet.PAGES_RIGHT), faded at the [sides] (pages moving) and at the
     * [top] (on at that strength: the scroll's) as EdgeFade has it: sharp, then a blurred
     * copy over it on the way, then nothing. With neither, drawn as it is. The blur needs a
     * hardware canvas (a RenderNode with a RenderEffect); on a software one the fade alone.
     */
    private fun faded(canvas: Canvas, l: Float, sides: Boolean, top: Float, block: (Canvas) -> Unit) {
        val r = PortalSheet.PAGES_RIGHT
        val w = Panel.WIDTH.toFloat(); val h = Panel.HEIGHT.toFloat()
        val clip = canvas.save()
        try {
            canvas.clipRect(l, 0f, r, h)
            if (!sides && top <= 0.001f) { block(canvas); return }
            if (!canvas.isHardwareAccelerated) {
                canvas.saveLayer(l, 0f, r, h, null)
                block(canvas)
                mask(canvas, l, sides, top, blurred = false)
                return
            }
            val node = content
            node.setPosition(0, 0, w.toInt(), h.toInt())
            val rc = node.beginRecording(w.toInt(), h.toInt())
            try { block(rc) } finally { node.endRecording() }
            // Sharp: whole inside, gone at the edges.
            var layer = canvas.saveLayer(l, 0f, r, h, null)
            canvas.drawRenderNode(node)
            mask(canvas, l, sides, top, blurred = false)
            canvas.restoreToCount(layer)
            // Blurred, over it, on the way out.
            blurredContent.setPosition(0, 0, w.toInt(), h.toInt())
            blurredContent.setRenderEffect(blurEffect)
            val bc = blurredContent.beginRecording(w.toInt(), h.toInt())
            try { bc.drawRenderNode(node) } finally { blurredContent.endRecording() }
            layer = canvas.saveLayer(l, 0f, r, h, null)
            canvas.drawRenderNode(blurredContent)
            mask(canvas, l, sides, top, blurred = true)
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
    private fun mask(canvas: Canvas, l: Float, sides: Boolean, top: Float, blurred: Boolean) {
        val r = PortalSheet.PAGES_RIGHT
        val h = Panel.HEIGHT.toFloat()
        if (sides) {
            maskPaint.shader = sideShader(l, blurred)
            canvas.drawRect(l, 0f, r, h, maskPaint)
        }
        if (top > 0.001f) {
            // The blurred copy is the sides' when they fade; the top's only without them.
            val colours = if (blurred && !sides) EdgeFade.blurMask(top) else EdgeFade.sharpMask(top)
            maskPaint.shader = LinearGradient(0f, 0f, 0f, EdgeFade.BAND, colours, EdgeFade.positions(), Shader.TileMode.CLAMP)
            canvas.drawRect(l, 0f, r, h, maskPaint)
        }
    }

    /** The sides' mask across the pages' width from [l]: the band's stops at the left, mirrored at the right. */
    private fun sideShader(l: Float, blurred: Boolean): Shader {
        if (cachedSidesLeft != l) { cachedSides.fill(null); cachedSidesLeft = l }
        cachedSides[if (blurred) 1 else 0]?.let { return it }
        val r = PortalSheet.PAGES_RIGHT
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
    /** The pages' left edge [cachedSides] were made for. */
    private var cachedSidesLeft = Float.NaN

    /** The buttons, the pages of apps and their dots, [dy] down, each piece grown to [g]. */
    private fun drawUpper(
        canvas: Canvas, dy: Float, g: Float, layout: PortalSheet.Layout, page: Float,
        icons: List<Bitmap?>, labels: List<String>, guestOnTop: Boolean, pressed: Int, guestIcon: Bitmap?,
        names: Boolean,
    ) {
        // The dome is the whole of the sheet's upper part: under the buttons, which stand over it.
        dome?.let { pose -> drawDome(canvas, dy, g, layout, pose, icons) }
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
        // The dome was drawn first, under the buttons (above): no pages.
        if (dome != null) return
        // Every page any of which shows, each its stride across: the one the scroll is on,
        // and its neighbours either side (a page is no wider than what shows).
        iconPaint.alpha = 255
        val first = kotlin.math.floor(page).toInt()
        for (p in first - 1..first + 2) {
            if (p !in 0 until layout.pages) continue
            val dx = (p - page) * layout.pageStride
            layout.slots.forEachIndexed { slot, c ->
                val i = layout.appAt(p, slot)
                if (i < 0) return@forEachIndexed
                // Wholly past the sides' fades: not drawn.
                if (c.right + dx < layout.pagesLeft || c.left + dx > PortalSheet.PAGES_RIGHT) return@forEachIndexed
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
    /**
     * A slider's row, [dy] down, grown to [g]: [mark] in its icon's box, the track, and the value,
     * lit while [pressed] is [pressValue], the thumb held while it is [pressThumb].
     */
    private inline fun drawSlider(
        canvas: Canvas, s: PortalSheet.Slider, dy: Float, g: Float, look: SliderLook,
        pressed: Int, pressValue: Int, pressThumb: Int, mark: (Canvas, android.graphics.RectF) -> Unit,
    ) {
        grown(canvas, s.icon.centerX, s.icon.centerY + dy, g) {
            fillRect.set(s.icon.left, s.icon.top + dy, s.icon.right, s.icon.bottom + dy)
            mark(canvas, fillRect)
        }
        grown(canvas, s.track.centerX, s.track.centerY + dy, g) { drawTrack(canvas, s, dy, look, pressed == pressThumb) }
        grown(canvas, s.value.centerX, s.value.centerY + dy, g) {
            val m = text.fontMetrics
            val middle = -(m.ascent + m.descent) / 2f
            buttonRect.set(s.value.left, s.value.top + dy, s.value.right, s.value.bottom + dy)
            drawButton(canvas, buttonRect, if (pressed == pressValue) 0.5f else 0f, false)
            canvas.drawText(ellipsize(look.label, buttonRect.width() - 12f), buttonRect.centerX(), buttonRect.centerY() + middle, text)
        }
    }

    /**
     * The lock, [dy] down, grown to [g]: its words, a dot for each character typed (an empty ring
     * for none yet), the keys as the sheet's buttons — back and enter as glyphs — and the message.
     */
    private fun drawLock(canvas: Canvas, k: PortalSheet.Keypad, dy: Float, g: Float, look: LockLook) {
        if (look.unlocking >= 0f) { drawUnlock(canvas, k.keys, dy, look.unlocking, round = true); return }
        drawLockLine(canvas, k.centerX, k.infoRight - k.infoLeft, k.titleY + dy, k.dotsY + dy, look)
        // The keys: circles of the sheet's glass, their figures large.
        val size = text.textSize
        text.textSize = k.keys[0].height * KEY_TEXT
        val km = text.fontMetrics
        val keyMiddle = -(km.ascent + km.descent) / 2f
        k.keys.forEachIndexed { i, box ->
            grown(canvas, box.centerX, box.centerY + dy, g) {
                buttonRect.set(box.left, box.top + dy, box.right, box.bottom + dy)
                drawButton(canvas, buttonRect, if (look.pressedKey == i) 0.5f else 0f, false, radius = buttonRect.height() / 2, oval = true)
                val label = when (i) {
                    PortalSheet.KEY_BACK -> "⌫"
                    PortalSheet.KEY_OK -> "OK"
                    else -> PortalSheet.keyDigit(i).toString()
                }
                text.color = ink
                canvas.drawText(label, buttonRect.centerX(), buttonRect.centerY() + keyMiddle, text)
            }
        }
        text.textSize = size
    }

    /**
     * The lock's one line at the top, small: what went wrong (red), else a dot for each character
     * typed, else what to do. [textY] the words' baseline, [dotsY] the dots' middle.
     */
    private fun drawLockLine(canvas: Canvas, cx: Float, room: Float, textY: Float, dotsY: Float, look: LockLook) {
        val size0 = text.textSize
        text.textSize = size0 * TITLE_SCALE
        when {
            look.message != null -> {
                text.color = if (look.wrong) Panel.Palette.CRIT else ink
                canvas.drawText(ellipsize(look.message, room), cx, textY, text)
            }
            look.entered > 0 -> {
                val step = LOCK_DOT_R * 3f
                val shown = minOf(look.entered, (room / step).toInt())
                markPaint.style = Paint.Style.FILL
                markPaint.color = ink
                var x = cx - (shown - 1) * step / 2f
                repeat(shown) { canvas.drawCircle(x, dotsY, LOCK_DOT_R, markPaint); x += step }
            }
            else -> {
                text.color = ink
                canvas.drawText(ellipsize(look.title, room), cx, textY, text)
            }
        }
        text.color = ink
        text.textSize = size0
    }

    /**
     * A pattern lock: the nine dots, the ones joined grown into rings, lines from dot to dot and
     * on to the finger — the accent, red when it was wrong — and the words in their column.
     */
    private fun drawPattern(canvas: Canvas, k: PortalSheet.Keypad, grid: PortalSheet.PatternGrid, dy: Float, look: LockLook) {
        if (look.unlocking >= 0f) {
            // The nine dots, each as a bead of glass the size of its ring.
            val ring = grid.reach * 0.7f
            drawUnlock(canvas, grid.centers.map { (x, y) -> PortalSheet.Box(x - ring * grid.aspect, y - ring, x + ring * grid.aspect, y + ring) }, dy, look.unlocking, round = true)
            return
        }
        drawLockLine(canvas, k.centerX, k.infoRight - k.infoLeft, k.titleY + dy, k.dotsY + dy, look)
        val accent = if (look.wrong) Panel.Palette.CRIT else if (theme.isGlass) GLASS_ACCENT else Panel.Palette.WORK
        val p = markPaint
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        if (look.pattern.isNotEmpty()) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = PATTERN_LINE
            p.color = accent
            p.alpha = 200
            val path = Path()
            look.pattern.forEachIndexed { i, c ->
                val (x, y) = grid.centers[c]
                if (i == 0) path.moveTo(x, y + dy) else path.lineTo(x, y + dy)
            }
            look.finger?.let { (fx, fy) -> path.lineTo(fx, fy + dy) }
            canvas.drawPath(path, p)
            p.alpha = 255
        }
        grid.centers.forEachIndexed { i, (x, y) ->
            val on = i in look.pattern
            p.style = Paint.Style.FILL
            p.color = if (on) accent else ink
            // Ovals in panel units, round on the glass (grid.aspect).
            val r = if (on) PATTERN_DOT * 1.6f else PATTERN_DOT
            fillRect.set(x - r * grid.aspect, y + dy - r, x + r * grid.aspect, y + dy + r)
            canvas.drawOval(fillRect, p)
            if (on) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f
                val ring = grid.reach * 0.7f
                fillRect.set(x - ring * grid.aspect, y + dy - ring, x + ring * grid.aspect, y + dy + ring)
                canvas.drawOval(fillRect, p)
            }
        }
    }

    /**
     * A password lock: the words and a dot for each character on a line at the top, the message
     * under the dots when there is one, and the keyboard's page under them, its keys the sheet's
     * glass, rounded.
     */
    private fun drawKeyboard(canvas: Canvas, k: PortalSheet.Keyboard, dy: Float, g: Float, look: LockLook) {
        if (look.unlocking >= 0f) { drawUnlock(canvas, k.keys.map { it.box }, dy, look.unlocking, round = false); return }
        val size0 = text.textSize
        drawLockLine(canvas, k.centerX, k.right - k.left, k.titleY + dy, k.dotsY + dy, look)
        text.textSize = k.keys[0].box.height * KB_TEXT
        val km = text.fontMetrics
        val mid = -(km.ascent + km.descent) / 2f
        k.keys.forEachIndexed { i, key ->
            val b = key.box
            grown(canvas, b.centerX, b.centerY + dy, g) {
                buttonRect.set(b.left, b.top + dy, b.right, b.bottom + dy)
                val lit = look.pressedKey == i || (key.type == PortalSheet.KbType.SHIFT && look.page == PortalSheet.KB_UPPER)
                drawButton(canvas, buttonRect, if (lit) 0.5f else 0f, false, radius = minOf(b.width, b.height) * 0.42f)
                text.color = ink
                canvas.drawText(key.label, buttonRect.centerX(), buttonRect.centerY() + mid, text)
            }
        }
        text.textSize = size0
    }

    /**
     * The apps as a dome ([PortalDome]) at [pose], [dy] down, grown to [g]: each tile its app's
     * icon, the furthest first, fainter towards the rim; and a short bar in each of the sheet's
     * own strips, over and under the dome, where a drag still moves the sheet.
     */
    private fun drawDome(canvas: Canvas, dy: Float, g: Float, layout: PortalSheet.Layout, pose: PortalDome.Pose, icons: List<Bitmap?>) {
        val area = PortalDome.area(layout)
        val tiles = PortalDome.tiles(area, layout.count, pose)
        val draw: (Canvas) -> Unit = { c ->
            grown(c, area.centerX, area.centerY + dy, g) {
                for (t in tiles) {
                    val bmp = icons.getOrNull(t.app) ?: continue
                    iconPaint.alpha = (255 * t.alpha).toInt().coerceIn(0, 255)
                    // Turned as the ball turns it: narrower along the way out from the middle.
                    val deg = Math.toDegrees(t.turn.toDouble()).toFloat()
                    c.save()
                    c.translate(t.cx, t.cy + dy)
                    c.rotate(deg)
                    c.scale(t.along, t.across)
                    c.rotate(-deg)
                    iconRect.set(-t.half, -t.half, t.half, t.half)
                    c.drawBitmap(bmp, null, iconRect, iconPaint)
                    c.restore()
                }
            }
            iconPaint.alpha = 255
        }
        // INTO ITS EDGES THE DOME BLURS, THEN FADES (the owner, 2026-11-10: "some blur to the
        // margin of the dome instead of just fading"), on all four sides: a blurred copy of the
        // picture with its middle taken out and its very edge faded to nothing, and the sharp
        // picture over it, whole in the middle and gone by the edge. The blur needs a hardware
        // canvas (a RenderNode with a RenderEffect); on a software one the fade alone.
        val l = area.left; val t0 = area.top + dy; val r = area.right; val b = area.bottom + dy
        // The sheet's buttons stand over the dome: under each the dome is not drawn as it is but
        // blurred right down and dimmed (below), so the button's name is read against a soft
        // ground, not against icons (the owner, 2026-11-10).
        domeButtons.rewind()
        for (bx in layout.buttons) {
            val rad = minOf(bx.width, bx.height) * CcButton.CORNER
            domeButtons.addRoundRect(bx.left, bx.top + dy, bx.right, bx.bottom + dy, rad, rad, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipRect(l, t0, r, b)
        if (layout.buttons.isNotEmpty()) canvas.clipOutPath(domeButtons)
        domeMasks(area, dy)
        if (canvas.isHardwareAccelerated) {
            val w = Panel.WIDTH; val h = Panel.HEIGHT
            domeNode.setPosition(0, 0, w, h)
            val rc = domeNode.beginRecording(w, h)
            try { draw(rc) } finally { domeNode.endRecording() }
            domeBlurred.setPosition(0, 0, w, h)
            domeBlurred.setRenderEffect(domeBlur)
            val bc = domeBlurred.beginRecording(w, h)
            try { bc.drawRenderNode(domeNode) } finally { domeBlurred.endRecording() }
            // Blurred: everywhere but the middle, and nothing at the very edge.
            var layer = canvas.saveLayer(l, t0, r, b, null)
            canvas.drawRenderNode(domeBlurred)
            val hole = canvas.saveLayer(l, t0, r, b, domeHole)
            domeFill.shader = null
            canvas.drawRect(l, t0, r, b, domeFill)
            maskPaint.shader = domeSharpX; canvas.drawRect(l, t0, r, b, maskPaint)
            maskPaint.shader = domeSharpY; canvas.drawRect(l, t0, r, b, maskPaint)
            canvas.restoreToCount(hole)
            maskPaint.shader = domeOuterX; canvas.drawRect(l, t0, r, b, maskPaint)
            maskPaint.shader = domeOuterY; canvas.drawRect(l, t0, r, b, maskPaint)
            canvas.restoreToCount(layer)
            // Sharp, over it: whole in the middle, gone by the edge.
            layer = canvas.saveLayer(l, t0, r, b, null)
            canvas.drawRenderNode(domeNode)
            maskPaint.shader = domeSharpX; canvas.drawRect(l, t0, r, b, maskPaint)
            maskPaint.shader = domeSharpY; canvas.drawRect(l, t0, r, b, maskPaint)
            canvas.restoreToCount(layer)
        } else {
            val layer = canvas.saveLayer(l, t0, r, b, null)
            draw(canvas)
            maskPaint.shader = domeSharpX; canvas.drawRect(l, t0, r, b, maskPaint)
            maskPaint.shader = domeSharpY; canvas.drawRect(l, t0, r, b, maskPaint)
            canvas.restoreToCount(layer)
        }
        canvas.restore()
        if (layout.buttons.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(l, t0, r, b)
            canvas.clipPath(domeButtons)
            if (canvas.isHardwareAccelerated) {
                val w = Panel.WIDTH; val h = Panel.HEIGHT
                domeUnderButtons.setPosition(0, 0, w, h)
                domeUnderButtons.setRenderEffect(domeButtonBlur)
                val uc = domeUnderButtons.beginRecording(w, h)
                try { uc.drawRenderNode(domeNode) } finally { domeUnderButtons.endRecording() }
                canvas.drawRenderNode(domeUnderButtons)
            }
            domeFill.color = DOME_BUTTON_DIM
            canvas.drawRect(l, t0, r, b, domeFill)
            domeFill.color = 0xFFFFFFFF.toInt()
            canvas.restore()
        }
        // The grips: where a drag is the sheet's and not the dome's (PortalDome.turns), a bar
        // near the bottom edge and, with no buttons, one near the top.
        val p = markPaint
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = DOME_GRIP_H
        p.color = (ink and 0x00FFFFFF) or (DOME_GRIP_ALPHA shl 24)
        // The bottom one only with an app on the back: it is for pulling its sliders up.
        if (PortalDome.pullsUp(layout)) {
            val under = PortalSheet.FULL - DOME_GRIP_EDGE + dy
            canvas.drawLine(area.centerX - DOME_GRIP_W / 2f, under, area.centerX + DOME_GRIP_W / 2f, under, p)
        }
        if (layout.buttons.isEmpty()) {
            val over = DOME_GRIP_EDGE + dy
            canvas.drawLine(area.centerX - DOME_GRIP_W / 2f, over, area.centerX + DOME_GRIP_W / 2f, over, p)
        }
        p.strokeCap = Paint.Cap.BUTT
    }

    private val domeNode = RenderNode("portal-dome")
    private val domeUnderButtons = RenderNode("portal-dome-under-buttons")
    private val domeButtonBlur = RenderEffect.createBlurEffect(DOME_BUTTON_BLUR, DOME_BUTTON_BLUR, Shader.TileMode.DECAL)
    private val domeButtons = Path()
    private val domeBlurred = RenderNode("portal-dome-blurred")
    private val domeBlur = RenderEffect.createBlurEffect(DOME_BLUR, DOME_BLUR, Shader.TileMode.DECAL)
    /** Takes out of the layer under it what is drawn in it: the blurred copy's middle. */
    private val domeHole = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val domeFill = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private var domeSharpX: Shader? = null
    private var domeSharpY: Shader? = null
    private var domeOuterX: Shader? = null
    private var domeOuterY: Shader? = null
    private var domeMasksFor: PortalSheet.Box? = null
    private var domeMasksDy = Float.NaN

    /**
     * The dome's masks for [area], [dy] down, each way: the sharp picture's, nothing at the
     * edge and whole [PortalDome.MARGIN] in; and the blurred copy's own fade at the very edge,
     * over the first third of that.
     */
    private fun domeMasks(area: PortalSheet.Box, dy: Float) {
        if (domeMasksFor == area && domeMasksDy == dy) return
        domeMasksFor = area; domeMasksDy = dy
        val whole = 0xFF000000.toInt()
        // Straight ramps, [margin] in from each end.
        fun band(from: Float, to: Float, across: Boolean, margin: Float): Shader {
            val m = (margin / (to - from)).coerceAtMost(0.49f)
            val pos = floatArrayOf(0f, m, 1f - m, 1f)
            val col = intArrayOf(0, whole, whole, 0)
            return if (across) LinearGradient(from, 0f, to, 0f, col, pos, Shader.TileMode.CLAMP)
            else LinearGradient(0f, from, 0f, to, col, pos, Shader.TileMode.CLAMP)
        }
        // Across, the left is its own (the owner, 2026-11-10): towards the lenses the fade is
        // longer ([left] in) and eased in and out, in steps — a straight ramp ended there in a
        // line the eye found. The right as the other sides: straight, [right] in.
        fun across(left: Float, right: Float): Shader {
            val span = area.right - area.left
            val ml = (left / span).coerceAtMost(0.6f)
            val mr = (right / span).coerceAtMost(0.3f)
            val steps = 8
            val pos = FloatArray(steps + 3)
            val col = IntArray(steps + 3)
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                pos[i] = ml * t
                col[i] = Math.round(255 * t * t * (3f - 2f * t)) shl 24
            }
            pos[steps + 1] = 1f - mr; col[steps + 1] = whole
            pos[steps + 2] = 1f; col[steps + 2] = 0
            return LinearGradient(area.left, 0f, area.right, 0f, col, pos, Shader.TileMode.CLAMP)
        }
        domeSharpX = across(PortalDome.MARGIN_LEFT, PortalDome.MARGIN)
        domeSharpY = band(area.top + dy, area.bottom + dy, false, PortalDome.MARGIN)
        domeOuterX = across(PortalDome.MARGIN_LEFT * 0.8f, PortalDome.MARGIN / 3f)
        domeOuterY = band(area.top + dy, area.bottom + dy, false, PortalDome.MARGIN / 3f)
    }

    /**
     * THE LOCK LEAVING, the code right (the owner, 2026-11-10): at [t] from 0 to 1, every piece of
     * glass the lock is made of — the keypad's keys, the keyboard's, the pattern's dots, in
     * [pieces] — runs together into one big rounded pill in their middle; a tick is drawn in it,
     * glowing; and the pill goes, a little bigger as it does, leaving what the lock stood over.
     * The pieces' figures are not drawn: they are gone as it starts.
     *
     * The pieces travel as themselves for the first of it, fading as the pill they are making
     * comes up under them: thirty panes of glass on one spot would be as dark as thirty, where
     * one pill is one.
     */
    private fun drawUnlock(canvas: Canvas, pieces: List<PortalSheet.Box>, dy: Float, t: Float, round: Boolean) {
        if (pieces.isEmpty()) return
        val left = pieces.minOf { it.left }; val right = pieces.maxOf { it.right }
        val top = pieces.minOf { it.top } + dy; val bottom = pieces.maxOf { it.bottom } + dy
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val h = minOf((bottom - top) * UNLOCK_PILL_H, UNLOCK_PILL_MAX)
        val w = minOf((right - left) * 0.8f, h * UNLOCK_PILL_ASPECT)
        val gather = smooth(0f, UNLOCK_GATHERED, t)
        val gone = smooth(UNLOCK_GOING, 1f, t)
        val all = if (gone > 0.004f) canvas.saveLayerAlpha(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), (255 * (1f - gone)).toInt().coerceIn(0, 255)) else -1
        canvas.save()
        val swell = 1f + UNLOCK_SWELL * gone
        canvas.scale(swell, swell, cx, cy)

        // The pieces, on their way in.
        val piecesAlpha = 1f - smooth(UNLOCK_GATHERED * 0.55f, UNLOCK_GATHERED, t)
        if (piecesAlpha > 0.004f) {
            val layer = if (piecesAlpha < 0.996f) canvas.saveLayerAlpha(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), (255 * piecesAlpha).toInt()) else -1
            for (b in pieces) {
                val l = b.left + (cx - w / 2f - b.left) * gather
                val r = b.right + (cx + w / 2f - b.right) * gather
                val tp = b.top + dy + (cy - h / 2f - (b.top + dy)) * gather
                val bt = b.bottom + dy + (cy + h / 2f - (b.bottom + dy)) * gather
                buttonRect.set(l, tp, r, bt)
                val own = minOf(b.width, b.height) * if (round) 0.5f else 0.42f
                drawButton(canvas, buttonRect, 0f, false, radius = own + (h / 2f - own) * gather)
            }
            if (layer >= 0) canvas.restoreToCount(layer)
        }
        // The pill they make.
        val pillAlpha = smooth(UNLOCK_GATHERED * 0.4f, UNLOCK_GATHERED, t)
        if (pillAlpha > 0.004f) {
            val layer = if (pillAlpha < 0.996f) canvas.saveLayerAlpha(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), (255 * pillAlpha).toInt()) else -1
            buttonRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            drawButton(canvas, buttonRect, 0f, false, radius = h / 2f)
            if (layer >= 0) canvas.restoreToCount(layer)
        }
        // The tick, drawn from its short stroke on, in light.
        val drawn = smooth(UNLOCK_GATHERED - 0.04f, UNLOCK_TICKED, t)
        if (drawn > 0.004f) {
            tick.rewind()
            tick.moveTo(cx - h * 0.21f, cy + h * 0.02f)
            tick.lineTo(cx - h * 0.06f, cy + h * 0.17f)
            tick.lineTo(cx + h * 0.23f, cy - h * 0.15f)
            tickMeasure.setPath(tick, false)
            tickDrawn.rewind()
            tickMeasure.getSegment(0f, tickMeasure.length * drawn, tickDrawn, true)
            // Grey light, not the accent's purple (the owner, 2026-11-10): the sheet's own ink.
            val accent = TICK_GLOW
            // Its glow: up as it is drawn, flaring as it closes, easing after.
            val flare = Math.sin(Math.PI * smooth(UNLOCK_GATHERED, UNLOCK_GOING, t).toDouble()).toFloat()
            val p = markPaint
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            val blur = h * (0.07f + 0.07f * flare)
            if (blur != tickBlurRadius) {
                tickBlurRadius = blur
                tickBlur = android.graphics.BlurMaskFilter(blur.coerceAtLeast(0.5f), android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            p.maskFilter = tickBlur
            p.strokeWidth = h * 0.13f
            p.color = accent
            p.alpha = (255 * (0.55f + 0.45f * flare)).toInt()
            canvas.drawPath(tickDrawn, p)
            p.maskFilter = null
            p.strokeWidth = h * 0.075f
            p.color = 0xFFFFFFFF.toInt()
            p.alpha = 255
            canvas.drawPath(tickDrawn, p)
            p.strokeCap = Paint.Cap.BUTT
        }
        canvas.restore()
        if (all >= 0) canvas.restoreToCount(all)
    }

    private val tick = Path()
    private val tickDrawn = Path()
    private val tickMeasure = android.graphics.PathMeasure()
    private var tickBlur: android.graphics.BlurMaskFilter? = null
    private var tickBlurRadius = -1f

    private fun smooth(e0: Float, e1: Float, t: Float): Float {
        val x = ((t - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** The width slider's mark in [box]: a two-headed arrow between two upright bars, in the ink. */
    private fun drawWidthMark(canvas: Canvas, box: android.graphics.RectF) {
        val p = markPaint
        p.color = ink
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val inset = box.width() * 0.14f
        val l = box.left + inset; val r = box.right - inset
        val cy = box.centerY(); val half = box.height() * 0.3f
        canvas.drawLine(l, cy - half, l, cy + half, p)
        canvas.drawLine(r, cy - half, r, cy + half, p)
        val al = l + 7f; val ar = r - 7f; val head = 6f
        canvas.drawLine(al, cy, ar, cy, p)
        canvas.drawLine(al, cy, al + head, cy - head, p); canvas.drawLine(al, cy, al + head, cy + head, p)
        canvas.drawLine(ar, cy, ar - head, cy - head, p); canvas.drawLine(ar, cy, ar - head, cy + head, p)
    }

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * "Keep right of the cameras", [dy] down, grown to [g]: its row a button as the slider's value
     * is, its words left of the switch, and the switch the front's, per theme as the slider is.
     * GLASS: a capsule in the slider's grey, the front's accent when [on], its pill thumb white.
     * ORIGINAL: square, the panel's TRACK, its green when on, the thumb its green off and its
     * ground on.
     */
    private fun drawToggle(canvas: Canvas, t: PortalSheet.Toggle, dy: Float, g: Float, on: Boolean, pressed: Int) {
        grown(canvas, t.row.centerX, t.row.centerY + dy, g) {
            buttonRect.set(t.row.left, t.row.top + dy, t.row.right, t.row.bottom + dy)
            drawButton(canvas, buttonRect, if (pressed == PRESS_TOGGLE) 0.5f else 0f, false)
            val m = text.fontMetrics
            val room = t.knob.left - t.row.left - 2 * PortalSheet.BUTTON_PAD
            canvas.drawText(
                ellipsize(PortalSheet.TOGGLE_LABEL, room), t.row.left + PortalSheet.BUTTON_PAD + room / 2,
                t.row.centerY + dy - (m.ascent + m.descent) / 2f, text,
            )
            val glassy = theme.isGlass
            val h = t.knob.height
            val radius = if (glassy) h / 2 else 0f
            buttonPaint.style = Paint.Style.FILL
            buttonPaint.color = when {
                on -> if (glassy) GLASS_ACCENT else Panel.Palette.WORK
                else -> if (glassy) GLASS_TRACK else Panel.Palette.TRACK
            }
            fillRect.set(t.knob.left, t.knob.top + dy, t.knob.right, t.knob.bottom + dy)
            canvas.drawRoundRect(fillRect, radius, radius, buttonPaint)
            val inner = h - 2 * KNOB_INSET
            val left = if (on) t.knob.right - KNOB_INSET - inner else t.knob.left + KNOB_INSET
            buttonPaint.color = when {
                glassy -> 0xFFFFFFFF.toInt()
                on -> Panel.Palette.BG
                else -> Panel.Palette.WORK
            }
            fillRect.set(left, t.knob.top + dy + KNOB_INSET, left + inner, t.knob.bottom + dy - KNOB_INSET)
            canvas.drawRoundRect(fillRect, radius, radius, buttonPaint)
        }
    }

    /**
     * The slider's track, its fill up to the thumb, and the thumb, [dy] down — the front's
     * sliders, at the rear's size. GLASS: GlassSlider's — a capsule track in its grey, filled
     * in the front's accent, and a pill thumb, white at rest and, held, one and a half times
     * as big and clear glass. ORIGINAL: the front's PanelSeekBar — square track in the
     * panel's TRACK, filled and thumbed in its green.
     */
    private fun drawTrack(canvas: Canvas, s: PortalSheet.Slider, dy: Float, look: SliderLook, held: Boolean) {
        val cy = s.track.centerY + dy
        val thumbX = s.track.left + look.fraction.coerceIn(0f, 1f) * s.track.width
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
        /** The oval in [rect]: a round key on a rear whose panel units are not square (PortalSheet.keypad). */
        oval: Boolean = false,
    ): Boolean {
        buttonShape.rewind()
        if (oval) buttonShape.addOval(rect, Path.Direction.CW) else buttonShape.addRoundRect(rect, radius, radius, Path.Direction.CW)
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
        /** [draw]'s pressed: "Keep right of the cameras"'s row. */
        const val PRESS_TOGGLE = 12
        /** [pressed] for the lock's key i: this plus i. */
        const val PRESS_KEY = 100
        /** The lock's dots: their radius, and the most shown in a row. */
        private const val LOCK_DOT_R = 6f
        /** The dome's grips, in the sheet's own strips over and under it: a bar this wide and thick, this faint. */
        private const val DOME_GRIP_W = 96f
        private const val DOME_GRIP_H = 6f
        private const val DOME_GRIP_ALPHA = 0x99
        /** How far in from the sheet's top and bottom edges the grips stand. */
        private const val DOME_GRIP_EDGE = 16f
        /** How blurred the dome is in its margins. */
        private const val DOME_BLUR = 14f
        /** Under the sheet's buttons the dome is blurred this much, and dimmed by this. */
        private const val DOME_BUTTON_BLUR = 26f
        private const val DOME_BUTTON_DIM = 0x52000000
        /**
         * The lock leaving ([drawUnlock]), in its run: by where its pieces have run together, by
         * where the tick is drawn, and from where it all goes — the last of which is also from
         * where what it stood over comes in (ShellSheet).
         */
        /** How long the lock takes to leave, all of it. */
        const val UNLOCK_MS = 1100L
        /** The tick's glow: a light grey. */
        private const val TICK_GLOW = 0xFFC8C8CC.toInt()
        const val UNLOCK_GATHERED = 0.36f
        const val UNLOCK_TICKED = 0.62f
        const val UNLOCK_GOING = 0.8f
        /** The pill the pieces make: its height in theirs together, no more than this, and its width in its height. */
        private const val UNLOCK_PILL_H = 0.5f
        private const val UNLOCK_PILL_MAX = 130f
        private const val UNLOCK_PILL_ASPECT = 2.3f
        /** How much bigger the pill is by the time it has gone. */
        private const val UNLOCK_SWELL = 0.08f
        /** A pattern's dots and its line. */
        private const val PATTERN_DOT = 8f
        private const val PATTERN_LINE = 10f
        /** A keyboard key's label, as much of the key's height. */
        private const val KB_TEXT = 0.34f
        /** The lock's words, as much of the sheet's text size. */
        private const val TITLE_SCALE = 0.72f
        /** A key's figure, as much of the key's height. */
        private const val KEY_TEXT = 0.34f
        private const val DOTS_MAX = 16
        /** The width slider's value, and its thumb held. */
        const val PRESS_WIDTH_VALUE = 13
        const val PRESS_WIDTH_THUMB = 14
        private const val KNOB_INSET = 3f

        private const val SHADOW = 0xCC000000.toInt()

        /** The slider's track, this thick: GlassSlider's 6 dp to its 24 dp thumb; PanelSeekBar's to its 18 dp one. */
        private const val TRACK_H = 7f
        private const val TRACK_H_FLAT = 9f

        /** GlassSlider's track and fill over a dark ground (ui.glass.Ink.track, .ACCENT). */
        private const val GLASS_TRACK = 0x5C787880
        private const val GLASS_ACCENT = 0xFFC26BFF.toInt()

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
