package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.rear.ArtPlacement
import dev.kb896.aiusage.rear.GlassLuminance
import java.io.File

/**
 * The wallpaper every glass control on the front screen samples.
 *
 * Built once at the window's size and held as one bitmap; its luminance grid is built
 * with it. Nothing reads pixels back per frame — a control asks the grid what is behind
 * it, which is the panel's rule (see GlassLuminance) rather than the catalog's readback.
 */
object Backdrop {
    var bitmap: Bitmap? = null; private set
    /**
     * The same bitmap as a shader, made once with it.
     *
     * Pane used to build one of these per draw — per pane, per frame, on a screen that
     * can hold a dozen. A BitmapShader is only a bitmap plus a tile mode plus a local
     * matrix, and the matrix is the only part that changes between panes, so one shader
     * with the matrix set per draw is the same picture for no allocation.
     */
    var shader: BitmapShader? = null; private set
    var cells: FloatArray = FloatArray(GlassLuminance.SIDE * GlassLuminance.SIDE); private set
    var width = 0; private set
    var height = 0; private set
    /** Where the root view sits on screen; every sample is relative to it. */
    val origin = IntArray(2)
    private var root: View? = null

    /** Give the root its wallpaper background and build at its size once laid out. */
    fun install(root: View) {
        this.root = root
        root.background = BackdropDrawable()
        root.doOnLayout { build(it) }
        root.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) build(v)
        }
    }

    /** The image or colour changed. */
    fun rebuild(context: Context) {
        // Under ORIGINAL nothing samples this. It still ran on every background edit,
        // decoding the picture and allocating a window-sized bitmap for a view that will
        // never draw a pane — and, after a GLASS -> ORIGINAL switch, for a dead one.
        if (!UiTheme.isGlass(context)) return
        val r = root ?: return
        build(r)
        r.invalidate()
        invalidateGlass(r)
    }

    private fun build(root: View) {
        val w = root.width; val h = root.height
        if (w <= 0 || h <= 0) return
        root.getLocationOnScreen(origin)
        val config = ConfigStore(root.context).load()
        val image = config.backdrop.imagePath?.let { decodeNear(it, w, h) }
        val built = paint(w, h, config, image)
        // The outgoing bitmap is dropped, never recycled: a draw already queued may still
        // hold it, and drawing a recycled bitmap throws where letting go of one costs a GC.
        bitmap = built
        shader = BitmapShader(built, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        width = w; height = h
        cells = cellsOf(built)
    }

    /**
     * Let go of everything: the root, the window-sized bitmap and the grid.
     *
     * This is an object, so it outlives the activity that filled it. A GLASS -> ORIGINAL
     * switch recreates the activity and nothing here would ever be asked for again, while
     * a stale [root] keeps the whole dead view tree — and its 13 MB of wallpaper — alive.
     */
    fun release() {
        root = null
        bitmap = null
        shader = null
        width = 0
        height = 0
        cells = FloatArray(GlassLuminance.SIDE * GlassLuminance.SIDE)
    }

    /**
     * A backdrop with no window behind it, for tests.
     *
     * Robolectric has no laid-out root to build from, and half of what a control does
     * only happens once [bitmap] is non-null. Leaves the fields exactly as [build] would,
     * at the origin. A member rather than an extension because the setters are private.
     */
    internal fun installForTest(bitmap: Bitmap) {
        this.bitmap = bitmap
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        width = bitmap.width
        height = bitmap.height
        origin[0] = 0; origin[1] = 0
        cells = cellsOf(bitmap)
    }

    /** Draw the window's wallpaper: the picture centre-cropped, or the colour as a gradient. */
    fun paint(w: Int, h: Int, config: PanelConfig, image: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (image != null) {
            val dst = ArtPlacement.destination(RectF(0f, 0f, w.toFloat(), h.toFloat()),
                image.width, image.height, 0f, 0f, 1f)
            canvas.drawBitmap(image, null, dst, p)
        } else {
            val stops = gradientStops(config.background)
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), stops[0], stops[1], Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        return out
    }

    /** Top and bottom of the flat-colour gradient. A flat colour gives a lens nothing to bend. */
    fun gradientStops(colour: Int): IntArray = intArrayOf(
        Ink.mix(colour, 0xFFFFFFFF.toInt(), 0.08f),
        Ink.mix(colour, 0xFF000000.toInt(), 0.12f),
    )

    /** Decode near the window's size, never at a photograph's own. Null if unreadable. */
    fun decodeNear(path: String, w: Int, h: Int): Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun cellsOf(bmp: Bitmap): FloatArray {
        val side = GlassLuminance.SIDE
        val small = Bitmap.createScaledBitmap(bmp, side, side, true)
        val out = FloatArray(side * side)
        for (y in 0 until side) for (x in 0 until side) out[y * side + x] = GlassLuminance.of(small.getPixel(x, y))
        return out
    }

    /** Mean luminance under a rectangle given in ROOT coordinates (screen minus [origin]). */
    fun luminance(left: Float, top: Float, right: Float, bottom: Float): Float {
        if (width <= 0 || height <= 0) return 0f
        return GlassLuminance.average(cells, left, top, right, bottom, width.toFloat(), height.toFloat())
    }

    private val loc = IntArray(2)

    /** Mean luminance under [view], wherever it is on screen right now. */
    fun luminanceOf(view: View): Float {
        view.getLocationOnScreen(loc)
        val l = (loc[0] - origin[0]).toFloat(); val t = (loc[1] - origin[1]).toFloat()
        return luminance(l, t, l + view.width, t + view.height)
    }

    fun meanLuminance(): Float = if (width <= 0) 0f else cells.average().toFloat()

    /**
     * Glass moving over a still wallpaper has to redraw as it moves.
     *
     * [also] is for what a scroll changes that is not a Glassy view — the headings, which
     * are plain TextViews over the wallpaper and re-ink from the cell they are over.
     */
    fun track(scroll: ViewGroup, also: (() -> Unit)? = null) {
        scroll.setOnScrollChangeListener { v, _, _, _, _ ->
            invalidateGlass(v)
            also?.invoke()
        }
    }

    fun invalidateGlass(view: View) {
        if (view is Glassy) view.invalidate()
        if (view is ViewGroup) for (i in 0 until view.childCount) invalidateGlass(view.getChildAt(i))
    }
}

/** The wallpaper as the root's background. Flat panel colour until the bitmap exists. */
class BackdropDrawable : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    override fun draw(canvas: Canvas) {
        val b = Backdrop.bitmap
        if (b == null) { paint.color = Panel.Palette.BG; canvas.drawRect(bounds, paint) }
        else canvas.drawBitmap(b, bounds.left.toFloat(), bounds.top.toFloat(), paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}
