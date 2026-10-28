package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.PanelTheme

/**
 * Paints the back's control centre — the brightness as a glass of liquid with its sun and
 * its figure, the automatic-brightness button under it, and the four tiles — over whatever
 * the caller has blurred behind it. Moved out of RearDialView (drawControlCenter and what it
 * drew), so the panel and the shell's control centre over a guest (ShellControlCenter) are
 * one drawing, as the portal sheet's is (PortalSheetPainter).
 *
 * A function of what comes in: the canvas, how far it is pulled ([draw]'s progress), the
 * [theme] and the panel's [face], the brightness and its figure, the automatic state, the
 * tiles and what is pressed ([Look]) — every value already eased by the caller, whose
 * clocks they are — and the glass ([lens]: the panel's view lays real glass over its
 * blurred picture, the shell unbacked panes). The liquid's motion is the [cell]'s, stepped
 * on [nowMs]. Laid out by ControlCenterLayout.
 *
 * Not thread-safe; all painters in a process must draw one at a time (they share Glass's
 * shaders).
 */
class ControlCenterPainter(face: PanelType.Face) {

    /** The panel's theme: GLASS draws glass, ORIGINAL flat. */
    var theme: PanelTheme = PanelTheme.GLASS

    /**
     * The panel's face: the automatic button's A at the body weight, and the figure at the
     * heaviest under GLASS (glass needs ink to be made of) and the body weight under
     * ORIGINAL — as RearDialView's extras and chargeFigure paints are told.
     */
    var face: PanelType.Face = face
        set(value) {
            if (value === field) return
            field = value
            applyFace()
        }

    /**
     * The control centre as it is to be drawn.
     *
     * [level] is the level the cell shows (ControlCenter.MIN_LEVEL to 1); [figureGrow] how far
     * its figure is grown in (0 to 1); [unavailable] the brightness cannot be set (no
     * Shizuku): the figure says so and the button is off. [auto] is the button's colour, 0
     * off to 1 on; [tiles] each tile's (silent, Bluetooth, mobile data, Wi-Fi), [reachable]
     * whether the last three can be switched — faint without. [pressed] is a tile (0 to 3)
     * or ControlCenterLayout.AUTO under a finger, shown half lit; ControlCenterLayout.NONE
     * for none (the panel never shows one).
     */
    class Look(
        val level: Float,
        val figureGrow: Float,
        val unavailable: Boolean,
        val auto: Float,
        val tiles: FloatArray,
        val reachable: Boolean,
        val pressed: Int = ControlCenterLayout.NONE,
    )

    private val liquid = LiquidCellPainter()

    /** The A, at the body weight. */
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    /** The figure under GLASS, at the heaviest. */
    private val heavy = Paint(Paint.ANTI_ALIAS_FLAG)
    /** The tiles' glyphs (Glyphs.draw sets its colour, style and smoothing). */
    private val iconPaint = Paint()
    /** The sun, and every button's body over its pane (CcButton.drawBody), one paint as the panel had. */
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { applyFace() }

    private fun applyFace() {
        face.set(body, PanelType.BODY)
        face.set(heavy, PanelType.HEAVIEST)
    }

    /**
     * The control centre at [progress] (0 shut, 1 open), as [look] has it.
     *
     * NOTHING UNTIL HALFWAY, then arriving over the rest — fading in, sliding down and
     * growing into place, all on one eased curve (Arrival): a pull that is still deciding
     * shows only the blur, and the controls come as it commits.
     *
     * [cell] is the brightness's liquid, stepped on [nowMs] (SystemClock.elapsedRealtime).
     * [lens] lays the glass. [corner] is the display's corner radius in panel units;
     * [buttonGround] the colour a flat button's mark turns to as it fills (asked only for a
     * flat one); [cellGround] the panel's background, the cell's ground under ORIGINAL;
     * [gravityX], [gravityY] which way is down for the liquid (0, 0: straight down).
     */
    fun draw(
        canvas: Canvas, progress: Float, look: Look,
        cell: LiquidCellPainter.Cell, lens: LiquidCellPainter.Lens, nowMs: Long,
        corner: Float, buttonGround: () -> Int, cellGround: Int,
        gravityX: Float = 0f, gravityY: Float = 0f,
    ) {
        val arrival = Arrival.of(progress)
        if (arrival <= 0.004f) return
        val layer = if (arrival < 0.996f) canvas.saveLayerAlpha(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(), (255 * arrival).toInt()) else -1
        // Each piece grows about its own middle — see ControlCenterLayout.grown — not the lot from one point.
        try {
            liquid.lens = lens
            liquid.glassy = theme.isGlass
            liquid.corner = corner
            liquid.ground = cellGround
            liquid.gravityX = gravityX
            liquid.gravityY = gravityY
            drawContents(canvas, progress, look, cell, lens, nowMs, corner, buttonGround)
        } finally {
            if (layer >= 0) canvas.restoreToCount(layer)
        }
    }

    /** A tile's colour as drawn: its own, or half lit under a finger. */
    private fun tileOn(look: Look, i: Int): Float = if (look.pressed == i) PRESSED else look.tiles.getOrElse(i) { 0f }

    private fun drawContents(
        canvas: Canvas, progress: Float, look: Look,
        cell: LiquidCellPainter.Cell, lens: LiquidCellPainter.Lens, nowMs: Long,
        corner: Float, buttonGround: () -> Int,
    ) {
        val rect = ControlCenterLayout.cellRect(progress)
        val level = look.level
        val glass = theme.isGlass
        // The switches first, so the brightness figure grows out OVER them.
        drawAutoButton(canvas, ControlCenterLayout.autoRect(progress), look, lens, buttonGround)
        drawQuickTiles(canvas, progress, look, lens, corner, buttonGround)
        liquid.draw(
            canvas, cell, if (glass) heavy else body, nowMs,
            rect.left, rect.top, rect.right, rect.bottom,
            fill = level,
            // White liquid, as the charging cell's is see-through, and the figure in
            // the very colour the liquid shows.
            tint = 0xFFFFFFFF.toInt(),
            grow = look.figureGrow,
            figureOnRight = true,
            // Under ORIGINAL, white outright — liquid, outline, figure; nothing there is
            // see-through. Under GLASS the liquid is a tint in the glass, and the figure
            // wears the colour it shows.
            figureTint = if (glass) (0x00FFFFFF) or (RearDialView.CHARGE_LIQUID_ALPHA shl 24) else 0xFFFFFFFF.toInt(),
            liquidAlpha = if (glass) RearDialView.CHARGE_LIQUID_ALPHA else 0xFF,
            rimAlpha = if (glass) RearDialView.CHARGE_RIM_ALPHA else 0xFF,
            // Where it crosses a tile, its colour turns over as far as the tile is on.
            figureOver = (0 until 4).map { i ->
                val r = ControlCenterLayout.tileRect(i, progress)
                tileShape(i, r, corner) to (if (i == 0 || look.reachable) tileOn(look, i) else 0f)
            },
            figure = if (look.unavailable) "Needs Shizuku" else Math.round(level * 100f).toString() + "%",
            // THE SUN, in the glass near its foot: half a turn from dimmest to brightest,
            // its rays lengthening with the level — light above the liquid, and where the
            // liquid covers it, inverted, so it reads through the water as well as over it.
            inside = { c, liquidBody ->
                val size = rect.width() * 0.62f
                val cx = rect.centerX()
                val cy = rect.bottom - rect.width() * 0.62f
                c.save()
                c.clipOutPath(liquidBody)
                drawSun(c, cx, cy, size, level * 0.5f, level, 0xE6FFFFFF.toInt())
                c.restore()
                c.save()
                c.clipPath(liquidBody)
                drawSun(c, cx, cy, size, level * 0.5f, level, 0xCC000000.toInt())
                c.restore()
            },
        )
    }

    /**
     * Rounded rectangles — except where a tile meets the screen's own corner, top right and
     * bottom right, which follow the screen's curve ([corner]) carried inward.
     */
    private fun tileShape(i: Int, rect: RectF, corner: Float): Path {
        val base = minOf(rect.width(), rect.height()) * CcButton.CORNER
        val screen = (corner - ChargeOverlay.MARGIN).coerceAtLeast(base)
        // top-left, top-right, bottom-right, bottom-left — x and y for each.
        val tr = if (i == 1) screen else base
        val br = if (i == 3) screen else base
        return Path().apply {
            addRoundRect(rect, floatArrayOf(base, base, tr, tr, br, br, base, base), Path.Direction.CW)
        }
    }

    private fun drawQuickTiles(
        canvas: Canvas, progress: Float, look: Look, lens: LiquidCellPainter.Lens, corner: Float, buttonGround: () -> Int,
    ) {
        for (i in 0 until 4) {
            val rect = ControlCenterLayout.tileRect(i, progress)
            val shape = tileShape(i, rect, corner)
            // Silent is the app's own; the others need Shizuku, and are faint without it.
            val usable = i == 0 || look.reachable
            val on = if (usable) tileOn(look, i) else 0f
            drawCcButton(canvas, rect, minOf(rect.width(), rect.height()) * CcButton.CORNER, shape, on, lens, buttonGround) { c, color ->
                val ink = if (usable) color else (color and 0x00FFFFFF) or (0x40 shl 24)
                val h = minOf(rect.width(), rect.height()) * 0.34f
                Glyphs.draw(c, QUICK_ICONS[i], theme, rect.centerX() - h / 2f, rect.centerY() - h / 2f, h, ink, iconPaint, smooth = theme.isGlass)
            }
        }
    }

    /**
     * The automatic-brightness button: a round glass pane with an A on it, bright when
     * automatic is on and faint when off. Under ORIGINAL a disc, filled when on and only
     * outlined when off.
     */
    private fun drawAutoButton(canvas: Canvas, rect: RectF, look: Look, lens: LiquidCellPainter.Lens, buttonGround: () -> Int) {
        val r = rect.width() / 2f
        val shape = Path().apply { addCircle(rect.centerX(), rect.centerY(), r, Path.Direction.CW) }
        val on = when {
            look.unavailable -> 0f
            look.pressed == ControlCenterLayout.AUTO -> PRESSED
            else -> look.auto
        }
        drawCcButton(canvas, rect, r, shape, on, lens, buttonGround) { c, color ->
            body.textAlign = Paint.Align.CENTER
            body.textSize = r * 0.9f
            body.color = color
            val m = body.fontMetrics
            c.drawText("A", rect.centerX(), rect.centerY() - (m.ascent + m.descent) / 2f, body)
        }
    }

    /**
     * One of the control centre's buttons, [on] 0 to 1 (eased): its pane of glass under
     * GLASS, and over it what CcButton draws. [mark] draws the mark in the colour it is given.
     */
    private fun drawCcButton(
        canvas: Canvas, rect: RectF, radius: Float, shape: Path, on: Float,
        lens: LiquidCellPainter.Lens, buttonGround: () -> Int,
        mark: (Canvas, Int) -> Unit,
    ) {
        val glassy = theme.isGlass && lens.pane(canvas, rect, radius, shape, rim = true)
        val bg = if (glassy) 0 else buttonGround()
        // White, as RGB with no alpha: what "on" fills it with, and ORIGINAL's outline.
        CcButton.drawBody(canvas, shape, on, 0x00FFFFFF, glassy, bg, sunPaint, mark)
    }

    /**
     * A sun [size] across at ([cx], [cy]): a disc and eight rays, turned [turn] of the way
     * round, the rays as long as [shine] says — stubs at the dimmest, full at the brightest.
     */
    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, size: Float, turn: Float, shine: Float, color: Int) {
        sunPaint.color = color
        sunPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, size * 0.2f, sunPaint)
        sunPaint.style = Paint.Style.STROKE
        sunPaint.strokeWidth = size * 0.08f
        sunPaint.strokeCap = Paint.Cap.ROUND
        val inner = size * 0.3f
        val outer = inner + size * (0.04f + 0.18f * shine.coerceIn(0f, 1f))
        canvas.save()
        canvas.rotate(turn * 360f, cx, cy)
        for (i in 0 until 8) {
            canvas.drawLine(cx, cy - inner, cx, cy - outer, sunPaint)
            canvas.rotate(45f, cx, cy)
        }
        canvas.restore()
        sunPaint.style = Paint.Style.FILL
    }

    companion object {
        /** A control under a finger: half lit, as a portal sheet button is. */
        const val PRESSED = 0.5f

        /** The tiles, in order: what they show, and the name a switch goes out by. */
        val QUICK_ICONS = arrayOf(PanelIcons.SILENT, PanelIcons.BLUETOOTH, PanelIcons.MOBILE_DATA, PanelIcons.WIFI)
        val QUICK_NAMES = arrayOf("silent", "bluetooth", "data", "wifi")
    }
}
