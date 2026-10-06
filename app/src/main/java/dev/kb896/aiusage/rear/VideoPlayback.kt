package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * A video background's frames, as hardware bitmaps the panel draws like a picture.
 *
 * The clip decodes into an ImageReader rather than a SurfaceView: the frame has to be IN
 * the panel's drawing, where the glass panes refract it, not a layer under it. Each new
 * frame is wrapped (no copy) and [onFrame] asks for a redraw; the frames before it stay
 * open a little longer, since the render thread may still be reading them.
 *
 * OUR CLOCK, NOT THE PLAYER'S. The clip is decoded by MediaCodec directly and every frame
 * is put up when [EaseCurve.wallMs] says it is due. A MediaPlayer eased by changing its
 * speed sixty times a second resynced on every change and stuttered; this has no speed to
 * change, only times. And where the run is slowed, a frame is put up one frame early and
 * [blend] fades it in over the one before it across the time between them — at a quarter
 * speed a 30 fps clip would otherwise step at seven and a half.
 *
 * STOPPING NEVER BLANKS IT. A frozen or sleeping video keeps [still], a copy of the frame
 * it stopped on, and starting over cross-fades from that copy into the new run over
 * [FADE_MS] — the panel draws [fadeFrom] over the live frame at [fadeLeft].
 *
 * NOTHING HERE MAY THROW INTO THE PANEL. A decoder that fails is let go and [onLost] asks
 * the panel to open a new one. The reader is closed on the thread its frames arrive on,
 * so a frame being taken can never meet a reader being closed.
 */
class VideoPlayback(private val onFrame: () -> Unit, private val onLost: () -> Unit) {
    @Volatile var frame: Bitmap? = null; private set

    /** The frame [frame] is fading in over while a slowed run blends, else null. */
    @Volatile var previous: Bitmap? = null; private set
    @Volatile private var blendFrom = 0L
    @Volatile private var blendTo = 0L

    /** The frame the video stopped on, kept through sleep: a copy, not the reader's buffer. */
    var still: Bitmap? = null; private set

    /** The frame a restart fades out of, and when its fade began (0: not yet, the run has no frame). */
    var fadeFrom: Bitmap? = null; private set
    @Volatile private var fadeStart = 0L
    @Volatile private var awaitingRun = false

    private var decoder: Decoder? = null
    private val main = Handler(Looper.getMainLooper())
    /** Where a slept clip was, to carry a loop on from there when it is opened again. */
    private var sleptPath: String? = null
    private var sleptAtMs = 0L
    private val losses = ArrayDeque<Long>()

    /** What to show: the live frame, or the still while there is none. */
    val shown: Bitmap? get() = frame ?: still

    /** How far [frame] has faded in over [previous], 0 to 1; 1 when nothing is blending. */
    fun blend(now: Long): Float {
        if (previous == null || blendTo <= blendFrom) return 1f
        return ((now - blendFrom).toFloat() / (blendTo - blendFrom)).coerceIn(0f, 1f)
    }

    /** How much of [fadeFrom] still shows, 1 to 0 over [FADE_MS] from the run's first frame. */
    fun fadeLeft(now: Long): Float {
        if (fadeFrom == null) return 0f
        val start = fadeStart
        if (start == 0L) return 1f
        val left = 1f - (now - start).toFloat() / FADE_MS
        if (left <= 0f) { fadeFrom = null; return 0f }
        return left
    }

    /** Opens [path]; true when that made a new decoder, which has drawn nothing yet. */
    fun open(path: String): Boolean {
        val d = decoder
        if (d != null && d.path == path) return false
        // Another clip: the old one's frame is not this one's to fade from.
        if (d != null) drop() else close()
        val from = if (path == sleptPath) sleptAtMs else 0L
        decoder = try {
            Decoder(path, from).also { it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "could not open $path", e)
            null
        }
        return decoder != null
    }

    /**
     * Plays round and round; [restart] starts it over with the fade, else it carries on.
     * Each pass eased as asked — the preview's, see VideoState.loopEase.
     */
    fun loop(restart: Boolean, easeIn: Boolean = false, easeOut: Boolean = false) {
        val d = decoder ?: return
        if (restart) fadeOut()
        d.command(Mode.LOOP, restart, easeIn, easeOut)
    }

    /**
     * Holds the frame where it is. A decoder that has drawn nothing yet — just opened, after
     * a sleep with no still — puts up its first frame and stops there.
     */
    fun freeze() {
        val d = decoder ?: return
        if (frame == null && still == null) d.command(Mode.FIRST, restart = true, easeIn = false, easeOut = false)
        else { d.command(Mode.IDLE, restart = false, easeIn = false, easeOut = false); still = snapshot() }
    }

    /** One run from the start, fading in from the frame it stopped on, eased if asked. */
    fun playOnce(easeIn: Boolean, easeOut: Boolean) {
        val d = decoder ?: return
        fadeOut()
        d.command(Mode.ONCE, restart = true, easeIn = easeIn, easeOut = easeOut)
    }

    /** Lets go of the decoder, keeping the frame it was on to show until it is back. */
    fun sleep() {
        val d = decoder ?: return
        still = snapshot()
        sleptPath = d.path
        sleptAtMs = d.positionMs
        close()
    }

    /** Lets go of everything, the last frame too: there is no video any more. */
    fun drop() {
        close()
        still = null
        fadeFrom = null
        sleptPath = null
        sleptAtMs = 0L
    }

    private fun close() {
        decoder?.quit()
        decoder = null
        awaitingRun = false
        frame = null
        previous = null
    }

    /** A copy of what is on show, safe from the reader reusing its buffer. */
    private fun snapshot(): Bitmap? =
        frame?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() } ?: still

    /** From the start: the frame on show is what the new run fades in from. */
    private fun fadeOut() {
        fadeFrom = snapshot()
        fadeStart = 0L
        awaitingRun = fadeFrom != null
    }

    /** The decoder failed: keep what was on show, let go of it, and ask for a new one. */
    private fun lost(which: Decoder) {
        if (decoder !== which) return
        still = snapshot()
        close()
        // A clip that fails every time it opens must not be reopened forever: three losses
        // in half a minute and it stays on its still until the panel next changes state.
        val now = SystemClock.uptimeMillis()
        losses.removeAll { now - it > LOSS_WINDOW_MS }
        losses.addLast(now)
        if (losses.size <= MAX_LOSSES) onLost()
    }

    private enum class Mode { IDLE, FIRST, LOOP, ONCE }

    /**
     * One clip: its extractor, its codec, the reader its frames land in, and the thread
     * that feeds the one and paces the other. Commands from the main thread go through
     * [lock]; a wait for a frame's time wakes for them. Opened at [fromMs] — a loop carried
     * on after a sleep — it seeks there and puts nothing up before it.
     */
    private inner class Decoder(val path: String, fromMs: Long) : Thread("video-backdrop") {
        /** The clip time of the frame last put up: where a sleep leaves it. */
        @Volatile var positionMs = fromMs; private set
        /** Frames before this are decoded and dropped; the first at or after it starts the clock. */
        private var skipUntil = fromMs
        private val lock = Object()
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        private val reader: ImageReader
        private val frames = HandlerThread("video-frames").also { it.start() }
        private val held = ArrayDeque<Image>()
        private val durationMs: Long

        private var mode = Mode.IDLE
        private var restart = false
        private var easeIn = false
        private var easeOut = false
        private var quitting = false
        /** When each frame is due; moved on by a pause. Read and written under [lock]. */
        private val clock = RunClock()
        /** The blend window the next frame to arrive is put up with; set before it is released. */
        @Volatile private var nextFrom = 0L
        @Volatile private var nextTo = 0L
        @Volatile private var closed = false

        init {
            try {
                extractor.setDataSource(path)
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val w = format.getInteger(MediaFormat.KEY_WIDTH)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
                reader = ImageReader.newInstance(w, h, ImageFormat.PRIVATE, 5, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                reader.setOnImageAvailableListener({ took(it) }, Handler(frames.looper))
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, reader.surface, null, 0)
            } catch (e: Exception) {
                extractor.release()
                frames.quitSafely()
                throw e
            }
        }

        fun command(mode: Mode, restart: Boolean, easeIn: Boolean, easeOut: Boolean) = synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            if (this.mode == Mode.IDLE && mode != Mode.IDLE && !restart) clock.resume(now)
            if (mode == Mode.IDLE && this.mode != Mode.IDLE) clock.pause(now)
            this.mode = mode
            this.restart = this.restart || restart
            // Only a run says how it is eased; a freeze keeps the run's, which its held frame is timed by.
            if (mode == Mode.LOOP || mode == Mode.ONCE) {
                this.easeIn = easeIn
                this.easeOut = easeOut
            }
            lock.notifyAll()
        }

        fun quit() = synchronized(lock) { quitting = true; lock.notifyAll() }

        /** When clip time [ptsMs] is due, as an offset from the run's start. */
        private fun wall(ptsMs: Long): Long =
            if (easeIn || easeOut) EaseCurve.wallMs(ptsMs, durationMs, easeIn, easeOut).toLong() else ptsMs

        override fun run() {
            try {
                codec.start()
                decode()
            } catch (e: Exception) {
                Log.w(TAG, "decoder failed", e)
                main.post { lost(this) }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                runCatching { extractor.release() }
                // The reader, and the images it lent, close on the thread frames arrive on.
                Handler(frames.looper).post {
                    closed = true
                    synchronized(held) { held.forEach { it.close() }; held.clear() }
                    runCatching { reader.close() }
                    frames.quitSafely()
                }
            }
        }

        private fun decode() {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            if (skipUntil > 0) extractor.seekTo(skipUntil * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var lastPts = -1L
            var lastDue = 0L
            var frameMs = 33L
            while (true) {
                synchronized(lock) {
                    if (quitting) return
                    if (restart) {
                        restart = false
                        skipUntil = 0L
                        codec.flush()
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        inputDone = false
                        lastPts = -1L
                        clock.start(SystemClock.uptimeMillis())
                    }
                    while (mode == Mode.IDLE && !quitting && !restart) lock.wait()
                    if (quitting) return
                }
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TICK_US)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TICK_US)
                if (o < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    codec.releaseOutputBuffer(o, false)
                    synchronized(lock) {
                        if (mode == Mode.LOOP) {
                            // Round again, the first frame due one frame after the last.
                            codec.flush()
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            inputDone = false
                            clock.restartAt(lastDue + frameMs)
                            lastPts = -1L
                        } else {
                            mode = Mode.IDLE   // a run's end: its last frame stays up
                            clock.pause(SystemClock.uptimeMillis())
                        }
                    }
                    continue
                }
                val pts = info.presentationTimeUs / 1000
                val skip = synchronized(lock) {
                    when {
                        skipUntil <= 0L || restart -> false
                        pts < skipUntil -> true
                        else -> {
                            // The frame it slept on: due now, and the run's clock set from it.
                            skipUntil = 0L
                            clock.startFrom(SystemClock.uptimeMillis(), wall(pts))
                            false
                        }
                    }
                }
                if (skip) { codec.releaseOutputBuffer(o, false); continue }
                if (!present(o, pts, lastPts, lastDue)) continue
                positionMs = pts
                if (lastPts >= 0 && pts > lastPts) frameMs = pts - lastPts
                lastDue = synchronized(lock) { clock.due(wall(pts)) }
                lastPts = pts
            }
        }

        /**
         * Waits until frame [pts] is due and puts it up; false when a command overtook it
         * (a restart drops it, a pause holds it until resumed). A slowed frame goes up one
         * frame early and blends in across the gap — see the class comment.
         */
        private fun present(o: Int, pts: Long, lastPts: Long, lastDue: Long): Boolean {
            while (true) {
                val (due, releaseAt) = synchronized(lock) {
                    if (quitting || restart) { codec.releaseOutputBuffer(o, false); return false }
                    if (mode == Mode.FIRST) {
                        nextFrom = 0L; nextTo = 0L
                        codec.releaseOutputBuffer(o, true)
                        mode = Mode.IDLE
                        clock.pause(SystemClock.uptimeMillis())
                        return true
                    }
                    if (mode == Mode.IDLE) { lock.wait(); return@synchronized null to null }
                    val due = clock.due(wall(pts))
                    val slowed = lastPts >= 0 && (due - lastDue) > (pts - lastPts) * SLOWED
                    due to (if (slowed) lastDue else due)
                }
                if (due == null || releaseAt == null) continue
                val wait = releaseAt - SystemClock.uptimeMillis()
                if (wait > 0) {
                    synchronized(lock) { lock.wait(wait) }
                    continue   // re-read: a command may have moved the clock
                }
                if (releaseAt < due) { nextFrom = releaseAt; nextTo = due } else { nextFrom = 0L; nextTo = 0L }
                codec.releaseOutputBuffer(o, true)
                return true
            }
        }

        /** On the frames thread. Never throws: a frame that cannot be had is skipped. */
        private fun took(r: ImageReader) {
            if (closed) return
            val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
            val bmp = try {
                image.hardwareBuffer?.use { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
            } catch (_: Exception) {
                null
            }
            if (bmp == null) {
                Log.w(TAG, "a frame would not wrap")
                image.close()
                return
            }
            synchronized(held) {
                held.addLast(image)
                while (held.size > 3) held.removeFirst().close()
            }
            val from = nextFrom
            val to = nextTo
            if (to > from) {
                previous = frame
                blendFrom = from
                blendTo = to
            } else {
                previous = null
            }
            frame = bmp
            if (awaitingRun) { awaitingRun = false; fadeStart = SystemClock.uptimeMillis() }
            main.post(onFrame)
        }
    }

    private companion object {
        const val TAG = "AiUsageVideo"
        /** How long a decoder call waits for a buffer before looking at the commands again. */
        const val TICK_US = 5_000L
        /** A frame held this much longer than the clip spaced it is slowed, and blends. */
        const val SLOWED = 1.3f
        /** The cross-fade from the frame it stopped on into a new run. */
        const val FADE_MS = 450L
        const val MAX_LOSSES = 3
        const val LOSS_WINDOW_MS = 30_000L
    }
}
