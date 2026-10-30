package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import dev.kb896.aiusage.R

/**
 * The 896 Store's button, as the site has it: a glass capsule with the store's icon at its
 * left end on black — the black giving way to the glass along a 45° edge that starts a
 * button's height in at the bottom.
 */
class StoreButton(context: Context) : GlassButton(context) {

    private val density = context.resources.displayMetrics.density
    private val icon = context.getDrawable(R.drawable.store_896)!!
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadedFor = 0f

    init {
        // The text starts after the black the icon sits in.
        setPadding((64 * density).toInt(), 0, (18 * density).toInt(), 0)
    }

    override fun drawFace(canvas: Canvas, w: Float, h: Float, r: Float) {
        if (h != shadedFor) {
            shadedFor = h
            // Along the 45° line a point (x, y) is (x + y) / √2 in, so the bottom edge's
            // point a height in, (h, h), is h·√2: black to there, the glass 22dp on — the site's
            // calc(var(--h) * 1.4142) and 1.4rem.
            val black = h * SQRT2
            val end = black + 22 * density
            paint.shader = LinearGradient(
                0f, 0f, end / SQRT2, end / SQRT2,
                intArrayOf(BLACK, BLACK, 0), floatArrayOf(0f, black / end, 1f), Shader.TileMode.CLAMP,
            )
        }
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(0f, 0f, w, h, paint)
        // Centred in the black, not flush with the capsule's end, whose round would clip
        // the icon's corners: three quarters of the height, its middle 0.72h in — where,
        // halfway down, the black is widest — so even its corners stay inside the round.
        val size = h * 0.74f
        val left = h * 0.72f - size / 2
        val top = (h - size) / 2
        icon.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
        icon.draw(canvas)
        canvas.restore()
    }

    private companion object {
        const val SQRT2 = 1.4142135f
        const val BLACK = 0xFF000000.toInt()
    }
}
