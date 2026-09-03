package dev.kb896.aiusage.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.BlendMode
import android.view.View
import dev.kb896.aiusage.rear.Glass
import dev.kb896.aiusage.rear.GlassLuminance
import kotlin.math.min

/**
 * One rounded rectangle, drawn as glass over the wallpaper.
 *
 * The panel does this with RenderNodes and RenderEffects because its backdrop is live.
 * The front's backdrop is a bitmap that does not change between frames, so the lens can
 * be a Paint shader: the backdrop as a BitmapShader translated to the view's place on
 * screen, fed to the vendored refraction shader as its content. Then the tint, then the
 * rim. Uniforms are set on the shared shader per draw and Android re-syncs them into the
 * paint at draw time, which the panel's mist already depends on.
 */
object Pane {
    /** What a pane reads as over no backdrop at all: the panel colour, which is dark. */
    const val FALLBACK_LUMINANCE = 0.12f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    /** The part of a pane the lens does nothing to. Reused: this is on the scroll path. */
    private val inner = RectF()
    private val matrix = Matrix()
    private val loc = IntArray(2)

    /**
     * One disc, made once and moved. A RadialGradient is defined at a centre and a radius,
     * so a press on a control of a different size used to mean a new one every frame; a
     * unit disc at the origin plus a local matrix is the same picture with no allocation,
     * and the fade comes off the paint's alpha rather than being baked into the colours.
     */
    private val disc = RadialGradient(0f, 0f, 1f,
        intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)

    fun draw(
        canvas: Canvas, view: View,
        left: Float, top: Float, right: Float, bottom: Float,
        radius: Float, tint: Int,
        clear: Boolean = false, rim: Boolean = true, content: Shader? = null,
        lens: Boolean = true, interior: Boolean = true,
    ): Float {
        val w = right - left; val h = bottom - top
        if (w <= 0f || h <= 0f) return FALLBACK_LUMINANCE
        val density = view.resources.displayMetrics.density
        val backdrop = Backdrop.bitmap
        view.getLocationOnScreen(loc)
        val sx = loc[0] - Backdrop.origin[0] + left
        val sy = loc[1] - Backdrop.origin[1] + top
        val lum = if (backdrop == null) FALLBACK_LUMINANCE
            else Backdrop.luminance(sx, sy, sx + w, sy + h)

        canvas.save()
        canvas.translate(left, top)
        rect.set(0f, 0f, w, h)

        // A caller that draws its own colour over the pane and then comes back for the
        // rim asks for [lens] = false: running the refraction a second time repaints the
        // backdrop over the colour that was the whole point of the first pass.
        val source = content ?: Backdrop.shader
        if (lens && backdrop != null && source != null) {
            // A shader carries its own coordinates and the canvas is in PANE space, so
            // both kinds need moving: the backdrop's are the root's, and a supplied one's
            // are the VIEW's — the difference is the pane's origin. Setting a local matrix
            // is a dirty flag rather than an allocation; Paint.getNativeInstance() re-syncs
            // it when the draw is recorded, the same way these uniforms already are.
            if (content != null) matrix.setTranslate(-left, -top) else matrix.setTranslate(-sx, -sy)
            source.setLocalMatrix(matrix)
            val short = min(w, h)
            val refraction = Glass.refraction
            refraction.setInputShader("content", source)
            refraction.setFloatUniform("size", w, h)
            refraction.setFloatUniform("offset", 0f, 0f)
            refraction.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            refraction.setFloatUniform("refractionHeight", min(short * Glass.HEIGHT, Glass.HEIGHT_MAX * density))
            refraction.setFloatUniform("refractionAmount", -min(short * Glass.AMOUNT, Glass.AMOUNT_MAX * density))
            refraction.setFloatUniform("depthEffect", Glass.DEPTH)
            paint.shader = refraction; paint.style = Paint.Style.FILL; paint.alpha = 255
            // [interior] = false spends the shader only where it bends anything. Away
            // from the edge band the refraction samples straight through, so the fill
            // reproduces the wallpaper — which the root's BackdropDrawable has already
            // painted behind the card. Clipping the middle out of the lens pass drops a
            // full-area shader fill and changes no pixel. The tint and the rim, which do
            // cover the whole pane, are left alone.
            val clipped = !interior && content == null && clipsInterior(w, h, density)
            if (clipped) {
                val inset = interiorInset(short, density)
                inner.set(inset, inset, w - inset, h - inset)
                canvas.save()
                canvas.clipOutRect(inner)
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (clipped) canvas.restore()
            paint.shader = null
        }

        val body = if (clear) GlassLuminance.clearTint(lum) else GlassLuminance.reactive(tint, lum)
        if (body ushr 24 != 0) {
            paint.style = Paint.Style.FILL; paint.color = body
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        if (rim && backdrop != null) {
            val edge = Glass.highlight
            edge.setFloatUniform("size", w, h)
            edge.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            edge.setColorUniform("color", Glass.LIGHT_COLOR)
            edge.setFloatUniform("angle", Glass.LIGHT_ANGLE)
            edge.setFloatUniform("falloff", Glass.LIGHT_FALLOFF)
            paint.shader = edge; paint.style = Paint.Style.STROKE
            paint.strokeWidth = Glass.RIM_WIDTH; paint.alpha = 255
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null; paint.style = Paint.Style.FILL
        }
        canvas.restore()
        return lum
    }

    /**
     * How far in from a pane's edge the lens still bends the backdrop: the refraction
     * height the shader is actually given, plus 2dp so the clip never eats into the band.
     */
    fun interiorInset(short: Float, density: Float): Float =
        (min(short * Glass.HEIGHT, Glass.HEIGHT_MAX * density) + 2 * density).coerceAtLeast(0f)

    /** Whether a pane of this size has an interior left to clip out. A small one has not. */
    fun clipsInterior(w: Float, h: Float, density: Float): Boolean {
        val inset = interiorInset(min(w, h), density)
        return w - 2 * inset > 0f && h - 2 * inset > 0f
    }

    /**
     * The press highlight: a flat lift plus a soft disc under the finger, added (PLUS)
     * over the pane. InteractiveHighlight.kt draws 0.08 white flat and 0.15 white in a
     * disc of radius 1.5 x the short side, fading from half that radius.
     */
    fun glow(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
             radius: Float, progress: Float, x: Float, y: Float) {
        val w = right - left; val h = bottom - top
        // A RadialGradient with no radius throws, and a pane mid-collapse or before its
        // first layout has none — the same case draw already declines to draw.
        if (progress <= 0f || w <= 0f || h <= 0f) return
        rect.set(left, top, right, bottom)
        paint.blendMode = BlendMode.PLUS
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.color = Color.argb((0.08f * progress * 255).toInt(), 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, paint)
        val r = min(w, h) * 1.5f
        matrix.setTranslate(x.coerceIn(left, right), y.coerceIn(top, bottom))
        matrix.preScale(r, r)
        disc.setLocalMatrix(matrix)
        paint.shader = disc
        paint.alpha = (0.15f * progress * 255).toInt()
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null; paint.blendMode = null
    }
}
