package dev.kb896.aiusage.ui.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * The panel's clip played backwards, as a clip of its own: what Yoyo's return and
 * Ping-pong play, since a video cannot be decoded backwards — each picture is built from
 * the ones before it.
 *
 * Stretch by stretch from the end: from a key frame to the next, the stretch is decoded
 * whole into memory (a quarter to a half second at the panel's size: a few MB), then fed to
 * the encoder last frame first, each at the time it has backwards. Silent, H.264, the
 * clip's own size. Runs on the thread it is called on; [cancelled] is asked between stretches.
 */
object ReverseExport {
    fun reversedUs(ptsUs: Long, lastUs: Long): Long = lastUs - ptsUs

    /** From each key frame to the next, the last stretch first. */
    fun stretchesBackwards(keyStartsUs: List<Long>): List<Pair<Long, Long>> =
        keyStartsUs.indices.reversed().map { i -> keyStartsUs[i] to (keyStartsUs.getOrNull(i + 1) ?: Long.MAX_VALUE) }

    /** True when [out] holds the reversed clip; on false it holds nothing worth keeping. */
    fun run(source: File, out: File, onProgress: (Int) -> Unit, cancelled: () -> Boolean): Boolean {
        val ex = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(source.path)
            val track = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)

            // Where each stretch starts, how many frames there are, and when the last one is.
            val keys = mutableListOf<Long>()
            var lastUs = 0L
            var frames = 0
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) keys += t
                lastUs = maxOf(lastUs, t)
                frames++
                ex.advance()
            }
            if (keys.isEmpty() || frames == 0) return false
            val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE)
            else maxOf(1, (frames * 1_000_000L / maxOf(1L, lastUs)).toInt())

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { decoder = it }
            dec.configure(format, null, null, 0)
            dec.start()

            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, maxOf(2_000_000, w * h * fps / 4))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { encoder = it }
            enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val sink = Sink(enc, mux)

            val info = MediaCodec.BufferInfo()
            var done = 0
            for ((from, to) in stretchesBackwards(keys)) {
                if (cancelled()) return false
                dec.flush()
                ex.seekTo(from, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val stretch = ArrayList<Pair<Long, ByteArray>>()
                var fed = false
                var drained = false
                while (!drained) {
                    if (!fed) {
                        val i = dec.dequeueInputBuffer(TICK_US)
                        if (i >= 0) {
                            val t = ex.sampleTime
                            val nextKey = t >= 0 && t != from && ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                            if (t < 0 || nextKey) {
                                dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                fed = true
                            } else {
                                val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                                dec.queueInputBuffer(i, 0, n, t, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = dec.dequeueOutputBuffer(info, TICK_US)
                    if (o < 0) continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) drained = true
                    if (info.size > 0 && info.presentationTimeUs >= from && info.presentationTimeUs < to) {
                        dec.getOutputImage(o)?.use { img ->
                            val bytes = ByteArray(Yuv.size(w, h))
                            Yuv.pack(img.planes.map { Yuv.Plane(it.buffer, it.rowStride, it.pixelStride) }, w, h, bytes)
                            stretch += info.presentationTimeUs to bytes
                        }
                    }
                    dec.releaseOutputBuffer(o, false)
                }
                for ((pts, bytes) in stretch.sortedByDescending { it.first }) {
                    sink.encode(bytes, w, h, reversedUs(pts, lastUs))
                    done++
                }
                onProgress((100 * done / frames).coerceIn(0, 100))
            }
            sink.finish(lastUs + 1)
            return true
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { muxer?.stop() }; runCatching { muxer?.release() }
            ex.release()
        }
    }

    /** The encoder and the file it writes, fed one frame at a time and drained as it goes. */
    private class Sink(private val enc: MediaCodec, private val mux: MediaMuxer) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1

        fun encode(bytes: ByteArray, w: Int, h: Int, ptsUs: Long) {
            while (true) {
                val i = enc.dequeueInputBuffer(TICK_US)
                if (i >= 0) {
                    val img = enc.getInputImage(i)!!
                    Yuv.unpack(bytes, w, h, img.planes.map { Yuv.Plane(it.buffer, it.rowStride, it.pixelStride) })
                    enc.queueInputBuffer(i, 0, Yuv.size(w, h), ptsUs, 0)
                    drain(false)
                    return
                }
                drain(false)
            }
        }

        fun finish(ptsUs: Long) {
            while (true) {
                val i = enc.dequeueInputBuffer(TICK_US)
                if (i >= 0) { enc.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
                drain(false)
            }
            drain(true)
        }

        private fun drain(toEnd: Boolean) {
            while (true) {
                val o = enc.dequeueOutputBuffer(info, if (toEnd) TICK_US else 0)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(enc.outputFormat); mux.start() }
                    o >= 0 -> {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            mux.writeSampleData(track, enc.getOutputBuffer(o)!!, info)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (eos) return
                    }
                    !toEnd -> return
                }
            }
        }
    }

    private const val TICK_US = 10_000L
}
