package dev.kb896.aiusage.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.kb896.aiusage.R
import dev.kb896.aiusage.ui.UiType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The settings' bottom bar: the panel's three pages and always-on, as tabs.
 *
 * The liquid-glass tab bar of the cv-new phone app (lib/ui/spec_shell.dart, itself a
 * port of the catalog's LiquidBottomTabs, Apache 2.0), brought over 1:1 in this app's
 * glass: a capsule of glass with a dark 40% surface; a pill of glass over the chosen tab
 * that springs between tabs and can be dragged; pressed, the pill grows to 78/56 — past
 * the bar — shows the tabs through itself in the accent, takes a specular rim at 45° and
 * a shadow, a soft light follows it across the bar, and a fling squashes it along its
 * speed; the whole bar leans up to 4 units with a drag and springs back. The one knob,
 * [NS], scales the assembly as the original's does; 0.8 rather than its 0.7, for four
 * tabs where it has three.
 *
 * The glass refracts the wallpaper, as every pane here does, rather than blurring the
 * rows scrolling under it.
 */
class GlassTabBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs), Glassy {

    data class Tab(val label: String, val icon: Int)

    var tabs: List<Tab> = listOf(
        Tab("Clock", R.drawable.ic_tab_clock),
        Tab("Usage", R.drawable.ic_tab_usage),
        Tab("Media", R.drawable.ic_tab_media),
        Tab("Always-on", R.drawable.ic_tab_aod),
    )
        set(value) { field = value; loadIcons(); invalidate() }

    var selected: Int = 1; private set
    var onSelect: ((Int) -> Unit)? = null

    private val d = context.resources.displayMetrics.density
    private val drag = DampedDrag(this, initial = selected.toFloat(),
        range = 0f..3f, threshold = 0.001f, initialScale = 1f, pressedScale = 78f / 56f)
    private val panel = drag.motion.spring(300f, 1f, 0.5f, 0f)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = UiType.of(context)
    }
    private val labelBold = Paint(label).apply { fontVariationSettings = "'wght' 700" }
    private val labelPlain = Paint(label).apply { fontVariationSettings = "'wght' 500" }
    private val bar = RectF()
    /** The light that follows the pill: one unit gradient, placed by its matrix — no allocation per frame. */
    private val glow = RadialGradient(0f, 0f, 1f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val glowMatrix = android.graphics.Matrix()
    private val pill = RectF()
    private var icons: List<Drawable> = emptyList()
    private var rimFor = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var tracking = false

    init { loadIcons() }

    private fun loadIcons() {
        icons = tabs.map { context.getDrawable(it.icon)!!.mutate() }
    }

    /** Selects [index] — from code, or a tap or a drag — and reports it if it changed. */
    fun select(index: Int, animate: Boolean = true) {
        val i = index.coerceIn(0, tabs.size - 1)
        if (animate) drag.animateTo(i.toFloat()) else drag.snap(i.toFloat())
        if (i == selected) { invalidate(); return }
        selected = i
        invalidate()
        onSelect?.invoke(i)
    }

    // ---- geometry, in pixels -------------------------------------------------------

    private val barH get() = 64 * NS * d
    private val inner get() = 4 * NS * d
    private val bottomGap get() = 10 * d
    private val topRoom get() = 14 * d
    private val barW get() = width * WIDTH_SHARE
    private val tabW get() = (barW - 2 * inner) / tabs.size

    /** How far the bar leans with a drag: up to 4 units, eased, spring-returned. */
    private fun lean(): Float {
        val f = (panel.value / barW).coerceIn(-1f, 1f)
        val eased = 1f - (1f - abs(f)) * (1f - abs(f)) // easeOut
        return 4 * NS * d * sign(f) * eased
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (topRoom + barH + bottomGap).roundToInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    /** The scroll above runs under the bar; it needs this much room at its end. */
    val reach: Int get() = (barH + bottomGap + 8 * d).roundToInt()

    // ---- touch ---------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the capsule takes touches: around it, the rows underneath do.
                if (!bar.contains(e.x, e.y)) return false
                tracking = true
                dragging = false
                downX = e.x; downY = e.y; lastX = e.x
                drag.press()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (!dragging) {
                    if (abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else return true
                }
                val dx = e.x - lastX
                lastX = e.x
                drag.updateValue(drag.targetValue + dx / tabW)
                panel.snap(panel.value + dx)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                drag.release()
                drag.motion.animate(panel, 0f)
                if (dragging) select(drag.targetValue.roundToInt())
                else { select(((e.x - bar.left - inner) / tabW).toInt()); performClick() }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                dragging = false
                drag.release()
                drag.motion.animate(panel, 0f)
                drag.animateTo(selected.toFloat())
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    // ---- drawing -------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val off = lean()
        val left = (width - barW) / 2f + off
        val top = topRoom
        bar.set(left, top, left + barW, top + barH)
        val r = barH / 2f
        val lum = if (Backdrop.bitmap == null) Pane.FALLBACK_LUMINANCE else Backdrop.luminanceOf(this)
        val accent = Ink.accent(lum)
        val muted = Ink.secondary(lum)

        val p = drag.pressProgress.coerceIn(0f, 1f)
        val value = drag.value
        // The squash reads only live speed — the finger's, or the spring's as it settles.
        val v = drag.velocity * (tabs.size - 1) / 10f
        val sx = drag.scaleX / (1f - (v * 0.75f).coerceIn(-0.2f, 0.2f))
        val sy = drag.scaleY * (1f - (v * 0.25f).coerceIn(-0.2f, 0.2f))
        val thumbLeft = (value * tabW).coerceIn(0f, barW - 2 * inner - tabW)
        val pillCx = bar.left + inner + thumbLeft + tabW / 2f
        val pillCy = bar.top + inner + 28 * NS * d

        // The bar: glass with its dark 40% surface.
        Pane.draw(canvas, this, bar.left, bar.top, bar.right, bar.bottom, r, tint = SURFACE)

        // The tabs; under the pressed pill each fades by how much of it the pill covers,
        // so the accent copy inside the pill is the only one seen there.
        val ink = if (dragging) value.roundToInt().coerceIn(0, tabs.size - 1) else selected
        for (i in tabs.indices) {
            val cover = (1f - abs(i - value)).coerceIn(0f, 1f)
            drawTab(canvas, i, if (i == ink) accent else muted, (1f - p * cover), ink == i)
        }

        // A soft light following the pill across the bar, while pressed.
        if (p > 0f) {
            glowMatrix.setScale(barH * 0.6f, barH * 0.6f)
            glowMatrix.postTranslate(pillCx, bar.centerY())
            glow.setLocalMatrix(glowMatrix)
            fill.shader = glow
            fill.color = Color.WHITE
            fill.alpha = (255 * 0.12f * p).toInt()
            canvas.drawRoundRect(bar, r, r, fill)
            fill.shader = null
            fill.alpha = 255
        }

        // The pill: outside the bar's clip, so its pressed size grows past the bar.
        val pw = tabW; val ph = 56 * NS * d; val pr = ph / 2f
        pill.set(pillCx - pw / 2f, pillCy - ph / 2f, pillCx + pw / 2f, pillCy + ph / 2f)
        canvas.save()
        canvas.scale(sx, sy, pillCx, pillCy)
        if (p > 0f) {
            shadow.color = withAlpha(Color.BLACK, 0.20f * p)
            shadow.setShadowLayer(12 * NS * d, 0f, 4 * NS * d, withAlpha(Color.BLACK, 0.20f * p))
            canvas.drawRoundRect(pill, pr, pr, shadow)
            shadow.clearShadowLayer()
            Pane.draw(canvas, this, pill.left, pill.top, pill.right, pill.bottom, pr,
                tint = 0, clear = true, rim = false)
        }
        fill.color = blend(withAlpha(Color.WHITE, 0.10f), withAlpha(Color.WHITE, 0.14f), p)
        canvas.drawRoundRect(pill, pr, pr, fill)
        // The tabs again, in the accent, seen through the pressed pill.
        if (p > 0f) {
            canvas.save()
            canvas.clipRect(pill)
            for (i in tabs.indices) drawTab(canvas, i, accent, p, i == ink)
            canvas.restore()
            drawRim(canvas, p, pr)
        }
        canvas.restore()
    }

    private fun drawTab(canvas: Canvas, i: Int, colour: Int, alpha: Float, bold: Boolean) {
        if (alpha <= 0.001f) return
        val cx = bar.left + inner + tabW * (i + 0.5f)
        val iconSize = 24 * NS * d
        val textSize = LABEL_SP * NS * resources.displayMetrics.scaledDensity
        val block = iconSize + 1 * d + textSize
        val top = bar.top + inner + (56 * NS * d - block) / 2f
        icons.getOrNull(i)?.let { icon ->
            icon.setBounds((cx - iconSize / 2f).toInt(), top.toInt(), (cx + iconSize / 2f).toInt(), (top + iconSize).toInt())
            icon.setTint(colour)
            icon.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            icon.draw(canvas)
        }
        val paint = if (bold) labelBold else labelPlain
        paint.textSize = textSize
        paint.color = colour
        paint.alpha = (Color.alpha(colour) * alpha).toInt().coerceIn(0, 255)
        canvas.drawText(tabs[i].label, cx, top + iconSize + 1 * d - paint.ascent(), paint)
    }

    /** The specular rim: white, strongest at 45° and 225°, added on. */
    private fun drawRim(canvas: Canvas, p: Float, radius: Float) {
        if (rimFor != pill.width() + pill.height()) {
            rimFor = pill.width() + pill.height()
            val colours = IntArray(33) { i ->
                val theta = i / 32.0 * 2 * Math.PI
                withAlpha(Color.WHITE, (0.5 * abs(cos(theta - Math.PI / 4))).toFloat())
            }
            rim.shader = SweepGradient(0f, 0f, colours, FloatArray(33) { it / 32f })
            rim.strokeWidth = 1f * d
            rim.blendMode = android.graphics.BlendMode.PLUS
        }
        canvas.save()
        canvas.translate(pill.centerX(), pill.centerY())
        rim.alpha = (255 * p).toInt()
        val half = 0.5f * d
        canvas.drawRoundRect(-pill.width() / 2f + half, -pill.height() / 2f + half,
            pill.width() / 2f - half, pill.height() / 2f - half, radius, radius, rim)
        canvas.restore()
    }

    private fun withAlpha(colour: Int, a: Float): Int =
        (colour and 0x00FFFFFF) or ((255 * a.coerceIn(0f, 1f)).toInt() shl 24)

    private fun blend(a: Int, b: Int, t: Float): Int = androidx.core.graphics.ColorUtils.blendARGB(a, b, t)

    companion object {
        /** The original's one size knob. */
        const val NS = 0.8f
        /** The bar's share of the screen's width — the original's 70%, wider for four tabs. */
        const val WIDTH_SHARE = 0.9f
        /** The tabs' dark glass surface: #121212 at 40%. */
        const val SURFACE = 0x66121212
        /** A tab's label: the original's 10.5, a little larger for this face. */
        const val LABEL_SP = 12f
    }
}
