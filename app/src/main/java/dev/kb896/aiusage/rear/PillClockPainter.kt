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
 * THE HOUR PILL GLOWS, AND THE OTHERS ARE CLEAR GLASS OVER IT (the owner, 2026-11-10, after the
 * wallpaper again: "one of them glows and the light refracts in the others"). The hour hand is
 * the lamp: its glass filled with the clock's light, a halo of it round it. The minute and the
 * second hand are dark, clear glass, and what they bend is the ground WITH THE LIT HOUR HAND ON
 * IT ([draw]'s light), so where one lies over the lamp the lamp shows through it, bent by its
 * edge. Each also catches the light where it comes in, at the hub all three share: a wash of it
 * fading out along the hand, and its rim lit the same way ([caught]).
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
    /** This frame's tint strength (PanelConfig.clockGlassTint): how much of the clock's colour the glass takes. */
    private var strength = 1f
    /** The lamp's light inside its glass, and the light the other hands catch: added over the glass. */
    private val emit = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val washRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS; style = Paint.Style.STROKE }
    /** What [wash] and [washRim] were last built for: the dial and the colour. */
    private var washKey = FloatArray(4) { Float.NaN }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadowBlur = -1f
    /** This frame's hour hand, in degrees: where the lamp lies, for the hands over it. */
    private var hourAngle = 0f
    /** This frame's time on the lamp's clock, in seconds ([draw]'s time). */
    private var lampTime = 0f
    /**
     * The arm the minute hand is growing, while the second hand morphs out of it: a capsule from
     * ([budAx], [budAy]) to ([budBx], [budBy]), [budR] round, in the minute hand's own turned
     * space, run into the minute hand's body over [budK]. Only while [bud].
     */
    private var bud = false
    private var budAx = 0f
    private var budAy = 0f
    private var budBx = 0f
    private var budBy = 0f
    private var budR = 0f
    private var budK = 0f
    /** A capsule hand's body, rim and light ([PILL]); null where it will not compile, and the plain washes stand in. */
    private val pill: android.graphics.RuntimeShader? by lazy {
        runCatching { android.graphics.RuntimeShader(PILL) }
            .onFailure { android.util.Log.w("AiUsageRear", "pill clock: the pill shader would not compile, the plain washes stand in", it) }
            .getOrNull()
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The face at [alpha]; the second hand out of the minute hand by [seconds], 0 (none) to 1
     * (its own). [ink] is the clock's colour. [pane] draws one glass card in the canvas's current
     * space: rect, corner, tint, how far the canvas is turned (its rim lit from the panel's
     * light, not one that turns with the hand), an outline of its own or null for the rounded
     * rect, and whether it lies over the lamp: true for the minute and the second hand, which
     * bend the ground with the lit hour hand on it, false for the hour hand, which bends the
     * ground alone. [light] is handed a hand to draw onto that ground for the panes after it:
     * the lit hour hand before the minute hand's pane is asked for, then the minute hand before
     * the second hand's. [glass] false (the ORIGINAL theme): solid hands
     * in [ink], and [pane] and [light] unused.
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
        /**
         * The lamp's own clock, in seconds: its glow drifts round its rim and swells and eases by
         * it. Held still, the glow is locked where it is (always-on).
         */
        time: Float = 0f,
        light: (draw: (Canvas) -> Unit) -> Unit = {},
        pane: (Canvas, RectF, Float, Int, Float, Path?, Boolean) -> Boolean,
    ) {
        if (alpha <= 0.004f) return
        lampTime = time
        this.glass = glass
        this.strength = tintStrength.coerceIn(0f, 1f)
        solid.color = ink or (0xFF shl 24)
        val layer = if (alpha < 0.996f) {
            val room = PillClock.HOUR.thickness * dial.r * HALO_BLUR * 2f
            bounds.set(dial.left - room, dial.top - room, dial.right + room, dial.bottom + room)
            canvas.saveLayerAlpha(bounds, (255 * alpha).toInt().coerceIn(0, 255))
        } else -1
        val lampTint = if (glass) withAlpha(ink, TINT_MAX * strength) else ink or (0xFF shl 24)
        // The second hand's glass, and the web's: the clock's colour a little deeper, as glass with
        // no light of its own. (The capsules' bodies are the pill shader's.)
        val tint = if (glass) withAlpha(shade(ink, CLEAR_SHADE), TINT_MAX * strength) else lampTint
        hourAngle = angles.hour
        if (glass) light { c -> hand(c, dial, PillClock.HOUR.length, PillClock.HOUR.thickness, angles.hour, lampTint, ink, lamp = true, pane) }
        hand(canvas, dial, PillClock.HOUR.length, PillClock.HOUR.thickness, angles.hour, lampTint, ink, lamp = true, pane)
        when {
            seconds <= 0.001f -> minute(canvas, dial, angles, tint, ink, light, pane)
            seconds >= 0.999f -> {
                minute(canvas, dial, angles, tint, ink, light, pane)
                bottle(canvas, dial, PillClock.SECOND.length, PillClock.SECOND.thickness, angles.second, tint, ink, pane)
            }
            !glass -> {
                // Solid hands (ORIGINAL) keep the peel they had: out from under the minute hand,
                // joined by a web, rising over it at the end.
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
                minute(canvas, dial, angles, tint, ink, light, pane)
                // Out of the minute hand: only where it has come out of it...
                canvas.save()
                canvas.clipOutPath(minuteShape)
                bottle(canvas, dial, s.length, s.thickness, s.angle, tint, ink, pane)
                canvas.restore()
                // ...and over the peel's last stretch, rising over it where it still lies on it.
                val over = smooth(SOLID_OVER_FROM, 1f, seconds)
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
            else -> {
                // THE SECOND HAND MORPHS OUT OF THE MINUTE HAND (the owner, 2026-11-10: "more like
                // morphing out of it rather than coming from behind it and suddenly show over
                // it"). It starts as the minute hand itself, lying on it, and swings away
                // narrowing and lengthening — all the while ONE body of glass with the minute
                // hand, the two run together where they are close (the pill shader's bud). Over
                // the middle of the run the second hand proper comes up through that arm, and
                // the arm thins away under it.
                val s = PillClock.split(angles.minute, angles.second, seconds)
                val t = seconds.coerceIn(0f, 1f)
                val e = if (t < 0.5f) 4f * t * t * t else 1f - (2f - 2f * t).let { it * it * it } / 2f
                val minuteR = PillClock.MINUTE.thickness * dial.r / 2f
                val neckR = PillClock.SECOND.thickness * dial.r / 2f
                val gone = smooth(BUD_GONE_FROM, 1f, t)
                budR = (minuteR + (neckR - minuteR) * e) * (1f - gone)
                val len = (PillClock.MINUTE.length + (PillClock.SECOND.length - PillClock.MINUTE.length) * e) * dial.r
                val a = Math.toRadians((s.angle - angles.minute).toDouble())
                val dx = Math.sin(a).toFloat()
                val dy = -Math.cos(a).toFloat()
                val back = (TAIL * dial.r - budR).coerceAtLeast(0f)
                val out = (len - budR).coerceAtLeast(0f)
                budAx = dial.cx - dx * back; budAy = dial.cy - dy * back
                budBx = dial.cx + dx * out; budBy = dial.cy + dy * out
                // Nothing run together at the very start: two bodies lying on each other, melted,
                // are a little bigger than one, and the minute hand swelled by a few pixels for a
                // frame as the arm came or went (the owner, 2026-11-10). The melt comes in as
                // the arm leaves.
                budK = dial.r * BUD_MELT * (1f - e) * smooth(0f, BUD_MELT_IN, t)
                bud = budR > 0.3f
                minute(canvas, dial, angles, tint, ink, light, pane)
                bud = false
                val up = smooth(BOTTLE_FROM, BOTTLE_BY, t)
                if (up > 0.004f) {
                    bounds.set(dial.left, dial.top, dial.right, dial.bottom)
                    canvas.saveLayerAlpha(bounds, (255 * up).toInt().coerceIn(0, 255))
                    bottle(canvas, dial, PillClock.SECOND.length, PillClock.SECOND.thickness, s.angle, tint, ink, pane)
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

    /**
     * The minute hand, and then onto the lit ground too ([draw]'s light), so the second hand over
     * it bends it as well as the lamp: over the lamp alone, the second hand showed the hour hand
     * through the minute hand as if that were not there (the owner, 2026-11-10).
     */
    private fun minute(
        canvas: Canvas, dial: PillClock.Dial, angles: PillClock.Angles, tint: Int, ink: Int,
        light: (draw: (Canvas) -> Unit) -> Unit,
        pane: (Canvas, RectF, Float, Int, Float, Path?, Boolean) -> Boolean,
    ) {
        hand(canvas, dial, PillClock.MINUTE.length, PillClock.MINUTE.thickness, angles.minute, tint, ink, lamp = false, pane)
        if (glass) light { c -> hand(c, dial, PillClock.MINUTE.length, PillClock.MINUTE.thickness, angles.minute, tint, ink, lamp = false, pane) }
    }

    /**
     * One capsule hand [length] long and [thickness] across, at [angle]. The [lamp] (the hour
     * hand): its halo, its glass, its light. Any other: clear glass over the lamp, and the light
     * it catches.
     */
    private fun hand(
        canvas: Canvas,
        dial: PillClock.Dial,
        length: Float,
        thickness: Float,
        angle: Float,
        tint: Int,
        ink: Int,
        lamp: Boolean,
        pane: (Canvas, RectF, Float, Int, Float, Path?, Boolean) -> Boolean,
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
        if (lamp) {
            val blur = rect.width() * HALO_BLUR
            if (blur != shadowBlur) {
                shadowBlur = blur
                shadow.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            // Swelling and easing with the rim's light ([PILL]'s pulse, the same sum).
            val swell = 0.82f + 0.12f * Math.sin(lampTime * 0.9).toFloat() + 0.06f * Math.sin(lampTime * 0.37 + 1.3).toFloat()
            shadow.color = withAlpha(ink, HALO_ALPHA * swell)
            canvas.drawRoundRect(rect, r, r, shadow)
        }
        val pill = pill
        if (pill == null) {
            pane(canvas, rect, r, tint, angle, null, !lamp)
            shape.rewind()
            shape.addRoundRect(rect, r, r, Path.Direction.CW)
            if (lamp) {
                emit.color = withAlpha(ink, LAMP_ALPHA)
                canvas.drawPath(shape, emit)
            } else {
                caught(canvas, dial, ink, shape)
            }
            canvas.restore()
            return
        }
        // The lens with no body of its own: the pill shader is the body, the rim and the light.
        pane(canvas, rect, r, 0, angle, null, !lamp)
        // The lamp (the hour hand) as this hand sees it, in its own turned space: a capsule from
        // behind the hub out along the hour hand.
        val a = Math.toRadians((hourAngle - angle).toDouble())
        val dx = Math.sin(a).toFloat()
        val dy = -Math.cos(a).toFloat()
        val lampR = PillClock.HOUR.thickness * dial.r / 2f
        val back = (TAIL * dial.r - lampR).coerceAtLeast(0f)
        val out = (PillClock.HOUR.length * dial.r - lampR).coerceAtLeast(0f)
        // The panel's light, from up and to the left, turned into the hand's space.
        val t = Math.toRadians(angle.toDouble())
        val ct = Math.cos(t).toFloat()
        val st = Math.sin(t).toFloat()
        pill.setFloatUniform("origin", rect.left, rect.top)
        pill.setFloatUniform("size", rect.width(), rect.height())
        pill.setFloatUniform("lampA", dial.cx - dx * back, dial.cy - dy * back)
        pill.setFloatUniform("lampB", dial.cx + dx * out, dial.cy + dy * out)
        pill.setFloatUniform("lampR", lampR)
        // The slot: the stretch of this hand's own middle line that lies over the lamp.
        var s0 = Float.NaN
        var s1 = Float.NaN
        if (!lamp) {
            val ax = dial.cx - dx * back; val ay = dial.cy - dy * back
            val bx = dial.cx + dx * out; val by = dial.cy + dy * out
            val ex = bx - ax; val ey = by - ay
            val ee = (ex * ex + ey * ey).coerceAtLeast(0.0001f)
            val reach = lampR * SLOT_OVER
            for (i in 0..SLOT_STEPS) {
                val y = rect.top + rect.height() * i / SLOT_STEPS
                val px = dial.cx - ax; val py = y - ay
                val h = ((px * ex + py * ey) / ee).coerceIn(0f, 1f)
                val qx = px - ex * h; val qy = py - ey * h
                if (qx * qx + qy * qy < reach * reach) {
                    if (s0.isNaN()) s0 = y
                    s1 = y
                }
            }
        }
        if (s0.isNaN()) pill.setFloatUniform("slot", 0f, 0f)
        else pill.setFloatUniform("slot", s0 - rect.top, s1 - rect.top)
        pill.setFloatUniform("lamp", if (lamp) 1f else 0f)
        pill.setFloatUniform("time", lampTime)
        pill.setFloatUniform("light", LIGHT_X * ct + LIGHT_Y * st, -LIGHT_X * st + LIGHT_Y * ct)
        pill.setFloatUniform("body", BODY_MIN + (BODY_MAX - BODY_MIN) * strength)
        pill.setColorUniform("ink", ink or (0xFF shl 24))
        val arm = bud && !lamp
        pill.setFloatUniform("budA", budAx, budAy)
        pill.setFloatUniform("budB", budBx, budBy)
        pill.setFloatUniform("bud", if (arm) budR else 0f, budK)
        pillPaint.shader = pill
        // With an arm the body reaches past the hand's own box: the shader finds its edge.
        if (arm) canvas.drawRect(dial.left, dial.top, dial.right, dial.bottom, pillPaint)
        else canvas.drawRoundRect(rect, r, r, pillPaint)
        canvas.restore()
    }

    /**
     * The lamp's light caught in a clear hand whose [outline] this is, in the hand's own turned
     * space: strongest at the hub, where it lies on the lamp, and gone by [CATCH_REACH] along it.
     * A wash inside the glass, and the rim lit over it.
     */
    private fun caught(canvas: Canvas, dial: PillClock.Dial, ink: Int, outline: Path) {
        val k = washKey
        if (k[0] != dial.cx || k[1] != dial.cy || k[2] != dial.r || k[3] != ink.toFloat()) {
            k[0] = dial.cx; k[1] = dial.cy; k[2] = dial.r; k[3] = ink.toFloat()
            val stops = floatArrayOf(0f, 0.45f, 1f)
            wash.shader = android.graphics.RadialGradient(
                dial.cx, dial.cy, dial.r * CATCH_REACH,
                intArrayOf(withAlpha(ink, CATCH_ALPHA), withAlpha(ink, CATCH_ALPHA * 0.3f), withAlpha(ink, 0f)),
                stops, android.graphics.Shader.TileMode.CLAMP,
            )
            washRim.shader = android.graphics.RadialGradient(
                dial.cx, dial.cy, dial.r * CATCH_RIM_REACH,
                intArrayOf(withAlpha(ink, CATCH_RIM_ALPHA), withAlpha(ink, CATCH_RIM_ALPHA * 0.4f), withAlpha(ink, 0f)),
                stops, android.graphics.Shader.TileMode.CLAMP,
            )
            washRim.strokeWidth = (dial.r * CATCH_RIM_WIDTH).coerceAtLeast(1f)
        }
        canvas.save()
        canvas.clipPath(outline)
        canvas.drawPath(outline, wash)
        // Clipped to the glass, so half the stroke shows: a line just inside the edge.
        canvas.drawPath(outline, washRim)
        canvas.restore()
    }

    /** The second hand, a bottle [length] long with a neck [thickness] across, at [angle]: clear glass over the lamp, and the light it catches. */
    private fun bottle(
        canvas: Canvas, dial: PillClock.Dial, length: Float, thickness: Float, angle: Float, tint: Int, ink: Int,
        pane: (Canvas, RectF, Float, Int, Float, Path?, Boolean) -> Boolean,
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
        pane(canvas, rect, hb, tint, angle, bottleLocal, true)
        caught(canvas, dial, ink, bottleLocal)
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

    /** [c] at [k] of its brightness, opaque. */
    private fun shade(c: Int, k: Float): Int {
        val r = ((c shr 16 and 0xFF) * k).toInt()
        val g = ((c shr 8 and 0xFF) * k).toInt()
        val b = ((c and 0xFF) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun smooth(e0: Float, e1: Float, t: Float): Float {
        val x = ((t - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    companion object {
        /**
         * How much of the clock's colour each hand's glass takes at the clock's full glass tint
         * (PanelConfig.clockGlassTint): nearly the colour itself. At its default that is the 0.30
         * wash the hands always had.
         */
        const val TINT_MAX = 0.85f
        /** The second hand's glass: the clock's colour at this much of its brightness. */
        const val CLEAR_SHADE = 0.6f
        /** A clear capsule's body, how solid it is: at no glass tint, and at all of it (PanelConfig.clockGlassTint). */
        const val BODY_MIN = 0.12f
        const val BODY_MAX = 0.8f
        /** The panel's light as the pills take it: from up and to the left (a unit vector, y down). */
        const val LIGHT_X = -0.6f
        const val LIGHT_Y = -0.8f
        /** The slot: how near the lamp's middle line a hand's must lie, in the lamp's half width, and how finely that is looked for. */
        const val SLOT_OVER = 0.85f
        const val SLOT_STEPS = 48

        /**
         * One capsule hand, standing up in its box ([origin], [size]): what is drawn over its lens.
         *
         * THE LAMP ([lamp] 1, the hour hand): glass too, as clear as the others are ([body]), with
         * the clock's light in it — hot at its edge and soft through its middle, as a tube lit
         * from inside — but not all the way round (the owner, 2026-11-10): about a fifth of its
         * rim is dark, in a few stretches, which drift round it as [time] runs, and the whole
         * light swells and eases. With [time] held it is locked as it stands (always-on).
         *
         * A CLEAR HAND ([lamp] 0): a body of the clock's colour gone deep ([body], how solid); a
         * hairline of light on its rim, bright where it faces the panel's [light] or lies near the
         * lamp (the capsule [lampA] to [lampB], [lampR] round) and faint round the rest; a soft
         * glow in from that rim; and THE SLOT — where it lies over the lamp ([slot], from where to
         * where down its own middle line), the lamp shows through as a bright capsule of its own
         * inside the glass, hard-edged, with its light spilling into the glass round it. That
         * shape is what the wallpaper's pills carry. Always a capsule along the hand: the lamp's
         * own outline cut by the hand's made a chipped shape where they crossed (2026-11-10).
         */
        const val PILL = """
            uniform float2 origin;
            uniform float2 size;
            uniform float2 lampA;
            uniform float2 lampB;
            uniform float lampR;
            uniform float2 slot;
            uniform float lamp;
            uniform float time;
            uniform float2 budA;
            uniform float2 budB;
            uniform float2 bud;
            uniform float2 light;
            uniform float body;
            layout(color) uniform half4 ink;

            float seg(float2 p, float2 a, float2 b) {
                float2 pa = p - a;
                float2 ba = b - a;
                float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
                return length(pa - ba * h);
            }

            half4 main(float2 xy) {
                float2 p = xy - origin;
                float r = size.x * 0.5;
                float2 q = float2(r, clamp(p.y, r, max(size.y - r, r)));
                float2 dv = p - q;
                float dist = length(dv);
                float edge = r - dist;
                float2 n = dv / max(dist, 0.001);
                if (bud.x > 0.0) {
                    // One body with the arm: the two distances run together where they are close.
                    float2 pa = xy - budA;
                    float2 ba = budB - budA;
                    float hh = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
                    float2 bv = pa - ba * hh;
                    float bl = length(bv);
                    float d1 = -edge;
                    float d2 = bl - bud.x;
                    float k = max(bud.y, 0.001);
                    float h = clamp(0.5 + 0.5 * (d2 - d1) / k, 0.0, 1.0);
                    edge = -(mix(d2, d1, h) - k * h * (1.0 - h));
                    n = normalize(mix(bv / max(bl, 0.001), n, h));
                }
                if (edge <= 0.0) { return half4(0.0); }
                float t = edge / r;
                float aa = smoothstep(0.0, 1.0, edge);
                float3 c = float3(ink.rgb);
                float3 hot = mix(c, float3(1.0), 0.55);

                float line = smoothstep(1.4, 0.2, edge);
                float3 deep = c * 0.4;

                if (lamp > 0.5) {
// Where round the rim, 0 to 1: down the right side, round the foot, up the
                    // left, round the tip.
                    float straight = max(size.y - 2.0 * r, 0.0);
                    float around = 2.0 * straight + 6.2832 * r;
                    float s;
                    if (p.y > size.y - r) { s = straight + r * acos(clamp(n.x, -1.0, 1.0)); }
                    else if (p.y < r) { s = 2.0 * straight + 3.1416 * r + r * acos(clamp(-n.x, -1.0, 1.0)); }
                    else if (dv.x > 0.0) { s = p.y - r; }
                    else { s = straight + 3.1416 * r + (size.y - r - p.y); }
                    float u = s / max(around, 0.001);
                    // The light is not all the way round: a few dark stretches, a fifth of the
                    // rim between them, drifting round it at their own paces.
                    float wave = 0.6 * sin(6.2832 * (2.0 * u - time * 0.045))
                        + 0.4 * sin(6.2832 * (3.0 * u + time * 0.028) + 1.7);
                    float lit = smoothstep(-0.62, -0.22, wave);
                    float pulse = 0.82 + 0.12 * sin(time * 0.9) + 0.06 * sin(time * 0.37 + 1.3);
                    float rimGlow = exp(-t * 4.0);
                    float3 add = (hot * rimGlow * 0.8 + hot * line * 0.6) * (0.05 + 0.95 * lit) * pulse
                        + c * (0.14 + 0.2 * (1.0 - t)) * pulse;
                    float al = clamp(body * 0.75 + max(add.r, max(add.g, add.b)), 0.0, 1.0);
                    float3 rgb = min(deep * body * 0.75 + add, float3(al));
                    return half4(half3(rgb * aa), half(al * aa));
                }

                float dl = seg(xy, lampA, lampB);
                float nearLamp = exp(-max(dl - lampR, 0.0) / (r * 1.8));
                float facing = 0.5 + 0.5 * dot(n, light);
                float lit = max(facing * facing * 0.6, nearLamp);
                float glow = exp(-edge / (r * 0.24));
                float3 add = hot * line * (0.12 + 0.88 * lit) + c * glow * 0.4 * lit;

                float len = slot.y - slot.x;
                if (len > 0.5) {
                    float rs = min(r * 0.6, len * 0.5);
                    float mid = (slot.x + slot.y) * 0.5;
                    float halfLen = max(len * 0.5 - rs, 0.0);
                    float sd = length(p - float2(r, clamp(p.y, mid - halfLen, mid + halfLen)));
                    float present = smoothstep(0.5, r * 0.6, len);
                    float inSlot = smoothstep(0.0, 1.2, rs - sd) * present;
                    float spill = exp(-max(sd - rs, 0.0) / (r * 0.9)) * smoothstep(0.0, r * 0.35, edge) * present;
                    add += c * spill * 0.4;
                    add = mix(add, mix(hot, c, 0.35 * sd / max(rs, 0.001)), inSlot * 0.92);
                }

                float al = clamp(body + max(add.r, max(add.g, add.b)), 0.0, 1.0);
                float3 rgb = min(deep * body + add, float3(al));
                return half4(half3(rgb * aa), half(al * aa));
            }
        """
        /** The lamp's light, added inside the hour pill's glass. */
        const val LAMP_ALPHA = 0.45f
        /** The lamp's halo round it: its blur, in the pill's width, and its strength. */
        const val HALO_BLUR = 0.7f
        const val HALO_ALPHA = 0.8f
        /**
         * The light a clear hand catches: the wash inside it, and its lit rim — each its
         * strength at the hub and how far along the hand it reaches, in the dial's radius; the
         * rim's width too.
         */
        const val CATCH_ALPHA = 0.32f
        const val CATCH_REACH = 0.6f
        const val CATCH_RIM_ALPHA = 0.9f
        const val CATCH_RIM_REACH = 0.85f
        const val CATCH_RIM_WIDTH = 0.02f
        /** Every hand's tail behind the centre, in the dial's radius (PillClock's hands all have it). */
        val TAIL = PillClock.MINUTE.tail
        /** How far in the web's edge is drawn at its thinnest, in its reach. */
        const val WEB_PULL_MIN = 0.15f
        /** Solid hands (ORIGINAL): where in the peel the second hand starts rising over the minute hand. */
        const val SOLID_OVER_FROM = 0.75f
        /**
         * The second hand's morph out of the minute hand, in its run: how far the two bodies run
         * together at the start (in the dial's radius), from where to where the second hand
         * proper comes up through the arm, and from where the arm thins away under it; and by
         * where in the run that running together is all there ([BUD_MELT_IN]).
         */
        const val BUD_MELT = 0.1f
        const val BUD_MELT_IN = 0.2f
        const val BOTTLE_FROM = 0.4f
        const val BOTTLE_BY = 0.8f
        const val BUD_GONE_FROM = 0.72f
        /** A solid hand's drop shadow (ORIGINAL): its colour, its blur and how far down it falls, in the dial's radius. */
        const val DROP_COLOR = 0x73000000
        const val DROP_BLUR = 0.035f
        const val DROP_DOWN = 0.02f
        /** How round the bottle's shoulder is, in the shoulder's length. */
        const val SHOULDER_EASE = 0.55f
    }
}
