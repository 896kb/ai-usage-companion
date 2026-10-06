package dev.kb896.aiusage.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import dev.kb896.aiusage.ui.UiType
import dev.kb896.aiusage.ui.glass.Ink
import kotlin.math.abs

/**
 * The trim's rules: at least half a second, at most a minute, inside the video. Dragging
 * one handle past a minute from the other brings the other along. A video shorter than
 * half a second has nothing to trim and is kept whole.
 */
object TrimRange {
    const val MIN_MS = 500L
    const val MAX_MS = 60_000L

    fun moveStart(start: Long, end: Long, to: Long, duration: Long): Pair<Long, Long> {
        if (duration <= MIN_MS) return 0L to duration
        val s = to.coerceIn(0, end - MIN_MS)
        return s to minOf(end, s + MAX_MS).coerceAtMost(duration)
    }

    fun moveEnd(start: Long, end: Long, to: Long, duration: Long): Pair<Long, Long> {
        if (duration <= MIN_MS) return 0L to duration
        val e = to.coerceIn(start + MIN_MS, duration)
        return maxOf(start, e - MAX_MS) to e
    }
}

/**
 * Thumbnails along the video with a handle at each end and the times above. A handle
 * dragged scrubs the preview to it ([onScrub]); let go, the range is reported ([onRange])
 * and the preview loops it. Thumbnails load off the main thread.
 */
class TrimBar(context: Context) : View(context) {
    var onScrub: ((Long) -> Unit)? = null
    var onRange: ((Long, Long) -> Unit)? = null
    var startMs = 0L; private set
    var endMs = 0L; private set
    private var duration = 1L
    private var thumbs: List<Bitmap> = emptyList()
    private val density = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ink.primary(0f)
        textSize = 12 * density
        typeface = UiType.of(context)
        UiType.variation(context)?.let { fontVariationSettings = it }
    }
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.ACCENT }
    private val cellRect = RectF()
    /** -1 the start handle, 1 the end one, 0 none. */
    private var dragging = 0

    fun setVideo(uri: Uri, durationMs: Long, startMs: Long, endMs: Long) {
        duration = durationMs.coerceAtLeast(1)
        this.startMs = startMs.coerceIn(0, duration)
        this.endMs = if (endMs <= this.startMs || endMs > duration) minOf(duration, this.startMs + TrimRange.MAX_MS) else endMs
        Thread {
            val r = MediaMetadataRetriever()
            val got = try {
                r.setDataSource(context, uri)
                List(COUNT) { i ->
                    r.getScaledFrameAtTime(
                        duration * 1000 * (2 * i + 1) / (2 * COUNT),
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_PX, THUMB_PX,
                    )
                }.filterNotNull()
            } catch (_: Exception) {
                emptyList()
            } finally {
                r.release()
            }
            post { thumbs = got; invalidate() }
        }.start()
        invalidate()
    }

    private fun strip() = RectF(PAD_DP * density, LABEL_DP * density, width - PAD_DP * density, height.toFloat())
    private fun xOf(ms: Long) = strip().let { it.left + it.width() * ms / duration }
    private fun msOf(x: Float) = strip().let { ((x - it.left) / it.width() * duration).toLong() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ((LABEL_DP + STRIP_DP) * density).toInt())

    override fun onDraw(canvas: Canvas) {
        val s = strip()
        val cell = s.width() / COUNT
        thumbs.forEachIndexed { i, b ->
            cellRect.set(s.left + i * cell, s.top, s.left + (i + 1) * cell, s.bottom)
            canvas.drawBitmap(b, null, cellRect, null)
        }
        canvas.drawRect(s.left, s.top, xOf(startMs), s.bottom, shade)
        canvas.drawRect(xOf(endMs), s.top, s.right, s.bottom, shade)
        val r = 4 * density
        for (x in floatArrayOf(xOf(startMs), xOf(endMs))) {
            canvas.drawRoundRect(x - 5 * density, s.top - 2 * density, x + 5 * density, s.bottom + 2 * density, r, r, handle)
        }
        val label = "${clock(startMs)} – ${clock(endMs)}  ·  ${"%.1f".format((endMs - startMs) / 1000f)} s"
        canvas.drawText(label, s.left, (LABEL_DP - 6) * density, text)
    }

    private fun clock(ms: Long) = "%d:%04.1f".format(ms / 60_000, (ms % 60_000) / 1000f)

    @Suppress("ClickableViewAccessibility")   // a pair of handles, not a button
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (abs(e.x - xOf(startMs)) <= abs(e.x - xOf(endMs))) -1 else 1
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging != 0) {
                val (s, en) = if (dragging < 0) TrimRange.moveStart(startMs, endMs, msOf(e.x), duration)
                else TrimRange.moveEnd(startMs, endMs, msOf(e.x), duration)
                startMs = s
                endMs = en
                onScrub?.invoke(if (dragging < 0) s else en)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging != 0) {
                dragging = 0
                onRange?.invoke(startMs, endMs)
            }
        }
        return true
    }

    private companion object {
        const val COUNT = 8
        const val THUMB_PX = 160
        const val PAD_DP = 6f
        const val LABEL_DP = 22f
        const val STRIP_DP = 56f
    }
}
