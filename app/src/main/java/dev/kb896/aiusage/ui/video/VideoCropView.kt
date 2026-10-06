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
    /** Only a prepared player may be seeked or started; calls before that are kept for it. */
    private var prepared = false
    private var pendingSeek: Long? = null

    /** Said when the video cannot be played here, so the editor can say so and refuse to cut it. */
    var onFailed: (() -> Unit)? = null

    /** The rear display's own shape, so the clip made at its pixels fills it exactly. */
    private val aspect = RearSize.pixels(context).let { (w, h) -> w.toFloat() / h }

    /** Its rounded corners, as a share of the frame's width — the frame drawn the shape of the back. */
    private val corner = RearSize.cornerShare(context)
    private val framePath = android.graphics.Path()
    private fun cornerRadius() = region().width() * corner

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
            val r = cornerRadius()
            canvas.drawRoundRect(region(), r, r, paint)
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
            val r = cornerRadius()
            framePath.reset()
            framePath.addRoundRect(box, r, r, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipOutPath(framePath)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            canvas.restore()
            canvas.drawRoundRect(box, r, r, frame)
        }
    }

    init {
        // The video runs past the region; it must not run past this view — the dialog's title
        // sits just above. Unclip leaves this view alone for the same reason.
        clipChildren = true
        clipToPadding = true
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

    /** The region, centred, at the rear display's shape, read from the display. */
    fun region(): RectF {
        val w = width * 0.86f
        val h = w / aspect
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
        prepared = false
        player = try {
            MediaPlayer().apply {
                setDataSource(context, u)
                setSurface(s)
                setVolume(0f, 0f)
                setOnPreparedListener { p ->
                    prepared = true
                    val scrub = pendingSeek
                    pendingSeek = null
                    if (scrub != null) {
                        p.seekTo(scrub, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        p.seekTo(rangeStart, MediaPlayer.SEEK_CLOSEST)
                        p.start()
                    }
                    postOnAnimation(watch)
                }
                setOnErrorListener { _, _, _ -> failed(); true }
                prepareAsync()
            }
        } catch (_: Exception) {
            failed()
            null
        }
        place()
    }

    /** Back to the range's start whenever playback passes its end. */
    private val watch = object : Runnable {
        override fun run() {
            val p = player?.takeIf { prepared } ?: return
            if (p.isPlaying && p.currentPosition >= rangeEnd) p.seekTo(rangeStart, MediaPlayer.SEEK_CLOSEST)
            postOnAnimation(this)
        }
    }

    /** Loops [startMs]..[endMs], from its start. */
    fun loopRange(startMs: Long, endMs: Long) {
        rangeStart = startMs
        rangeEnd = endMs
        pendingSeek = null
        player?.takeIf { prepared }?.let {
            it.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
            if (!it.isPlaying) it.start()
        }
    }

    /** Scrubbing a trim handle: paused on that frame until the handle is let go. */
    fun seek(ms: Long) {
        val p = player ?: return
        if (!prepared) { pendingSeek = ms; return }
        if (p.isPlaying) p.pause()
        p.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
    }

    /** The player cannot play this video: let it go and tell the editor. */
    private fun failed() {
        removeCallbacks(watch)
        runCatching { player?.release() }
        player = null
        prepared = false
        onFailed?.invoke()
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
        runCatching { player?.release() }
        player = null
        prepared = false
        surface?.release()
        surface = null
    }
}
