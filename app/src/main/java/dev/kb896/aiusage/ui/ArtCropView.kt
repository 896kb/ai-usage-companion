package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.ArtPlacement

/**
 * Choose which part of an image the panel shows: drag to move, pinch to zoom.
 *
 * The art region is roughly 4:1 — 278 by 70 panel units — so almost nothing taken with a
 * camera fits it. Without a cropper the app picks the middle of the picture and the user
 * discovers what it chose by turning the phone over, which is the worst possible feedback
 * loop for a decision this arbitrary.
 *
 * The gesture moves the IMAGE, not a crop rectangle over it. Dragging a window around a
 * fixed photo and dragging a photo under a fixed window both work, but only the second
 * matches what the result looks like: the frame here is the panel's region, in place, and
 * whatever sits inside it is what will be drawn.
 */
class ArtCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /**
     * The shape of the region being cropped into.
     *
     * Settable because the same cropper now serves two regions that are nothing like each
     * other — a 4:1 strip for the maker's mark and the whole 1.58:1 panel for a
     * background. Showing one shape while the picture lands in the other is the exact
     * mistake this view exists to prevent.
     */
    var regionAspect: Float = REGION_ASPECT
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0xB0000000.toInt() }
    private val frame = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Panel.Palette.WORK
    }

    private var bitmap: Bitmap? = null

    private val gesture = CropGesture(context, ::region, { bitmap?.let { it.width to it.height } }, ::invalidate)

    /** Pan as a fraction of the slack at the current zoom, matching ArtConfig. */
    val panX get() = gesture.panX
    val panY get() = gesture.panY
    val zoom get() = gesture.zoom

    fun setImage(bitmap: Bitmap?, panX: Float, panY: Float, zoom: Float) {
        this.bitmap = bitmap
        gesture.panX = panX
        gesture.panY = panY
        gesture.zoom = zoom
        invalidate()
    }

    /** The region, centred in this view, at the panel's own aspect. */
    private fun region(): RectF {
        val w = width * 0.86f
        val h = w / regionAspect
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    @Suppress("ClickableViewAccessibility")   // a crop frame has no click semantics
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gesture.onTouch(event)
        return true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(width, (width * 0.62f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val box = region()

        // The whole image is drawn and the outside dimmed, rather than only the kept
        // part: a crop you cannot see the outside of is a guess. The placement itself is
        // the panel's own, so this preview cannot lie about the result.
        val dest = ArtPlacement.destination(box, bmp.width, bmp.height, panX, panY, zoom)
        // The colour behind the picture, where the panel has it — inside the region — and
        // the picture at the opacity it will have there.
        ground?.let { groundPaint.color = it or (0xFF shl 24); canvas.drawRect(box, groundPaint) }
        paint.alpha = (255 * imageAlpha.coerceIn(0f, 1f)).toInt()
        canvas.drawBitmap(bmp, null, dest, paint)
        paint.alpha = 255
        canvas.save()
        canvas.clipOutRect(box)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        canvas.restore()
        canvas.drawRect(box, frame)
    }

    /**
     * Not private any more: the region's shape is now the caller's to choose, so the two
     * shapes it may be have to be nameable from outside.
     */
    /** The colour behind the picture, shown inside the region; null shows none. */
    var ground: Int? = null
        set(value) { field = value; invalidate() }

    /** How much of the picture shows over [ground], 0..1. */
    var imageAlpha: Float = 1f
        set(value) { field = value; invalidate() }

    private val groundPaint = Paint()

    companion object {
        /** The art region's shape: 278 by 70 panel units. */
        const val REGION_ASPECT = 278f / 70f

        /** The whole panel, for a picture that goes behind all of it. */
        const val PANEL_ASPECT = 904f / 572f
    }
}
