package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * A glass of liquid, as the charging cell is built — and the control centre's brightness.
 * Moved out of RearDialView.drawLiquidCell so the shell's control centre pours the same
 * liquid; the panel draws both its cells through it, unchanged.
 *
 * Needs no View: what the panel's view had to give — its panes of glass, the lens that
 * magnifies through the liquid, the glass figure — comes through [lens]; the display's
 * corner radius, the panel's ground and which way is down come in as properties, set by
 * the caller before each [draw].
 *
 * Not thread-safe; its paints are its own, and one painter draws on one thread.
 */
class LiquidCellPainter {

    /**
     * One glass of liquid's moving parts: its surface, its discharges, which way it thinks
     * is down (in radians — see [draw]), when it last stepped and the tenth of the level it
     * last knocked at.
     */
    class Cell {
        val surface = LiquidSurface()
        val surge = ChargeSurge()
        var down = 0f
        var stepAt = 0L
        var tenth = -1
        fun reset() { stepAt = 0L; tenth = -1; surface.calm(); surge.clear() }
    }

    /**
     * What only the caller can draw. The panel's view lays real glass over its own picture;
     * the shell, with no picture of what is under it, lays unbacked panes and no lens.
     */
    interface Lens {
        /** A pane of glass at [rect], [shape] when it is not the rounded rect; false when none was laid. */
        fun pane(canvas: Canvas, rect: RectF, radius: Float, shape: Path?, rim: Boolean): Boolean
        /** What is behind magnified through [body]; false when there is nothing to magnify. */
        fun magnify(canvas: Canvas, rect: RectF, body: Path, zoom: Float): Boolean = false
        /** [text] made of glass, its right edge at [rightAt]; false when the caller should draw the type. */
        fun text(canvas: Canvas, text: String, paint: Paint, rightAt: Float, centreY: Float): Boolean = false
    }

    /** The glass, the lens and the glass figure, as the caller can give them. */
    var lens: Lens = object : Lens {
        override fun pane(canvas: Canvas, rect: RectF, radius: Float, shape: Path?, rim: Boolean) = false
    }

    /** The theme is GLASS: the cell is a pane, the liquid a tint in it, no painted rim. */
    var glassy = true

    /** The display's own corner radius, in panel units (RearDialView.cornerRadius). */
    var corner = FALLBACK_CORNER

    /** The panel's background, the cell's ground where there is no pane (ORIGINAL). */
    var ground = 0

    /** The accelerometer's reading in the panel's frame; 0, 0 is straight down. */
    var gravityX = 0f
    var gravityY = 0f

    /** The glass's ground and the liquid (no anti-aliasing, as the panel's notice paint). */
    private val liquidPaint = Paint()

    /** The cell's painted outline under ORIGINAL. */
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The paint the discharges are drawn with. ADDITIVE, so overlapping flashes build up
     * the way light does rather than the nearest one simply covering the others.
     */
    private val gleam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        blendMode = android.graphics.BlendMode.PLUS
    }

    /** A colour pushed toward white, keeping its hue. RGB only; the caller sets alpha. */
    private fun lighten(color: Int, amount: Float): Int {
        val r = ((color shr 16) and 0xFF).let { it + ((255 - it) * amount).toInt() }
        val g = ((color shr 8) and 0xFF).let { it + ((255 - it) * amount).toInt() }
        val b = (color and 0xFF).let { it + ((255 - it) * amount).toInt() }
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * [cell] carries the liquid's moving parts; [fill] is the level, 0 to 1; [tint] the
     * liquid's colour; [surgeAt] the discharges' clock (the charging cell's elapsed time),
     * none without one; [grow] how far the figure is grown in, beside the glass on its
     * left — or on its right when [figureOnRight]. [figure] defaults to the level in percent.
     * [figurePaint]'s size, alignment and colour are set here, its alignment put back.
     */
    fun draw(
        canvas: Canvas,
        cell: Cell,
        /** The figure's paint: the panel's heaviest under GLASS, its body type otherwise. */
        figurePaint: Paint,
        /** The discharges' and the surface's clock: SystemClock.elapsedRealtime. */
        nowMs: Long,
        left: Float, top: Float, right: Float, bottom: Float,
        fill: Float,
        tint: Int,
        surgeAt: Float? = null,
        grow: Float = 0f,
        figureOnRight: Boolean = false,
        figureTint: Int = tint,
        figure: String = Math.round(fill * 100f).toString() + "%",
        /**
         * Something drawn inside the glass with the liquid, clipped to the glass: given the
         * liquid's body, so it can be drawn one way above the surface and another below.
         */
        inside: ((Canvas, android.graphics.Path) -> Unit)? = null,
        /** How solid the liquid and the glass's outline are (the outline: ORIGINAL only). */
        liquidAlpha: Int = RearDialView.CHARGE_LIQUID_ALPHA,
        rimAlpha: Int = RearDialView.CHARGE_RIM_ALPHA,
        /**
         * Shapes the figure may cross, each with how far its colour should turn over there
         * (0 not at all, 1 inverted): the control centre's tiles, filled as far as they are on.
         */
        figureOver: List<Pair<android.graphics.Path, Float>> = emptyList(),
    ) {
        // THE SCREEN'S OWN RADIUS, carried inward.
        //
        // The cell is inset from the panel's edges by MARGIN, and a shape inset inside a
        // rounded rectangle keeps concentric corners at radius minus inset — so this is
        // the display's actual curve continued, not an approximation of it. [corner] is
        // the display's own, as the caller reads it, rather than an assumed number.
        //
        // Floored, because on a display with barely rounded corners the arithmetic gives
        // something square, and a glass with sharp corners reads as a box.
        val r = (corner - ChargeOverlay.MARGIN)
            .coerceIn(ChargeOverlay.MIN_CORNER, (right - left) / 2f)

        val glass = android.graphics.Path().apply {
            addRoundRect(left, top, right, bottom, r, r, android.graphics.Path.Direction.CW)
        }

        // THE CELL IS A GLASS, so under GLASS it is made of glass.
        //
        // This is the one element on the panel where the material was never in question —
        // it has been "a glass with liquid in it" since it was built, and it was only
        // painted flat because there was no lens to hand. It is also an overlay, drawn
        // after the scene, so it gets the real thing: the readout and the record bend
        // through the empty part of the cell above the liquid line.
        //
        // The ground goes with it. That was there so the liquid had something to sit
        // inside rather than floating over whatever the panel was drawing; a pane does
        // that job better, because it is what a vessel actually does to what is behind it.
        val chargeRect = android.graphics.RectF(left, top, right, bottom)
        // NO RIM ON THE CELL. Every other pane wants an edge to say where it ends; this
        // one is a vessel whose contents are the point, and a lit outline around a glass
        // of moving liquid is a second thing competing with the first. The liquid's own
        // surface and the refraction at the walls say where the glass is.
        // NO TINT OF ITS OWN. It wore the notification card's dim for a while, on the
        // reasoning that two glass surfaces at two different weights read as two
        // materials — which is true of two CARDS, and this is not one. A notification is
        // a sheet you read type off, and it needs a colour so the type has something to
        // sit against. A cell is a vessel: the whole point of it is that you see what is
        // inside, and the dim it borrowed came from the notification's ground colour,
        // which is black unless the user has been into the settings to say otherwise. So
        // the cell was a black-tinted glass by default, with coloured liquid behind the
        // tint.
        //
        // Clear, then — and clear here means the same thing it means on the buttons: no
        // colour at all through the middle of the range, and a tint only at the two ends
        // where clear glass genuinely stops reading. The backdrop is the only thing that
        // gives it one. See clearReactive, which exists for exactly this.
        val paned = glassy && lens.pane(canvas, chargeRect, r, null, rim = false)

        canvas.save()
        canvas.clipPath(glass)
        if (!paned) {
            // The glass's own ground, so the liquid has something to be inside rather than
            // floating over whatever the panel was drawing there.
            liquidPaint.style = Paint.Style.FILL
            liquidPaint.shader = null
            liquidPaint.color =
                (ground and 0x00FFFFFF) or (CHARGE_GROUND_ALPHA shl 24)
            canvas.drawRect(left, top, right, bottom, liquidPaint)
        }

        // ---- the liquid, as a SURFACE ----------------------------------------------
        //
        // Drops were the second attempt and metaballs the third, and both were the wrong
        // shape of answer. Blobs merging and pulling apart is what happens to liquid
        // moving through air; this is a vessel with liquid sitting in it, and however
        // well a heap of circles is packed the eye keeps finding the lumps — because a
        // still liquid in a glass does not have any.
        //
        // What it has is a cell.surface. One smooth line that ripples when disturbed and
        // settles flat when left alone, which is a height field: see LiquidSurface.
        val now = nowMs
        val dt = if (cell.stepAt == 0L) 0f else (now - cell.stepAt) / 1000f
        cell.stepAt = now

        val (rawX, rawY) = ChargeOverlay.gravity(gravityX, gravityY)
        // THE DRAWN DOWN LAGS THE MEASURED ONE, on purpose.
        //
        // The level is worked out from where down is, so following the sensor exactly
        // makes a rotation a teleport: turn the phone over and the water is on the other
        // side on the very next frame. Swinging the direction round instead means the
        // surface tilts through every angle in between, which is the liquid going round
        // the glass rather than reappearing somewhere else.
        val want = Math.atan2(rawX.toDouble(), rawY.toDouble()).toFloat()
        cell.down =
            if (cell.stepAt == 0L || dt <= 0f) want
            else ChargeOverlay.turnToward(cell.down, want, dt, ChargeOverlay.TURN_RATE)
        val downX = Math.sin(cell.down.toDouble()).toFloat()
        val downY = Math.cos(cell.down.toDouble()).toFloat()
        cell.surface.step(dt, downX, downY)

        // A knock as the level passes each tenth, so the arriving liquid disturbs the
        // surface instead of the level sliding up under a sheet of glass.
        val tenth = (fill * 10f).toInt()
        if (tenth != cell.tenth) {
            cell.tenth = tenth
            cell.surface.splash(0.35f + 0.3f * ((tenth * 7) % 10) / 10f, ChargeOverlay.SPLASH)
        }

        canvas.save()
        canvas.clipPath(glass)
        // Along the surface, which is perpendicular to down. Everything below is built in
        // this frame and then simply drawn — nothing needs to know the angle.
        val acrossX = -downY
        val acrossY = downX
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val cw2 = (right - left)
        val ch2 = (bottom - top)
        // How far the cell reaches along each of the two directions. Generous on purpose:
        // the polygon is clipped to the glass, so overshooting costs nothing and running
        // short would leave a gap at a corner.
        val depth = Math.abs(cw2 * downX) + Math.abs(ch2 * downY)
        val span = Math.abs(cw2 * acrossX) + Math.abs(ch2 * acrossY)
        // Where the flat surface sits: at the top of the cell when empty, the bottom when
        // full, measured along the down axis.
        val level = (0.5f - fill) * depth

        val body = android.graphics.Path()
        for (i in 0 until cell.surface.size) {
            val u = (i / (cell.surface.size - 1f) - 0.5f) * span
            val d = level + cell.surface.heightAt(i)
            val px = cx + acrossX * u + downX * d
            val py = cy + acrossY * u + downY * d
            if (i == 0) body.moveTo(px, py) else body.lineTo(px, py)
        }
        // Close it off well past the far side, so the fill runs to the bottom of the glass
        // whichever way up the glass currently is.
        body.lineTo(cx + acrossX * span / 2f + downX * depth, cy + acrossY * span / 2f + downY * depth)
        body.lineTo(cx - acrossX * span / 2f + downX * depth, cy - acrossY * span / 2f + downY * depth)
        body.close()

        // WHAT THE LIQUID DOES TO WHAT IS BEHIND IT, before the liquid's own colour goes
        // over the top. The tint is translucent, so the magnified panel reads THROUGH it
        // — which is the right order: the readout is behind the liquid, not floating on
        // it. Only when the cell is really made of glass; without a pane there is no
        // backdrop to magnify and the tint alone is what the cell has always been.
        if (paned) lens.magnify(canvas, chargeRect, body, Glass.LIQUID_ZOOM)

        liquidPaint.style = Paint.Style.FILL
        liquidPaint.shader = null
        liquidPaint.color = (tint and 0x00FFFFFF) or (liquidAlpha shl 24)
        canvas.drawPath(body, liquidPaint)
        inside?.invoke(canvas, body)

        // The meniscus. A liquid's surface catches the light, and a brighter line along
        // it is most of what separates "filled shape" from "something with a top".
        //
        // NOT UNDER GLASS, where the liquid magnifies. The lit line was standing in for a
        // surface, and the magnification IS one: what is behind the cell steps as it
        // crosses the level, which is the same statement made by the thing itself rather
        // than by a line drawn where the thing would be. Kept together they read as two
        // tops — a drawn edge sitting a hair off the optical one that the lens produces.
        if (!glassy) {
            liquidPaint.style = Paint.Style.STROKE
            liquidPaint.strokeWidth = CHARGE_SURFACE
            liquidPaint.color = (tint and 0x00FFFFFF) or (CHARGE_MENISCUS shl 24)
            val skin = android.graphics.Path()
            for (i in 0 until cell.surface.size) {
                val u = (i / (cell.surface.size - 1f) - 0.5f) * span
                val d = level + cell.surface.heightAt(i)
                val px = cx + acrossX * u + downX * d
                val py = cy + acrossY * u + downY * d
                if (i == 0) skin.moveTo(px, py) else skin.lineTo(px, py)
            }
            canvas.drawPath(skin, liquidPaint)
            liquidPaint.style = Paint.Style.FILL
        }

        // ---- the surges ------------------------------------------------------------
        //
        // Charge going in, shown the way a cloud shows it: lit from inside, with the
        // source itself never visible. A drawn bolt would be a bolt on top of a picture of
        // water; a bright patch under the surface whose edges are lost in the body is the
        // water having something happen in it.
        //
        // CLIPPED TO THE LIQUID, which is the whole of why it works. The glow stops
        // exactly where the liquid does, so it lights the stuff rather than being painted
        // over it — and above the surface there is nothing to light, correctly.
        if (surgeAt != null) cell.surge.update(surgeAt, fill)
        if (surgeAt != null && cell.surge.size > 0) {
            canvas.save()
            canvas.clipPath(body)
            cell.surge.forEach { flash ->
                val b = cell.surge.brightness(flash, surgeAt)
                if (b <= 0.01f) return@forEach
                // Placed in the LIQUID's frame — across the surface and down into the
                // depth — so a flash stays where it is in the water while the water turns.
                val u = (flash.u - 0.5f) * span
                val d = level + flash.v * (depth / 2f - level).coerceAtLeast(1f)
                val px = cx + acrossX * u + downX * d
                val py = cy + acrossY * u + downY * d
                val r = (right - left) * flash.size
                // THE CORE IS NEARLY WHITE, not the liquid's own colour.
                //
                // Drawn in the tint it was invisible, and obviously so in hindsight:
                // adding more of a colour to a body already made of it reads as slightly
                // more liquid, not as light. Measured, the flashes were firing the whole
                // time — one alight, brightness peaking around three quarters — and
                // nothing could be seen. A discharge is white-hot at the middle and takes
                // the colour of what it is shining through further out, so that is what
                // the gradient does.
                gleam.shader = android.graphics.RadialGradient(
                    px, py, r,
                    intArrayOf(
                        lighten(tint, SURGE_WHITEN) or
                            ((SURGE_CORE * b).toInt().coerceIn(0, 255) shl 24),
                        (tint and 0x00FFFFFF) or
                            ((SURGE_HALO * b).toInt().coerceIn(0, 255) shl 24),
                        tint and 0x00FFFFFF,
                    ),
                    floatArrayOf(0f, 0.3f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(px, py, r, gleam)
            }
            gleam.shader = null
            canvas.restore()
        }
        canvas.restore()

        // And the glass's clip, so the rim below is a full stroke rather than a half one
        // cut down its own centre line.
        //
        // ONE restore for one save, and it is worth being careful here: this function has
        // had the pairing wrong in both directions within an hour. First a restore was
        // dropped and the rim was drawn inside the clip; then the metaball rewrite took
        // out a save and left its restore behind, which is not a cosmetic fault — the
        // canvas underflows, onDraw throws, and the panel process dies on the frame the
        // charger is plugged in.
        canvas.restore()

        // The rim, drawn after the clip is lifted so it is a full stroke rather than a
        // half one clipped down its own centre line.
        //
        // NOT UNDER GLASS. This is the cell's painted outline, in the battery's own
        // colour, and it is what ORIGINAL uses to say where the glass is. A pane says
        // that by refracting, so the outline becomes a second answer to a question
        // already answered — a green line around a piece of glass, which is the one thing
        // a piece of glass does not have.
        if (!paned) {
            rimPaint.style = Paint.Style.STROKE
            rimPaint.strokeWidth = RearDialView.CHARGE_RIM
            rimPaint.color = (tint and 0x00FFFFFF) or (rimAlpha shl 24)
            canvas.drawRoundRect(left, top, right, bottom, r, r, rimPaint)
            rimPaint.style = Paint.Style.FILL
        }

        // ---- the figure ------------------------------------------------------------
        //
        // BESIDE THE CELL AND RIDING THE SURFACE, rather than centred in the glass.
        //
        // It sat in the middle of the cell, which put a number over a column of moving
        // liquid — legible, because it was drawn over the top, but placed somewhere that
        // had nothing to do with the reading. Set beside the cell at the height of the
        // water it becomes a label ON the level: the two say the same thing in the same
        // place, and the figure rises as the cell fills.
        //
        // The height comes from the surface's own midpoint, so it follows the liquid
        // exactly — including when the phone is turned and the level tilts.
        val text = figure
        // Beside the glass: on its left for the charging cell, which sits at the right
        // edge; on its right for the control centre's, which sits at the left. Named for
        // the charging cell's side; it is the edge the figure is anchored by either way.
        val figureRight = if (figureOnRight) right + ChargeOverlay.FIGURE_GAP else left - ChargeOverlay.FIGURE_GAP
        // Clamped to the cell's own span. An empty cell puts the surface at the very top
        // and a full one at the very bottom, and a figure centred on either would hang
        // half off the panel — which is where the reading matters most.
        val figureHalf = (right - left) * ChargeOverlay.TEXT_SIZE * 0.5f
        val figureY = (cy + downY * level)
            .coerceIn(top + figureHalf, bottom - figureHalf)

        val glassFigure = glassy
        figurePaint.textSize = (right - left) * ChargeOverlay.TEXT_SIZE
        val was = figurePaint.textAlign
        figurePaint.textAlign = if (figureOnRight) Paint.Align.LEFT else Paint.Align.RIGHT
        figurePaint.color = figureTint

        // MADE OF GLASS, if there is a panel behind it to bend. Type is nearly all edge,
        // so it takes a distance field of its own rather than the rounded-rect arithmetic
        // every pane here uses — see SdfText.
        // GROWN FROM THE CELL'S EDGE, which is where it is anchored anyway: the figure's
        // right edge is a fixed gap from the cell, and the cell's own slide is already in
        // that number, so scaling about that point makes the two arrive as one object.
        if (grow > 0.01f) {
            fun drawFigure(color: Int) {
                figurePaint.color = color
                canvas.save()
                canvas.scale(grow, grow, figureRight, figureY)
                // The glass figure is placed by its right edge whichever side it is on.
                val sdfRight = if (figureOnRight) figureRight + figurePaint.measureText(text) else figureRight
                if (!glassFigure || !lens.text(canvas, text, figurePaint, sdfRight, figureY)) {
                    val m = figurePaint.fontMetrics
                    canvas.drawText(
                        text, figureRight, figureY - (m.ascent + m.descent) / 2f, figurePaint,
                    )
                }
                canvas.restore()
            }
            // Clear of everything: as it is. Over a shape: turned over as far as that shape
            // asks — its colour inverted, its opacity kept — so it stays legible on a filled
            // tile of its own colour and unchanged on an empty one.
            canvas.save()
            figureOver.forEach { (shape, _) -> canvas.clipOutPath(shape) }
            drawFigure(figureTint)
            canvas.restore()
            figureOver.forEach { (shape, turn) ->
                canvas.save()
                canvas.clipPath(shape)
                val inverted = (figureTint and 0xFF000000.toInt()) or (figureTint.inv() and 0x00FFFFFF)
                val rgb = RampColor.lerp(figureTint, inverted, turn.coerceIn(0f, 1f)) and 0x00FFFFFF
                drawFigure((figureTint and 0xFF000000.toInt()) or rgb)
                canvas.restore()
            }
        }
        figurePaint.textAlign = was
    }

    companion object {
        /** The rear's corner, measured, for a caller with no window to ask (RearDialView.FALLBACK_CORNER). */
        const val FALLBACK_CORNER = 97f

        // Near enough to opaque. At 0xE6 the readout underneath still read through the
        // glass — the masthead and the bar figures are bright and thin, which is exactly
        // what survives a wash — and a cell you can read the panel through looks like a
        // tint laid over the panel rather than an object in front of it.
        private const val CHARGE_GROUND_ALPHA = 0xF7

        private const val CHARGE_SURFACE = 2.5f
        private const val CHARGE_MENISCUS = 0xDD

        /** A discharge's brightest point, and the halo around it. */
        private const val SURGE_CORE = 240f
        private const val SURGE_HALO = 90f

        /** How far the core is pushed toward white. */
        private const val SURGE_WHITEN = 0.72f
    }
}
