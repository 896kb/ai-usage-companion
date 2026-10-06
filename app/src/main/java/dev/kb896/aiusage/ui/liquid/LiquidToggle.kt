// The 896 Store's LiquidToggle (dev.kb896.store/Liquid.kt), copied as it is — its springs,
// its page node, its highlight and the toggle itself — because the store's is the one that
// runs smoothly. Itself a rebuild, as Views, of AndroidLiquidGlass's catalog toggle:
//
//   Copyright 2025 Kyant — https://github.com/Kyant0/AndroidLiquidGlass
//   Licensed under the Apache License, Version 2.0.
//
// Changed only where the two apps differ: what the thumb bends is this app's silk background
// (SilkContent, from Backdrop) rather than the store's, it is redrawn with the silk's frames
// as a Glassy view rather than through the store's registry, the shaders are this app's own
// (rear/Glass, the same sources) and the accent is the app's (Ink.ACCENT). The helpers are
// file-private so they sit beside the app's own glass helpers without clashing.
package dev.kb896.aiusage.ui.liquid

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AnimationUtils
import android.widget.Checkable
import dev.kb896.aiusage.rear.Glass
import dev.kb896.aiusage.ui.glass.Backdrop
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

// ---- springs: the catalog's, integrated by hand (no Compose here) -----------------------

/** One damped spring for unit mass: Compose's spring(dampingRatio, stiffness). */
private class Spring(val stiffness: Float, val dampingRatio: Float, val threshold: Float, initial: Float) {
    var value = initial; private set
    var velocity = 0f; private set
    var target = initial

    val settled: Boolean get() = abs(value - target) < threshold && abs(velocity) < threshold * 10f

    fun snap(v: Float) { value = v; target = v; velocity = 0f }

    /** Advance by [dt] seconds; true while still moving. */
    fun step(dt: Float): Boolean {
        var remaining = min(dt, 1f / 30f)
        val damping = 2f * dampingRatio * sqrt(stiffness)
        while (remaining > 0f) {
            val h = min(remaining, 1f / 240f)
            val accel = -stiffness * (value - target) - damping * velocity
            velocity += accel * h
            value += velocity * h
            remaining -= h
        }
        if (settled) { value = target; velocity = 0f; return false }
        return true
    }
}

/** Steps a view's springs on its animation frames while any moves, and redraws it. */
private class Motion(private val view: View) {
    private val springs = ArrayList<Spring>()
    private var last = 0L
    private var running = false
    var onFrame: (() -> Unit)? = null

    fun spring(stiffness: Float, dampingRatio: Float, threshold: Float, initial: Float) =
        Spring(stiffness, dampingRatio, threshold, initial).also { springs += it }

    fun animate(spring: Spring, to: Float) { spring.target = to; start() }

    fun start() {
        if (running) return
        running = true
        last = 0L
        view.postOnAnimation(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = AnimationUtils.currentAnimationTimeMillis()
            val dt = if (last == 0L) 1f / 60f else (now - last) / 1000f
            last = now
            var moving = false
            for (s in springs) if (s.step(dt)) moving = true
            onFrame?.invoke()
            if (!moving) moving = springs.any { !it.settled }
            view.invalidate()
            if (moving) view.postOnAnimation(this) else running = false
        }
    }
}

/** Below this a press has let go (the press springs' visibility threshold). */
private const val REST_PRESS = 0.001f

/**
 * InteractiveHighlight: press progress spring(0.5, 300), position spring(0.5, 300); the
 * offset is how far the finger has moved since it came down.
 */
private class PressSpring(view: View) {
    private val motion = Motion(view)
    private val progressSpring = motion.spring(300f, 0.5f, 0.001f, 0f)
    private val xSpring = motion.spring(300f, 0.5f, 0.5f, 0f)
    private val ySpring = motion.spring(300f, 0.5f, 0.5f, 0f)
    private var startX = 0f
    private var startY = 0f

    val progress get() = progressSpring.value
    val x get() = xSpring.value
    val y get() = ySpring.value
    val offsetX get() = xSpring.value - startX
    val offsetY get() = ySpring.value - startY

    fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x; startY = e.y
                xSpring.snap(e.x); ySpring.snap(e.y)
                motion.animate(progressSpring, 1f)
            }
            MotionEvent.ACTION_MOVE -> { xSpring.snap(e.x); ySpring.snap(e.y); motion.start() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                motion.animate(progressSpring, 0f)
                motion.animate(xSpring, startX)
                motion.animate(ySpring, startY)
            }
        }
    }
}

/**
 * DampedDragAnimation: value (1, 1000), velocity (0.5, 300), press (1, 1000), scaleX (0.6, 250),
 * scaleY (0.7, 250). The velocity is the value spring's own, normalised to the range.
 */
private class DampedDrag(view: View, initial: Float, private val pressedScale: Float) {
    private val motion = Motion(view)
    private val valueSpring = motion.spring(1000f, 1f, 0.001f, initial)
    private val velocitySpring = motion.spring(300f, 0.5f, 0.01f, 0f)
    private val pressSpring = motion.spring(1000f, 1f, 0.001f, 0f)
    private val scaleXSpring = motion.spring(250f, 0.6f, 0.001f, 1f)
    private val scaleYSpring = motion.spring(250f, 0.7f, 0.001f, 1f)
    private var pendingRelease = false
    private var followsVelocity = true

    init {
        motion.onFrame = {
            if (followsVelocity) velocitySpring.target = valueSpring.velocity
            if (pendingRelease && valueSpring.settled) { pendingRelease = false; release() }
        }
    }

    val value get() = valueSpring.value
    val target get() = valueSpring.target
    val pressProgress get() = pressSpring.value
    val scaleX get() = scaleXSpring.value
    val scaleY get() = scaleYSpring.value
    val velocity get() = velocitySpring.value

    fun press() {
        motion.animate(pressSpring, 1f)
        motion.animate(scaleXSpring, pressedScale)
        motion.animate(scaleYSpring, pressedScale)
    }

    fun release() {
        motion.animate(pressSpring, 0f)
        motion.animate(scaleXSpring, 1f)
        motion.animate(scaleYSpring, 1f)
    }

    fun drag(v: Float) {
        followsVelocity = true
        motion.animate(valueSpring, v.coerceIn(0f, 1f))
    }

    /** A tap's move: press, travel, then let go once it has arrived. */
    fun animateTo(v: Float) {
        press()
        followsVelocity = false
        motion.animate(velocitySpring, 0f)
        motion.animate(valueSpring, v.coerceIn(0f, 1f))
        pendingRelease = true
    }

    fun snap(v: Float) = valueSpring.snap(v.coerceIn(0f, 1f))
}

// ---- the library's drawing, shared ------------------------------------------------------

private object Liquid {
    /** vibrancy(): colorControls(saturation = 1.5), the library's matrix. */
    val VIBRANCY: ColorMatrixColorFilter = run {
        val s = 1.5f
        val inv = 1f - s
        val r = 0.213f * inv
        val g = 0.715f * inv
        val b = 0.072f * inv
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    r + s, g, b, 0f, 0f,
                    r, g + s, b, 0f, 0f,
                    r, g, b + s, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }

    /** InteractiveHighlight's glow: a soft disc under the finger. */
    const val GLOW = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""

    /** The highlight's two looks: Default (lit, white 0.5, Plus) and Ambient (white 0.38). */
    enum class Rim { DEFAULT, AMBIENT }
}

/**
 * Let a control draw past its own box: a pressed button swells and follows the finger, and a
 * layout clips each child to its bounds by default, which cut the swell off in straight
 * lines.
 *
 * NOT PAST A SCROLLER, and not past what holds one. A ScrollView is kept inside its own box by
 * its PARENT's clipping, so unclipping the layout that holds the header and the list let the
 * list draw over the header as it scrolled up. The walk stops at a scroller, and at any layout
 * with one among its children; the levels below have the padding a swell needs.
 */
private fun unclipParents(view: View) {
    var parent = view.parent
    while (parent is android.view.ViewGroup && parent !is android.widget.ScrollView) {
        val holdsScroller = (0 until parent.childCount).any { parent.getChildAt(it) is android.widget.ScrollView }
        if (holdsScroller) return
        parent.clipChildren = false
        parent = parent.parent
    }
}

/** The page under a control, recorded into a node each frame, with the effects on the node. */
@TargetApi(33)
private class PageNode(private val view: View) {
    val node = RenderNode("liquid")
    private val content = SilkContent()
    private val paint = Paint()

    /** Record the page under [w]x[h] at the view's ([left], [top]); [extra] draws more over it. */
    fun record(left: Float, top: Float, w: Float, h: Float, extra: ((Canvas) -> Unit)? = null): RenderNode {
        node.setPosition(0, 0, ceil(w).toInt(), ceil(h).toInt())
        val c = node.beginRecording()
        try {
            paint.shader = content.at(view, left, top)
            c.drawRect(0f, 0f, w, h, paint)
            extra?.invoke(c)
        } finally {
            node.endRecording()
        }
        return node
    }
}

/** Draws the library's highlight round a capsule, as HighlightModifier does. */
private class Highlighter(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val layer = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val lit = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(Glass.HIGHLIGHT) else null
    private val ambient = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(Glass.AMBIENT) else null

    /**
     * [widthDp] and [blurDp] as Highlight has them (0.5 dp, blur half that); [alpha] the
     * layer's. The stroke is twice the width, centred on the outline and clipped to its
     * inside, blurred; the layer blends Plus for the lit rim, normally for the ambient one.
     */
    fun draw(canvas: Canvas, rect: RectF, radius: Float, rim: Liquid.Rim, widthDp: Float, blurDp: Float, alpha: Float) {
        if (alpha <= 0f || widthDp <= 0f) return
        val w = rect.width()
        val h = rect.height()
        val widthPx = widthDp * density
        stroke.strokeWidth = ceil(widthPx.coerceAtMost(min(w, h) / 2f)) * 2f
        stroke.maskFilter = if (blurDp > 0f) BlurMaskFilter(blurDp * density, BlurMaskFilter.Blur.NORMAL) else null
        stroke.color = if (rim == Liquid.Rim.DEFAULT) 0x80FFFFFF.toInt() else 0x61FFFFFF
        if (Build.VERSION.SDK_INT >= 33) shade(rim, w, h, radius) else stroke.shader = null
        layer.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        layer.blendMode = if (rim == Liquid.Rim.DEFAULT) BlendMode.PLUS else null
        canvas.saveLayer(rect.left - 1f, rect.top - 1f, rect.right + 1f, rect.bottom + 1f, layer)
        clip.rewind()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.save()
        canvas.translate(rect.left, rect.top)
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, stroke)
        canvas.restore()
        canvas.restore()
    }

    @TargetApi(33)
    private fun shade(rim: Liquid.Rim, w: Float, h: Float, radius: Float) {
        val shader = (if (rim == Liquid.Rim.DEFAULT) lit else ambient) ?: return
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
        if (rim == Liquid.Rim.DEFAULT) shader.setColorUniform("color", 0xFFFFFFFF.toInt())
        shader.setFloatUniform("angle", Glass.LIGHT_ANGLE)
        shader.setFloatUniform("falloff", 1f)
        stroke.shader = shader
    }
}

/** The app's silk background, moved so that [view]'s point ([left], [top]) samples what the
 *  screen shows at the same place — what the store's BendsContent is for its own background. */
private class SilkContent {
    private val matrix = Matrix()
    private val at = IntArray(2)

    fun at(view: View, left: Float, top: Float): Shader? {
        val shader = Backdrop.shader ?: return null
        view.getLocationOnScreen(at)
        matrix.setTranslate(-(at[0] + left - Backdrop.origin[0]), -(at[1] + top - Backdrop.origin[1]))
        shader.setLocalMatrix(matrix)
        return shader
    }
}

// ---- LiquidToggle ------------------------------------------------------------------------

/**
 * catalog/components/LiquidToggle.kt: a 64x28 dp capsule track, from the track colour to the
 * accent as it turns on; a 40x24 dp thumb, inset 2 dp, travelling 20 dp. At rest the thumb is
 * white; pressed, the white goes and it becomes glass: the page and the track under it
 * (the track squeezed, as the catalog's nested backdrop draws it), blurred 8 dp at rest and
 * not at all held, through a lens of 5 dp / 10 dp with dispersion; an inner shadow and the
 * ambient highlight come in with the press; a 4 dp shadow under it. It squashes with its
 * speed and swells 1.5x while held. Drag it, or tap.
 */
class LiquidToggle(context: Context) : View(context), Checkable, dev.kb896.aiusage.ui.glass.Glassy {
    var onCheckedChange: ((Boolean) -> Unit)? = null
    private var checked = false
    private val density = context.resources.displayMetrics.density
    private val drag = DampedDrag(this, initial = 0f, pressedScale = 1.5f)
    private val page = if (Build.VERSION.SDK_INT >= 33) PageNode(this) else null
    private val rim = Highlighter(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val thumb = RectF()
    private val local = RectF()
    private val clip = Path()
    private val inner = Path()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    private val effects = HashMap<Int, RenderEffect>()

    init { isClickable = true }

    override fun isChecked() = checked
    override fun toggle() { isChecked = !checked }
    override fun setChecked(b: Boolean) {
        if (b == checked) return
        checked = b
        drag.animateTo(if (b) 1f else 0f)
        onCheckedChange?.invoke(b)
    }

    /** Set without animating or reporting (the screen showing a stored value). */
    fun setCheckedSilently(b: Boolean) { checked = b; drag.snap(if (b) 1f else 0f); invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        unclipParents(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(((64 + 2 * PAD_X) * density).toInt(), ((28 + 2 * PAD_Y) * density).toInt())

    override fun performClick(): Boolean { toggle(); super.performClick(); return true }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val travel = 20 * density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragged = false; drag.press(); return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (!dragged) {
                    if (abs(dx) > slop && abs(dx) > abs(e.y - downY)) {
                        dragged = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else return true
                }
                drag.drag((if (checked) 1f else 0f) + dx / travel)
                return true
            }
            MotionEvent.ACTION_UP -> {
                drag.release()
                if (!dragged) { performClick(); return true }
                val on = drag.target >= 0.5f
                if (on != checked) { checked = on; onCheckedChange?.invoke(on) }
                drag.animateTo(if (on) 1f else 0f)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                drag.release()
                drag.animateTo(if (checked) 1f else 0f)
                dragged = false
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val px = PAD_X * density
        val py = PAD_Y * density
        track.set(px, py, w - px, h - py)
        val tr = track.height() / 2f
        val fraction = drag.value
        val trackColor = mix(TRACK, ACCENT, fraction)
        paint.shader = null
        paint.color = trackColor
        canvas.drawRoundRect(track, tr, tr, paint)

        val inset = 2 * density
        val tw = 40 * density
        val th = 24 * density
        val x = track.left + inset + 20 * density * fraction
        thumb.set(x, track.top + inset, x + tw, track.top + inset + th)
        val r = th / 2f
        val p = drag.pressProgress

        // layerBlock: the drag's scale, squashed along the travel by the speed.
        val v = drag.velocity / 50f
        var sx = drag.scaleX / (1f - (v * 0.75f).coerceIn(-0.2f, 0.2f))
        var sy = drag.scaleY * (1f - (v * 0.25f).coerceIn(-0.2f, 0.2f))
        sx = min(sx, cap(thumb.centerX(), w, tw))
        sy = min(sy, cap(thumb.centerY(), h, th))
        canvas.save()
        canvas.scale(sx, sy, thumb.centerX(), thumb.centerY())

        // Shadow: 4 dp, offset a sixth of that down, black at 0.05.
        paint.color = 0x0D000000
        paint.maskFilter = BlurMaskFilter(4 * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(thumb.left, thumb.top + 4 * density / 6f, thumb.right, thumb.bottom + 4 * density / 6f, r, r, paint)
        paint.maskFilter = null

        clip.rewind()
        clip.addRoundRect(thumb, r, r, Path.Direction.CW)
        if (p > REST_PRESS && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated) {
            glass(canvas, tw, th, r, p, trackColor)
        }

        // Inner shadow: 4 dp × press, offset by its radius, black 0.15, at the press's alpha.
        if (p > REST_PRESS) {
            val radius = 4 * density * p
            canvas.save()
            canvas.clipPath(clip)
            inner.rewind()
            inner.fillType = Path.FillType.EVEN_ODD
            inner.addRect(thumb.left - th, thumb.top - th, thumb.right + th, thumb.bottom + th, Path.Direction.CW)
            inner.addRoundRect(thumb.left, thumb.top + radius, thumb.right, thumb.bottom + radius, r, r, Path.Direction.CW)
            paint.color = ((0.15f * p * 255).toInt().coerceIn(0, 255) shl 24)
            if (radius > 0.5f) paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(inner, paint)
            paint.maskFilter = null
            canvas.restore()
        }

        // onDrawSurface: white, gone as it is pressed.
        paint.color = (((1f - p) * 255).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        canvas.drawRoundRect(thumb, r, r, paint)

        // Highlight.Ambient at a third less width and blur, at the press's alpha.
        rim.draw(canvas, thumb, r, Liquid.Rim.AMBIENT, 0.5f / 1.5f, 0.25f / 1.5f, p)
        canvas.restore()
    }

    /** The pressed thumb's glass: the page and the squeezed track, blurred, then lensed. */
    @TargetApi(33)
    private fun glass(canvas: Canvas, tw: Float, th: Float, r: Float, p: Float, trackColor: Int) {
        val node = page?.record(thumb.left, thumb.top, tw, th) { c ->
            // The track as the thumb sees it (rememberBackdrop(trackBackdrop) { scale(...) }),
            // scaled about the thumb's centre: 2/3→3/4 wide, 0→3/4 tall as it is pressed.
            val scaleX = 2f / 3f + (0.75f - 2f / 3f) * p
            val scaleY = 0.75f * p
            c.save()
            c.scale(scaleX, scaleY, tw / 2f, th / 2f)
            local.set(track.left - thumb.left, track.top - thumb.top, track.right - thumb.left, track.bottom - thumb.top)
            paint.color = trackColor
            c.drawRoundRect(local, track.height() / 2f, track.height() / 2f, paint)
            c.restore()
        } ?: return
        node.setRenderEffect(effectFor(tw, th, r, p))
        canvas.save()
        canvas.clipPath(clip)
        canvas.translate(thumb.left, thumb.top)
        canvas.drawRenderNode(node)
        canvas.restore()
    }

    /** blur(8 dp × (1 − press)) then lens(5 dp × press, 10 dp × press, dispersion), kept per step of press. */
    @TargetApi(33)
    private fun effectFor(w: Float, h: Float, r: Float, p: Float): RenderEffect {
        val step = (p * 32f).toInt().coerceIn(0, 32)
        effects[step]?.let { return it }
        val q = step / 32f
        val blurRadius = 8 * density * (1f - q)
        val lensShader = RuntimeShader(Glass.DISPERSION).apply {
            setFloatUniform("size", w, h)
            setFloatUniform("offset", 0f, 0f)
            setFloatUniform("cornerRadii", r, r, r, r)
            setFloatUniform("refractionHeight", (5 * density * q).coerceAtLeast(0.01f))
            setFloatUniform("refractionAmount", -10 * density * q)
            setFloatUniform("depthEffect", 0f)
            setFloatUniform("chromaticAberration", 1f)
        }
        val lens = RenderEffect.createRuntimeShaderEffect(lensShader, "content")
        val effect = if (blurRadius > 0.01f) {
            RenderEffect.createChainEffect(lens, RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
        } else lens
        effects[step] = effect
        return effect
    }

    private fun cap(centre: Float, extent: Float, size: Float) = min(centre, extent - centre) / (size / 2f)

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = Math.round(((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t.coerceIn(0f, 1f))
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        /** Room around the track for the pressed (1.5x) thumb. */
        const val PAD_X = 12f
        const val PAD_Y = 8f
        /** The catalog's dark-theme track colour, 0x787880 at 0.36; the accent is the app's (Ink.ACCENT). */
        const val TRACK = 0x5C787880
        val ACCENT = dev.kb896.aiusage.ui.glass.Ink.ACCENT
    }
}
