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
 * is put up when it is due — by [EaseCurve.wallMs] over a run, by [LoopPace] through a stop
 * or a wake. And where it is slowed, a frame is put up one frame early and [blend] fades it
 * in over the one before it across the time between them.
 *
 * TWO WAYS ROUND. A video cannot be decoded backwards, so going backwards plays the clip's
 * reversed copy: one decoder for each direction, and the one not playing waits with the
 * frame it will start on already decoded, so a Ping-pong's turn or a Yoyo's return starts
 * at once. Positions are kept the forward way round; the reversed copy's time t is the
 * forward clip's duration - t.
 *
 * STOPPING NEVER BLANKS IT. A frozen or sleeping video keeps [still], a copy of the frame
 * it stopped on, and starting over cross-fades from that copy into the new run over
 * [FADE_MS] — the panel draws [fadeFrom] over the live frame at [fadeLeft].
 *
 * NOTHING HERE MAY THROW INTO THE PANEL. A decoder that fails is let go and [onLost] asks
 * the panel to open a new one. Each reader is closed on the thread its frames arrive on, so
 * a frame being taken can never meet a reader being closed.
 */
class VideoPlayback(private val onFrame: () -> Unit, private val onLost: () -> Unit) {
    @Volatile var frame: Bitmap? = null; private set

    /** The frame [frame] is fading in over while a slowed run blends, else null. */
    @Volatile var previous: Bitmap? = null; private set
    @Volatile private var blendFrom = 0L
    @Volatile private var blendTo = 0L

    /** The frame the video stopped on, kept through sleep: a copy, not a reader's buffer. */
    var still: Bitmap? = null; private set

    /** The frame a restart fades out of, and when its fade began (0: not yet, the run has no frame). */
    var fadeFrom: Bitmap? = null; private set
    @Volatile private var fadeStart = 0L
    @Volatile private var awaitingRun = false

    private var fwd: Decoder? = null
    private var rev: Decoder? = null
    /** The decoder putting frames up; only its frames reach [frame]. */
    @Volatile private var active: Decoder? = null
    private val main = Handler(Looper.getMainLooper())
    private val losses = ArrayDeque<Long>()

    /** Where a slept clip was, the forward way round, and which way it was going. */
    private var sleptPath: String? = null
    private var sleptAtMs = 0L
    private var sleptBackward = false

    /** What runs after a run ends: going round there and back, and how each leg is eased. */
    private var pingPong = false
    private var legIn = false
    private var legOut = false

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

    /**
     * Opens [path], and its [reverse] copy when it will go backwards; true when that made new
     * decoders, which have drawn nothing yet. Reopening a slept clip picks up where it was.
     */
    fun open(path: String, reverse: String?): Boolean {
        val f = fwd
        if (f != null && f.path == path && rev?.path == reverse) return false
        // Another clip: the old one's frame is not this one's to fade from.
        if (f != null && f.path != path) drop() else close()
        val slept = path == sleptPath
        try {
            val forward = Decoder(path, fromMs = if (slept && !sleptBackward) sleptAtMs else 0L).also { it.start() }
            fwd = forward
            val back = reverse?.let {
                Decoder(it, fromMs = if (slept && sleptBackward) maxOf(0L, forward.durationMs - sleptAtMs) else 0L)
                    .also { d -> d.start() }
            }
            rev = back
            active = if (slept && sleptBackward && back != null) back else forward
        } catch (e: Exception) {
            Log.w(TAG, "could not open $path", e)
            close()
            return false
        }
        return true
    }

    /** Where it is, the forward way round. */
    private fun position(): Long {
        val f = fwd ?: return 0L
        val a = active
        return if (a != null && a === rev) maxOf(0L, f.durationMs - a.positionMs) else f.positionMs
    }

    /** Makes [to] the one putting frames up, the other left waiting where it was. */
    private fun switchTo(to: Decoder) {
        val from = active
        if (from === to) return
        // The new one first: the old one is no longer on show, so it stops at once, not eased.
        active = to
        from?.command(Mode.IDLE, restart = false, easeIn = false, easeOut = false)
    }

    /**
     * Plays round and round; [restart] starts it over with the fade, else it carries on.
     * Each pass eased as asked — the preview's, see VideoState.passEase.
     */
    fun loop(restart: Boolean, easeIn: Boolean = false, easeOut: Boolean = false) {
        val f = fwd ?: return
        pingPong = false
        if (restart) fadeOut()
        if (active !== f) {
            // Was going backwards: on forward from the same frame.
            val at = position()
            switchTo(f)
            if (!restart) f.leg(at, easeIn = false, easeOut = false)
        }
        f.command(Mode.LOOP, restart, easeIn, easeOut)
    }

    /**
     * Holds the frame where it is. A decoder that has drawn nothing yet — just opened, after
     * a sleep with no still — puts up its first frame and stops there.
     */
    fun freeze() {
        val d = active ?: return
        pingPong = false
        if (frame == null && still == null) d.command(Mode.FIRST, restart = true, easeIn = false, easeOut = false)
        else { d.command(Mode.IDLE, restart = false, easeIn = false, easeOut = false); still = snapshot() }
    }

    /** One run from the start, fading in from the frame it stopped on, eased if asked. */
    fun playOnce(easeIn: Boolean, easeOut: Boolean) {
        val f = fwd ?: return
        pingPong = false
        fadeOut()
        switchTo(f)
        f.leg(0L, easeIn, easeOut)
    }

    /** Yoyo, the panel open: on forward from where it is to the end, and rest there. */
    fun toEnd(easeIn: Boolean, easeOut: Boolean) = leg(forward = true, easeIn, easeOut)

    /** Yoyo, the panel shut: back from where it is to the start, and rest there. */
    fun toStart(easeIn: Boolean, easeOut: Boolean) = leg(forward = false, easeIn, easeOut)

    private fun leg(forward: Boolean, easeIn: Boolean, easeOut: Boolean) {
        val f = fwd ?: return
        val r = rev ?: return
        pingPong = false
        val to = if (forward) f else r
        if (active === to && to.inLeg) return                 // already on its way there
        val at = position()
        val from = if (forward) at else maxOf(0L, f.durationMs - at)
        if (active === to && from >= f.durationMs - END_SLACK_MS) {
            // Already there. Just opened — after a sleep — it has put nothing up yet: the
            // frame it is resting on goes up, or the panel would stand on its bare ground.
            if (frame == null) to.showHere()
            return
        }
        switchTo(to)
        to.leg(from, easeIn, easeOut)
    }

    /**
     * Forward and back without end: on from where it is, the way it was going, each leg
     * eased as asked (the preview's yoyo); [restart] starts it over from the start.
     */
    fun pingPong(restart: Boolean, easeIn: Boolean = false, easeOut: Boolean = false) {
        val f = fwd ?: return
        val r = rev ?: return
        legIn = easeIn
        legOut = easeOut
        if (pingPong && !restart && active?.inLeg == true) return
        pingPong = true
        if (restart) {
            fadeOut()
            switchTo(f)
            f.leg(0L, easeIn, easeOut)
            r.preroll(0L)
            return
        }
        val a = active ?: f
        switchTo(a)
        a.leg(a.positionMs, easeIn, easeOut)
        (if (a === f) r else f).preroll(0L)
    }

    /** A run reached its end: turn round or rest, the next way made ready. See VideoState.afterLeg. */
    private fun legEnded(d: Decoder) {
        if (d !== active) return
        val other = (if (d === fwd) rev else fwd) ?: return
        val next = VideoState.afterLeg(pingPong)
        if (next.turn) {
            active = other
            other.leg(0L, legIn, legOut)
        }
        if (next.readyOther) other.preroll(0L)
        if (next.readyFinished) d.preroll(0L)
    }

    /**
     * Whether stopping slows to a stop ([out]) and carrying on picks up speed ([into]) rather
     * than halting and jumping — the double tap, always-on, a wake. See LoopPace.
     */
    fun easeStops(into: Boolean, out: Boolean) {
        fwd?.easeStops(into, out)
        rev?.easeStops(into, out)
    }

    /** Lets go of the decoders, keeping the frame on show until they are back. */
    fun sleep() {
        val f = fwd ?: return
        still = snapshot()
        sleptPath = f.path
        sleptAtMs = position()
        sleptBackward = active === rev
        close()
    }

    /** Lets go of everything, the last frame too: there is no video any more. */
    fun drop() {
        close()
        still = null
        fadeFrom = null
        sleptPath = null
        sleptAtMs = 0L
        sleptBackward = false
    }

    private fun close() {
        fwd?.quit()
        rev?.quit()
        fwd = null
        rev = null
        active = null
        pingPong = false
        awaitingRun = false
        frame = null
        previous = null
    }

    /** A copy of what is on show, safe from the reader reusing its buffer. */
    private fun snapshot(): Bitmap? = frame?.let(::copyOf) ?: still

    /**
     * A frame the decoder lent, as a bitmap of our own. A plain copy reads the frame back
     * to the CPU, which a decoder's YUV buffer may refuse; then the GPU draws it into a
     * buffer of our own instead, which is a picture any canvas can draw.
     */
    private fun copyOf(src: Bitmap): Bitmap? {
        runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()?.let { return it }
        return try {
            val reader = ImageReader.newInstance(src.width, src.height, android.graphics.PixelFormat.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            val node = android.graphics.RenderNode("still").apply {
                setPosition(0, 0, src.width, src.height)
                beginRecording().apply { drawBitmap(src, 0f, 0f, null) }
                endRecording()
            }
            val renderer = android.graphics.HardwareRenderer().apply {
                setSurface(reader.surface)
                setContentRoot(node)
            }
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val copy = reader.acquireNextImage()?.use { image ->
                image.hardwareBuffer?.use { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
            }
            renderer.destroy()
            reader.close()
            copy
        } catch (e: Exception) {
            Log.w(TAG, "could not keep the frame on show", e)
            null
        }
    }

    /** From the start: the frame on show is what the new run fades in from. */
    private fun fadeOut() {
        fadeFrom = snapshot()
        fadeStart = 0L
        awaitingRun = fadeFrom != null
    }

    /** A decoder failed: keep what was on show, let go of both, and ask for new ones. */
    private fun lost(which: Decoder) {
        if (which !== fwd && which !== rev) return
        still = snapshot()
        close()
        // A clip that fails every time it opens must not be reopened forever: three losses
        // in half a minute and it stays on its still until the panel next changes state.
        val now = SystemClock.uptimeMillis()
        losses.removeAll { now - it > LOSS_WINDOW_MS }
        losses.addLast(now)
        if (losses.size <= MAX_LOSSES) onLost()
    }

    /**
     * IDLE holds; FIRST puts up the first frame it reaches and holds; PREROLL decodes to the
     * frame it was sent to and holds it unshown, ready to start at once; LOOP goes round;
     * LEG runs from where it was sent to the end of the clip and holds there.
     */
    private enum class Mode { IDLE, FIRST, PREROLL, LOOP, LEG }

    /**
     * One clip: its extractor, its codec, the reader its frames land in, and the thread
     * that feeds the one and paces the other. Commands from the main thread go through
     * [lock]; a wait for a frame's time wakes for them. Opened at [fromMs] — picked up after
     * a sleep — it seeks there and puts nothing up before it.
     */
    private inner class Decoder(val path: String, fromMs: Long) : Thread("video-backdrop") {
        /** The clip time of the frame last put up (or held ready): where a sleep leaves it. */
        @Volatile var positionMs = fromMs; private set
        /** Frames before this are decoded and dropped; the first at or after it starts the clock. */
        private var skipUntil = fromMs
        private val lock = Object()
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        private val reader: ImageReader
        private val frames = HandlerThread("video-frames").also { it.start() }
        private val held = ArrayDeque<Image>()
        val durationMs: Long

        private var mode = Mode.IDLE
        private var restart = false
        /** Where a restart seeks to. */
        private var seekTo = 0L
        /** Where the current leg started: its ease runs from here to the end. */
        private var legFrom = 0L
        /** The frame a preroll is holding unshown, or -1. */
        private var armedPts = -1L
        private var easeIn = false
        private var easeOut = false
        private var quitting = false
        /** When each frame is due; moved on by a pause. Read and written under [lock]. */
        private val clock = RunClock()
        /** The clock with its eased stops and starts. Under [lock] too. */
        private val pace = LoopPace(clock)
        private var stopEased = false
        private var startEased = false
        /** Whether a frame of this decoder's has been put up, so a stop has something to slow. */
        @Volatile private var shownOne = false
        /** The blend window the next frame to arrive is put up with; set before it is released. */
        @Volatile private var nextFrom = 0L
        @Volatile private var nextTo = 0L
        @Volatile private var closed = false

        val inLeg: Boolean get() = synchronized(lock) { mode == Mode.LEG }

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
                // Never past the last frame: a clip slept at its very end reopens on that frame.
                skipUntil = minOf(skipUntil, maxOf(0L, durationMs - END_SLACK_MS * 2))
            } catch (e: Exception) {
                extractor.release()
                frames.quitSafely()
                throw e
            }
        }

        fun easeStops(into: Boolean, out: Boolean) = synchronized(lock) {
            startEased = into
            stopEased = out
        }

        fun command(mode: Mode, restart: Boolean, easeIn: Boolean, easeOut: Boolean) = synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            val playing = this.mode == Mode.LOOP || this.mode == Mode.LEG
            if (mode == Mode.IDLE && playing) {
                if (stopEased && shownOne && !pace.stopping && active === this) {
                    // Slows to its stop; present() puts it to rest when the ramp ends.
                    pace.easeOut(now, positionMs)
                    lock.notifyAll()
                    return@synchronized
                }
                if (!pace.stopping) clock.pause(now)
            }
            // Carrying on — from a hold, or part-way into slowing down — picks up speed, or goes on at once.
            if (mode != Mode.IDLE && !restart && (this.mode == Mode.IDLE || pace.stopping) && shownOne) {
                if (startEased) pace.easeIn(now, positionMs) else pace.resume(now, positionMs)
            } else if (mode != Mode.IDLE && !restart && this.mode == Mode.IDLE) {
                clock.resume(now)
            }
            if (restart) seekTo = 0L
            this.mode = mode
            this.restart = this.restart || restart
            // Only a loop says here how it is eased; a freeze keeps the run's, which its held frame is timed by.
            if (mode == Mode.LOOP) {
                this.easeIn = easeIn
                this.easeOut = easeOut
            }
            lock.notifyAll()
        }

        /**
         * A run from [from] to the end, eased at its ends as asked. Started at once from a
         * prerolled frame there; otherwise it seeks, and starts at the first frame it reaches.
         */
        fun leg(from: Long, easeIn: Boolean, easeOut: Boolean) = synchronized(lock) {
            this.easeIn = easeIn
            this.easeOut = easeOut
            val armed = armedPts
            if (armed >= 0 && kotlin.math.abs(armed - from) <= ARM_SLACK_MS && !restart) {
                legFrom = armed
                armedPts = -1L
                pace.steady()
                clock.start(SystemClock.uptimeMillis())
            } else {
                legFrom = from.coerceIn(0L, maxOf(0L, durationMs - 1))
                seekTo = legFrom
                restart = true
            }
            mode = Mode.LEG
            lock.notifyAll()
        }

        /** Puts up the frame it was opened at — or its first — and holds it there. */
        fun showHere() = synchronized(lock) {
            if (mode == Mode.IDLE) { mode = Mode.FIRST; lock.notifyAll() }
        }

        /** Decodes to the frame at [at] and holds it unshown, ready for a [leg] from there. */
        fun preroll(at: Long) = synchronized(lock) {
            seekTo = at
            restart = true
            armedPts = -1L
            mode = Mode.PREROLL
            lock.notifyAll()
        }

        fun quit() = synchronized(lock) { quitting = true; lock.notifyAll() }

        /** When clip time [ptsMs] is due, as an offset from the run's start. */
        private fun wall(ptsMs: Long): Long = when {
            mode == Mode.LEG -> EaseCurve.wallMs(ptsMs - legFrom, durationMs - legFrom, easeIn, easeOut).toLong()
            easeIn || easeOut -> EaseCurve.wallMs(ptsMs, durationMs, easeIn, easeOut).toLong()
            else -> ptsMs
        }

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
                        codec.flush()
                        extractor.seekTo(seekTo * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        skipUntil = seekTo
                        inputDone = false
                        lastPts = -1L
                        armedPts = -1L
                        pace.steady()
                        if (seekTo <= 0L) clock.start(SystemClock.uptimeMillis())
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
                    val ended = synchronized(lock) {
                        if (mode == Mode.LOOP) {
                            // Round again, the first frame due one frame after the last.
                            codec.flush()
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            inputDone = false
                            clock.restartAt(lastDue + frameMs)
                            pace.wrapped(lastPts + frameMs)
                            lastPts = -1L
                            false
                        } else {
                            val wasLeg = mode == Mode.LEG
                            mode = Mode.IDLE   // a run's end: its last frame stays up
                            clock.pause(SystemClock.uptimeMillis())
                            wasLeg
                        }
                    }
                    if (ended) main.post { legEnded(this) }
                    continue
                }
                val pts = info.presentationTimeUs / 1000
                val skip = synchronized(lock) {
                    when {
                        skipUntil <= 0L || restart -> false
                        pts < skipUntil -> true
                        else -> {
                            // The frame it was sent to: due now, and the run's clock set from it —
                            // picking up speed from there, if that is how a loop starts. A
                            // preroll's clock waits for its leg.
                            skipUntil = 0L
                            val now = SystemClock.uptimeMillis()
                            when (mode) {
                                Mode.PREROLL -> Unit
                                Mode.LOOP -> if (startEased) pace.easeIn(now, pts) else pace.resume(now, wall(pts))
                                else -> pace.resume(now, wall(pts))
                            }
                            false
                        }
                    }
                }
                if (skip) { codec.releaseOutputBuffer(o, false); continue }
                if (!present(o, pts, lastPts, lastDue)) continue
                positionMs = pts
                shownOne = true
                if (lastPts >= 0 && pts > lastPts) frameMs = pts - lastPts
                lastDue = synchronized(lock) { pace.due(pts, ::wall) ?: lastDue }
                lastPts = pts
            }
        }

        /**
         * Waits until frame [pts] is due and puts it up; false when a command overtook it
         * (a restart drops it, a pause or a preroll holds it until it is wanted). A slowed
         * frame goes up one frame early and blends in across the gap — see the class comment.
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
                    if (mode == Mode.PREROLL) {
                        // Ready: this frame waits, unshown, for the leg that starts from it.
                        armedPts = pts
                        positionMs = pts
                        mode = Mode.IDLE
                    }
                    if (mode == Mode.IDLE) { lock.wait(); return@synchronized null to null }
                    // Null: an ease-out has reached its stop. It rests here, this frame held for the resume.
                    val due = pace.due(pts, ::wall) ?: run {
                        mode = Mode.IDLE
                        clock.pause(SystemClock.uptimeMillis())
                        return@synchronized null to null
                    }
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
            // Only the decoder putting frames up reaches the panel.
            if (active !== this) return
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
        /** A prerolled frame this close to where a leg is asked to start is where it starts. */
        const val ARM_SLACK_MS = 40L
        /** Within this of a clip's end a yoyo is already there. */
        const val END_SLACK_MS = 50L
    }
}
