package dev.kb896.aiusage.ui.glass

import android.graphics.BitmapShader
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
    private val matrix = Matrix()
    private val loc = IntArray(2)

    fun draw(
        canvas: Canvas, view: View,
        left: Float, top: Float, right: Float, bottom: Float,
        radius: Float, tint: Int,
        clear: Boolean = false, rim: Boolean = true, content: Shader? = null,
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

        if (backdrop != null) {
            val source = content ?: BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                matrix.setTranslate(-sx, -sy)
                it.setLocalMatrix(matrix)
            }
            val short = min(w, h)
            val lens = Glass.refraction
            lens.setInputShader("content", source)
            lens.setFloatUniform("size", w, h)
            lens.setFloatUniform("offset", 0f, 0f)
            lens.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            lens.setFloatUniform("refractionHeight", min(short * Glass.HEIGHT, Glass.HEIGHT_MAX * density))
            lens.setFloatUniform("refractionAmount", -min(short * Glass.AMOUNT, Glass.AMOUNT_MAX * density))
            lens.setFloatUniform("depthEffect", Glass.DEPTH)
            paint.shader = lens; paint.style = Paint.Style.FILL; paint.alpha = 255
            canvas.drawRoundRect(rect, radius, radius, paint)
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
        val c = Color.argb((0.15f * progress * 255).toInt(), 255, 255, 255)
        paint.shader = RadialGradient(x.coerceIn(left, right), y.coerceIn(top, bottom), r,
            intArrayOf(c, c, 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null; paint.blendMode = null
    }
}
