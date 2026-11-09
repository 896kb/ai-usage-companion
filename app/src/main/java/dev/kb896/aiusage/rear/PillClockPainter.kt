package dev.kb896.aiusage.rear

import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.kb896.aiusage.data.PanelConfig

/**
 * Draws the pill clock (PillClock): the hour and minute hands as glass capsules, the second hand
 * as a glass bottle, hour under minute under second.
 *
 * EACH HAND IS A GLASS CARD, the panel's own: drawn as the cards are (RearDialView.glassCard —
 * held, the ambient rim, the ground under it), so the clock is made of the same glass as
 * everything round it. Its colour is the clock's, as the glass figures of the digital face are
 * coloured, whatever the Glass settings' tint or the light under it — all alike: a tint and a
 * soft core of light inside, added over the glass (BlendMode.PLUS) and kept within its shape
 * (the owner, 2026-11-09).
 *
 * THE HOUR PILL ALSO CASTS A COLOURED SHADOW, as a CSS box-shadow does: a blurred capsule of
 * the clock's colour under it, which its glass covers where it stands, so it shows only round
 * it.
 *
 * THE SECOND HAND IS A BOTTLE (the owner, 2026-11-09): a wider body round the centre, a
 * shoulder, and the long neck that points (PillClock.BOTTLE_BODY and on). One pane with the
 * bottle's outline: the lens is its box's, whose sides are the body's, so the body bends as the
 * capsules do and the neck mostly shows straight through, edged by the rim along the outline.
 *
 * THE SECOND HAND IS BORN FROM THE MINUTE HAND (PillClock.split): switched on, it peels out of it
 * joined by a sticky web of the same tinted glass, which stretches and snaps as it swings to its
 * place and grows; switched off, it runs back and is taken in. While it peels it shows only
 * OUTSIDE the minute hand, so it grows out of that glass rather than appearing over it; over
 * the peel's last stretch the part over the minute hand fades in, and at rest it lies OVER the
 * minute hand (the owner, 2026-11-09) — rising over it, not popping onto it as the peel ends.
 * The web is drawn only between the two hands, never under either, and bridges only where they
 * are still close (PillClock.webReach). Drawing them as one merged path made a lens of its
 * bounding box (a new one every frame, a refraction that jumped as the web let go), and the web
 * timed alone opened into a fan between hands already far apart (2026-11-09).
 *
 * UNDER THE ORIGINAL THEME THERE IS NO GLASS (the owner, 2026-11-09): the hands are solid, in
 * the clock's colour, as that theme's figures are — no pane, no core, no coloured shadow
 * ([glass] false) — each over a soft dark drop shadow of its own (the owner, 2026-11-09), so a hand
 * crossing another stands off it rather than running into one shape of the same colour. The
 * shapes, the peel and the order are the same.
 *
 * EVERY HAND IS LIT FROM THE PANEL'S LIGHT, not from one that turns with it (GlassPainter's
 * lightTurn): turned, the rim's light used to turn too, so a hand changed its lit side as it moved.
 *
 * A rounded rect with its corner at half its thickness is a capsule, a shape the lens already
 * bends; drawn under a canvas rotated about the dial's centre (GlassPainter samples through
 * the inverse there), it bends what is under it. One shape a hand, one cached lens each: nothing
 * is built per frame at rest. One shader with the angles as uniforms would have needed a new
 * RenderEffect every frame, which is the leak GlassPainter.lensFor ended.
 */
class PillClockPainter {
    private val rect = RectF()
    private val core = RectF()
    private val arc = RectF()
    private val bounds = RectF()
    private val shape = Path()
    private val bottleLocal = Path()
    private val web = Path()
    private val minuteShape = Path()
    private val budShape = Path()
    private val turn = android.graphics.Matrix()
    private val webPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DROP_COLOR }
    private var dropBlur = -1f
    /** This frame's: glass hands, or solid ones (the ORIGINAL theme). */
    private var glass = true
    /** This frame's tint strength (PanelConfig.clockGlassTint): the glass's colour and the hour's halo both follow it. */
    private var strength = 1f
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowBlur = -1f
    private var shadowBlur = -1f

    /**
     * The face at [alpha]; the second hand out of the minute hand by [seconds], 0 (none) to 1
     * (its own). [ink] is the clock's colour. [pane] draws one glass card in the canvas's current
     * space: rect, corner, tint, how far the canvas is turned (its rim lit from the panel's
     * light, not one that turns with the hand), and an outline of its own or null for the
     * rounded rect. [glass] false (the ORIGINAL theme): solid hands in [ink], and [pane] unused.
     */
    fun draw(
        canvas: Canvas,
        dial: PillClock.Dial,
        angles: PillClock.Angles,
        ink: Int,
        seconds: Float,
        alpha: Float,
        glass: Boolean,
        /** How much of [ink] the glass takes, 0 clear to 1 (PanelConfig.clockGlassTint). */
        tintStrength: Float = 1f,
        pane: (Canvas, RectF, Float, Int, Float, Path?) -> Boolean,
    ) {
        if (alpha <= 0.004f) return
        this.glass = glass
        this.strength = tintStrength.coerceIn(0f, 1f)
        solid.color = ink or (0xFF shl 24)
        val layer = if (alpha < 0.996f) {
            val room = PillClock.HOUR.thickness * dial.r * SHADOW_BLUR * 2f
            bounds.set(dial.left - room, dial.top - room, dial.right + room, dial.bottom + room)
            canvas.saveLayerAlpha(bounds, (255 * alpha).toInt().coerceIn(0, 255))
        } else -1
        val tint = if (glass) withAlpha(ink, TINT_MAX * strength) else ink or (0xFF shl 24)
        hand(canvas, dial, PillClock.HOUR.length, PillClock.HOUR.thickness, angles.hour, tint, ink, cast = true, pane)
        when {
            seconds <= 0.001f -> minute(canvas, dial, angles, tint, ink, pane)
            seconds >= 0.999f -> {
                minute(canvas, dial, angles, tint, ink, pane)
                bottle(canvas, dial, PillClock.SECOND.length, PillClock.SECOND.thickness, angles.second, tint, ink, pane)
            }
            else -> {
                val s = PillClock.split(angles.minute, angles.second, seconds)
                capsule(dial, PillClock.MINUTE.length, PillClock.MINUTE.thickness, angles.minute, minuteShape)
                if (s.web > 0.01f) {
                    // Only between the hands: never under either, where it would tint through.
                    bottleOutline(dial, s.length, s.thickness)
                    budShape.set(bottleLocal)
                    turn.setRotate(s.angle, dial.cx, dial.cy)
                    budShape.transform(turn)
                    canvas.save()
                    canvas.clipOutPath(minuteShape)
                    canvas.clipOutPath(budShape)
                    web(canvas, dial, angles.minute, s, tint)
                    canvas.restore()
                }
                minute(canvas, dial, angles, tint, ink, pane)
                // Out of the minute hand: only where it has come out of it...
                canvas.save()
                canvas.clipOutPath(minuteShape)
                bottle(canvas, dial, s.length, s.thickness, s.angle, tint, ink, pane)
                canvas.restore()
                // ...and over the peel's last stretch, rising over it where it still lies on it.
                val over = smooth(OVER_FROM, 1f, seconds)
                if (over > 0.004f) {
                    canvas.save()
                    canvas.clipPath(minuteShape)
                    bounds.set(dial.left, dial.top, dial.right, dial.bottom)
                    canvas.saveLayerAlpha(bounds, (255 * over).toInt().coerceIn(0, 255))
                    bottle(canvas, dial, s.length, s.thickness, s.angle, tint, ink, pane)
                    canvas.restore()
                    canvas.restore()
                }
            }
        }
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /**
     * The face flat, for the change of face (ClockGoo): the hands' outlines filled with [paint],
     * the second hand where [seconds] has it. No glass, no light — the goo is its own material.
     */
    fun silhouette(canvas: Canvas, dial: PillClock.Dial, angles: PillClock.Angles, seconds: Float, paint: Paint) {
        flat(canvas, dial, PillClock.HOUR.length, PillClock.HOUR.thickness, angles.hour, paint)
        flat(canvas, dial, PillClock.MINUTE.length, PillClock.MINUTE.thickness, angles.minute, paint)
        if (seconds > 0.001f) {
            val s = PillClock.split(angles.minute, angles.second, seconds)
            bottleOutline(dial, s.length, s.thickness)
            canvas.save()
            canvas.rotate(s.angle, dial.cx, dial.cy)
            canvas.drawPath(bottleLocal, paint)
            canvas.restore()
        }
    }

    private fun flat(canvas: Canvas, dial: PillClock.Dial, length: Float, thickness: Float, angle: Float, paint: Paint) {
        boxOf(dial, length, thickness)
        canvas.save()
        canvas.rotate(angle, dial.cx, dial.cy)
        canvas.drawRoundRect(rect, rect.width() / 2f, rect.width() / 2f, paint)
        canvas.restore()
    }

    private fun minute(
        canvas: Canvas, dial: PillClock.Dial, angles: PillClock.Angles, tint: Int, ink: Int,
        pane: (Canvas, RectF, Float, Int, Float, Path?) -> Boolean,
    ) = hand(canvas, dial, PillClock.MINUTE.length, PillClock.MINUTE.thickness, angles.minute, tint, ink, cast = false, pane)

    /** One capsule hand [length] long and [thickness] across, at [angle]: its shadow if it [cast]s one, its glass, its core. */
    private fun hand(
        canvas: Canvas,
        dial: PillClock.Dial,
        length: Float,
        thickness: Float,
        angle: Float,
        tint: Int,
        ink: Int,
        cast: Boolean,
        pane: (Canvas, RectF, Float, Int, Float, Path?) -> Boolean,
    ) {
        boxOf(dial, length, thickness)
        val r = rect.width() / 2f
        canvas.save()
        canvas.rotate(angle, dial.cx, dial.cy)
        if (!glass) {
            dropShadow(canvas, dial, angle) { canvas.drawRoundRect(rect, r, r, drop) }
            canvas.drawRoundRect(rect, r, r, solid)
            canvas.restore()
            return
        }
        if (cast && strength > 0.004f) {
            val blur = rect.width() * SHADOW_BLUR
            if (blur != shadowBlur) {
                shadowBlur = blur
                shadow.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            // The halo is the tint's light: as clear as the glass is (PanelConfig.clockGlassTint).
            shadow.color = withAlpha(ink, (SHADOW_ALPHA * strength / PanelConfig.CLOCK_GLASS_TINT).coerceAtMost(SHADOW_MAX))
            canvas.drawRoundRect(rect, r, r, shadow)
        }
        pane(canvas, rect, r, tint, angle, null)
        if (INNER_GLOW) {
            shape.rewind()
            shape.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(shape)
            drawCore(canvas, ink, rect)
            canvas.restore()
        }
        canvas.restore()
    }

    /** The second hand, a bottle [length] long with a neck [thickness] across, at [angle]: its glass and its cores. */
    private fun bottle(
        canvas: Canvas, dial: PillClock.Dial, length: Float, thickness: Float, angle: Float, tint: Int, ink: Int,
        pane: (Canvas, RectF, Float, Int, Float, Path?) -> Boolean,
    ) {
        bottleOutline(dial, length, thickness)
        val hb = thickness * dial.r / 2f * PillClock.BOTTLE_BODY
        canvas.save()
        canvas.rotate(angle, dial.cx, dial.cy)
        if (!glass) {
            dropShadow(canvas, dial, angle) { canvas.drawPath(bottleLocal, drop) }
            canvas.drawPath(bottleLocal, solid)
            canvas.restore()
            return
        }
        pane(canvas, rect, hb, tint, angle, bottleLocal)
        if (!INNER_GLOW) {
            canvas.restore()
            return
        }
        canvas.save()
        canvas.clipPath(bottleLocal)
        // A core down the neck, and a wider one in the body.
        val hn = thickness * dial.r / 2f
        val bottom = rect.bottom
        core.set(dial.cx - hn, rect.top, dial.cx + hn, bottom)
        drawCore(canvas, ink, core)
        core.set(dial.cx - hb, dial.cy - PillClock.BOTTLE_BODY_TOP * dial.r, dial.cx + hb, bottom)
        drawCore(canvas, ink, core)
        canvas.restore()
        canvas.restore()
    }

    /**
     * The bottle's outline pointing up from the dial's centre, into [bottleLocal], and its box into
     * [rect]: a rounded base round the centre, straight sides up to [PillClock.BOTTLE_BODY_TOP], a
     * shoulder easing in to the neck, and the neck's rounded tip at [length].
     */
    private fun bottleOutline(dial: PillClock.Dial, length: Float, thickness: Float) {
        val r = dial.r
        val cx = dial.cx
        val hn = thickness * r / 2f
        val hb = hn * PillClock.BOTTLE_BODY
        val bottom = dial.cy + TAIL * r
        val bodyTop = dial.cy - PillClock.BOTTLE_BODY_TOP * r
        val neck = bodyTop - PillClock.BOTTLE_SHOULDER * r
        val tip = dial.cy - length * r
        val k = (bodyTop - neck) * SHOULDER_EASE
        bottleLocal.rewind()
        bottleLocal.moveTo(cx - hb, bottom - hb)
        bottleLocal.lineTo(cx - hb, bodyTop)
        bottleLocal.cubicTo(cx - hb, bodyTop - k, cx - hn, neck + k, cx - hn, neck)
        bottleLocal.lineTo(cx - hn, tip + hn)
        arc.set(cx - hn, tip, cx + hn, tip + 2f * hn)
        bottleLocal.arcTo(arc, 180f, 180f, false)
        bottleLocal.lineTo(cx + hn, neck)
        bottleLocal.cubicTo(cx + hn, neck + k, cx + hb, bodyTop - k, cx + hb, bodyTop)
        bottleLocal.lineTo(cx + hb, bottom - hb)
        arc.set(cx - hb, bottom - 2f * hb, cx + hb, bottom)
        bottleLocal.arcTo(arc, 0f, 180f, false)
        bottleLocal.close()
        rect.set(cx - hb, tip, cx + hb, bottom)
    }

    /** The soft core of light down [box]'s middle, in [ink]; the caller clips it to the glass. */
    private fun drawCore(canvas: Canvas, ink: Int, box: RectF) {
        val inset = box.width() * GLOW_INSET
        val l = box.left + inset
        val t = box.top + inset
        val rr = box.right - inset
        val b = box.bottom - inset
        if (rr <= l) return
        val blur = (box.width() * GLOW_BLUR).coerceAtLeast(0.5f)
        if (blur != glowBlur) {
            glowBlur = blur
            glow.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        glow.color = withAlpha(ink, GLOW_ALPHA)
        canvas.drawRoundRect(l, t, rr, b, (rr - l) / 2f, (rr - l) / 2f, glow)
    }

    /**
     * The web between the minute hand and the second hand peeling off it: from the centre out
     * along both to [PillClock.webReach], its edge drawn in towards the centre — the neck of a
     * drop pulling apart. Tinted as the glass is; the caller keeps it off both hands.
     */
    private fun web(canvas: Canvas, dial: PillClock.Dial, minuteAngle: Float, s: PillClock.Split, tint: Int) {
        val reach = PillClock.webReach(dial.r, minuteAngle, s.angle, s.length, s.thickness, s.web)
        if (reach < PillClock.MINUTE.thickness * dial.r / 2f) return // all of it inside the hands
        val a1 = Math.toRadians(minuteAngle.toDouble())
        val a2 = Math.toRadians(s.angle.toDouble())
        val x1 = Math.sin(a1).toFloat(); val y1 = -Math.cos(a1).toFloat()
        val x2 = Math.sin(a2).toFloat(); val y2 = -Math.cos(a2).toFloat()
        val pull = reach * (WEB_PULL_MIN + (1f - WEB_PULL_MIN) * s.web)
        web.rewind()
        web.moveTo(dial.cx, dial.cy)
        web.lineTo(dial.cx + x1 * reach, dial.cy + y1 * reach)
        web.quadTo(dial.cx + (x1 + x2) / 2f * pull, dial.cy + (y1 + y2) / 2f * pull, dial.cx + x2 * reach, dial.cy + y2 * reach)
        web.close()
        webPaint.color = tint
        canvas.drawPath(web, webPaint)
    }

    /** A hand's capsule, turned to [angle] about the dial's centre, into [out] (panel space). */
    private fun capsule(dial: PillClock.Dial, length: Float, thickness: Float, angle: Float, out: Path) {
        boxOf(dial, length, thickness)
        out.rewind()
        out.addRoundRect(rect, rect.width() / 2f, rect.width() / 2f, Path.Direction.CW)
        turn.setRotate(angle, dial.cx, dial.cy)
        out.transform(turn)
    }

    /** A hand [length] long and [thickness] across (in the dial's radius) pointing up, into [rect]. */
    private fun boxOf(dial: PillClock.Dial, length: Float, thickness: Float) {
        val half = thickness * dial.r / 2f
        rect.set(dial.cx - half, dial.cy - length * dial.r, dial.cx + half, dial.cy + TAIL * dial.r)
    }

    /**
     * A solid hand's drop shadow, drawn by [shape] with [drop]: blurred, and set straight down the
     * panel — the canvas is turned by [angle] for the hand, so the offset is turned back by it.
     */
    private inline fun dropShadow(canvas: Canvas, dial: PillClock.Dial, angle: Float, shape: () -> Unit) {
        val blur = dial.r * DROP_BLUR
        if (blur != dropBlur) {
            dropBlur = blur
            drop.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        val a = Math.toRadians(angle.toDouble())
        val down = dial.r * DROP_DOWN
        canvas.save()
        canvas.translate((down * Math.sin(a)).toFloat(), (down * Math.cos(a)).toFloat())
        shape()
        canvas.restore()
    }

    private fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((255 * a).toInt().coerceIn(0, 255) shl 24)

    private fun smooth(e0: Float, e1: Float, t: Float): Float {
        val x = ((t - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private companion object {
        /**
         * A soft core of light inside each hand, over its tint. Off, trying the plain tint alone
         * (the owner, 2026-11-09: "not sure I like the glow"); true brings it back.
         */
        const val INNER_GLOW = false
        /**
         * How much of the clock's colour each hand's glass takes at the clock's full glass tint
         * (PanelConfig.clockGlassTint): nearly the colour itself. At its default that is the 0.30
         * wash the hands always had.
         */
        const val TINT_MAX = 0.85f
        /** Each hand's core of light: inset from the rim and blurred, in its width, and its strength. */
        const val GLOW_INSET = 0.22f
        const val GLOW_BLUR = 0.28f
        const val GLOW_ALPHA = 0.85f
        /**
         * The hour pill's coloured shadow: its blur, in the pill's width, and its strength at the
         * default glass tint — fading with a clearer glass, a little stronger, to [SHADOW_MAX], with
         * a more coloured one.
         */
        const val SHADOW_BLUR = 0.45f
        const val SHADOW_ALPHA = 0.6f
        const val SHADOW_MAX = 0.9f
        /** Every hand's tail behind the centre, in the dial's radius (PillClock's hands all have it). */
        val TAIL = PillClock.MINUTE.tail
        /** How far in the web's edge is drawn at its thinnest, in its reach. */
        const val WEB_PULL_MIN = 0.15f
        /** Where in the peel the second hand starts rising over the minute hand. */
        const val OVER_FROM = 0.75f
        /** A solid hand's drop shadow (ORIGINAL): its colour, its blur and how far down it falls, in the dial's radius. */
        const val DROP_COLOR = 0x73000000
        const val DROP_BLUR = 0.035f
        const val DROP_DOWN = 0.02f
        /** How round the bottle's shoulder is, in the shoulder's length. */
        const val SHOULDER_EASE = 0.55f
    }
}
