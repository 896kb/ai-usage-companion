package dev.kb896.aiusage.ui.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.ArtPlacement
import dev.kb896.aiusage.ui.ArtCropView
import dev.kb896.aiusage.ui.CropGesture

/**
 * The editor's frame: the video playing, muted, placed by the panel's own ArtPlacement in
 * a region shaped like the rear display, the ground colour under it at the chosen opacity,
 * and everything outside the region dimmed — the image cropper's look, with a video.
 */
class VideoCropView(context: Context) : FrameLayout(context) {
    private val texture = TextureView(context)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var uri: Uri? = null
    var videoSize: Pair<Int, Int>? = null; private set
    private var rangeStart = 0L
    private var rangeEnd = Long.MAX_VALUE

    private val gesture = CropGesture(context, ::region, { videoSize }, ::place)
    val panX get() = gesture.panX
    val panY get() = gesture.panY
    val zoom get() = gesture.zoom

    var ground: Int = 0xFF000000.toInt()
        set(value) { field = value; under.invalidate() }
    var videoAlpha: Float = 1f
        set(value) { field = value; texture.alpha = value.coerceIn(0f, 1f) }

    /** The colour behind, inside the region only — where the panel has it. */
    private val under = object : View(context) {
        private val paint = Paint()
        override fun onDraw(canvas: Canvas) {
            paint.color = ground or (0xFF shl 24)
            canvas.drawRect(region(), paint)
        }
    }

    /** Outside the region dimmed, and the region's edge. */
    private val over = object : View(context) {
        private val shade = Paint().apply { color = 0xB0000000.toInt() }
        private val frame = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = Panel.Palette.WORK
        }
        override fun onDraw(canvas: Canvas) {
            val box = region()
            canvas.save()
            canvas.clipOutRect(box)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            canvas.restore()
            canvas.drawRect(box, frame)
        }
    }

    init {
        addView(under, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(over, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                startPlayer()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = place()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    /** The region, centred, at the rear display's shape — the image cropper's for a background. */
    fun region(): RectF {
        val w = width * 0.86f
        val h = w / ArtCropView.PANEL_ASPECT
        return RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    fun setVideo(uri: Uri, panX: Float, panY: Float, zoom: Float) {
        this.uri = uri
        gesture.panX = panX
        gesture.panY = panY
        gesture.zoom = zoom
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            if (w > 0 && h > 0) videoSize = VideoCrop.displaySize(w, h, rot)
        } catch (_: Exception) {
            videoSize = null
        } finally {
            r.release()
        }
        startPlayer()
    }

    private fun startPlayer() {
        val s = surface ?: return
        val u = uri ?: return
        if (player != null) return
        player = MediaPlayer().apply {
            setDataSource(context, u)
            setSurface(s)
            setVolume(0f, 0f)
            setOnPreparedListener { p ->
                p.seekTo(rangeStart, MediaPlayer.SEEK_CLOSEST)
                p.start()
                postOnAnimation(watch)
            }
            prepareAsync()
        }
        place()
    }

    /** Back to the range's start whenever playback passes its end. */
    private val watch = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying && p.currentPosition >= rangeEnd) p.seekTo(rangeStart, MediaPlayer.SEEK_CLOSEST)
            postOnAnimation(this)
        }
    }

    /** Loops [startMs]..[endMs], from its start. */
    fun loopRange(startMs: Long, endMs: Long) {
        rangeStart = startMs
        rangeEnd = endMs
        player?.let {
            it.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
            if (!it.isPlaying) it.start()
        }
    }

    /** Scrubbing a trim handle: paused on that frame until the handle is let go. */
    fun seek(ms: Long) {
        player?.let {
            if (it.isPlaying) it.pause()
            it.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        }
    }

    /** The TextureView stretches the video to its bounds; the matrix puts it where ArtPlacement says. */
    private fun place() {
        val (w, h) = videoSize ?: return
        if (width == 0 || height == 0) return
        val dest = ArtPlacement.destination(region(), w, h, gesture.panX, gesture.panY, gesture.zoom)
        texture.setTransform(Matrix().apply {
            setScale(dest.width() / width, dest.height() / height)
            postTranslate(dest.left, dest.top)
        })
        under.invalidate()
        over.invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec((width * 0.62f).toInt(), MeasureSpec.EXACTLY))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @Suppress("ClickableViewAccessibility")   // a crop frame has no click semantics
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gesture.onTouch(event)
        return true
    }

    fun release() {
        removeCallbacks(watch)
        player?.release()
        player = null
        surface?.release()
        surface = null
    }
}
