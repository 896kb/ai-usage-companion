package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log

/**
 * A video background's frames, as hardware bitmaps the panel draws like a picture.
 *
 * The clip decodes into an ImageReader rather than a SurfaceView: the frame has to be IN
 * the panel's drawing, where the glass panes refract it, not a layer under it. Each new
 * frame is wrapped (no copy) and [onFrame] asks for a redraw; the frames before it stay
 * open a little longer, since the render thread may still be reading them.
 *
 * The reader is the clip's own size, read from the file: the decoder writes at that size,
 * and the panel scales the frame into place when it draws it.
 */
class VideoPlayback(private val onFrame: () -> Unit) {
    @Volatile var frame: Bitmap? = null; private set

    private var player: MediaPlayer? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var path: String? = null
    private val held = ArrayDeque<Image>()
    private val main = Handler(Looper.getMainLooper())
    private var easeIn = false
    private var easeOut = false
    private var easing = false
    private var lastSpeed = 1f

    fun open(path: String) {
        if (path == this.path && player != null) return
        release()
        val (w, h) = sizeOf(path) ?: return
        this.path = path
        val t = HandlerThread("video-backdrop").also { it.start() }
        thread = t
        val r = ImageReader.newInstance(w, h, ImageFormat.PRIVATE, 5, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        r.setOnImageAvailableListener({ took(it) }, Handler(t.looper))
        reader = r
        player = try {
            MediaPlayer().apply {
                setDataSource(path)
                setSurface(r.surface)
                setVolume(0f, 0f)
                setOnErrorListener { _, what, extra -> Log.w(TAG, "player error $what/$extra"); true }
                prepare()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not open $path", e)
            release(); null
        }
    }

    private fun sizeOf(path: String): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            if (w > 0 && h > 0) w to h else null
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    private fun took(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val buffer = image.hardwareBuffer ?: run { image.close(); return }
        val bmp = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
        buffer.close()
        if (bmp == null) { Log.w(TAG, "a frame would not wrap"); image.close(); return }
        synchronized(held) {
            held.addLast(image)
            while (held.size > 3) held.removeFirst().close()
        }
        frame = bmp
        main.post(onFrame)
    }

    fun loop() {
        val p = player ?: return
        stopEasing()
        p.isLooping = true
        if (!p.isPlaying) {
            p.playbackParams = p.playbackParams.setSpeed(1f)
            if (!p.isPlaying) p.start()
        } else if (lastSpeed != 1f) {
            p.playbackParams = p.playbackParams.setSpeed(1f)
        }
        lastSpeed = 1f
    }

    fun holdFirst() {
        val p = player ?: return
        stopEasing()
        p.isLooping = false
        if (p.isPlaying) p.pause()
        p.seekTo(0, MediaPlayer.SEEK_CLOSEST)
    }

    fun playOnce(easeIn: Boolean, easeOut: Boolean) {
        val p = player ?: return
        stopEasing()
        p.isLooping = false
        p.seekTo(0, MediaPlayer.SEEK_CLOSEST)
        this.easeIn = easeIn; this.easeOut = easeOut
        lastSpeed = EaseCurve.speed(0, p.duration.toLong(), easeIn, easeOut)
        // A speed set on a paused player starts it: this is the start.
        p.playbackParams = p.playbackParams.setSpeed(lastSpeed)
        if (!p.isPlaying) p.start()
        if (easeIn || easeOut) { easing = true; main.post(ease) }
    }

    /** The ramp, followed while it runs; stops itself with the clip. */
    private val ease = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!easing || !p.isPlaying) { easing = false; return }
            val s = EaseCurve.speed(p.currentPosition.toLong(), p.duration.toLong(), easeIn, easeOut)
            if (kotlin.math.abs(s - lastSpeed) > 0.01f) {
                lastSpeed = s
                p.playbackParams = p.playbackParams.setSpeed(s)
            }
            main.postDelayed(this, EASE_STEP_MS)
        }
    }

    private fun stopEasing() { easing = false; main.removeCallbacks(ease) }

    fun pause() {
        stopEasing()
        player?.let { if (it.isPlaying) it.pause() }
    }

    fun release() {
        stopEasing()
        player?.release(); player = null
        reader?.close(); reader = null
        synchronized(held) { held.forEach { it.close() }; held.clear() }
        thread?.quitSafely(); thread = null
        frame = null
        path = null
    }

    private companion object {
        const val TAG = "AiUsageVideo"
        /** How often the ramp's speed is updated; frames still arrive at the clip's own rate. */
        const val EASE_STEP_MS = 16L
    }
}
