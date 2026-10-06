package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import dev.kb896.aiusage.R

/**
 * Something is being looked for: a ring of glass, as round as a colour's chip, with the
 * Usage tab's donut — the one with a bite out of it — turning inside in the accent.
 * Moves for as long as it is on screen, every frame, and stops when it leaves.
 */
class GlassSpinner(context: Context) : View(context), Glassy {
    private val density = resources.displayMetrics.density
    private val donut = context.getDrawable(R.drawable.ic_tab_usage)!!.mutate().apply { setTint(Ink.accent(0f)) }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheenFor = -1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(((SIZE_DP + 2 * MARGIN_DP) * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val r = SIZE_DP * density / 2f
        val cx = width / 2f; val cy = height / 2f
        val l = cx - r; val t = cy - r; val rr = cx + r; val b = cy + r
        // The glass, as GlassChip's: the body refracting what is behind, a sheen, the rim.
        Pane.draw(canvas, this, l, t, rr, b, r, tint = 0, clear = true, rim = false)
        if (sheenFor != r) {
            sheenFor = r
            sheen.shader = android.graphics.RadialGradient(
                cx - r * 0.35f, cy - r * 0.45f, r * 1.1f,
                intArrayOf(0x55FFFFFF, 0x14FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, r, sheen)
        // The donut, turning once a second, inside the glass.
        val d = (r * DONUT).toInt()
        val turn = (SystemClock.uptimeMillis() % TURN_MS) * 360f / TURN_MS
        canvas.save()
        canvas.rotate(turn, cx, cy)
        donut.setBounds((cx - d).toInt(), (cy - d).toInt(), (cx + d).toInt(), (cy + d).toInt())
        donut.draw(canvas)
        canvas.restore()
        Pane.draw(canvas, this, l, t, rr, b, r, tint = 0, clear = false, rim = true, lens = false)
        if (isAttachedToWindow && isShown) postInvalidateOnAnimation()
    }

    private companion object {
        const val SIZE_DP = 56f
        const val MARGIN_DP = 12f
        /** The donut's half-size, as a share of the glass's radius. */
        const val DONUT = 0.72f
        const val TURN_MS = 1000L
    }
}
