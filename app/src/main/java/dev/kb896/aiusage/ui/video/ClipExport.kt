package dev.kb896.aiusage.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import dev.kb896.aiusage.rear.GlassLuminance
import java.io.File

/**
 * Cuts the panel's clip from the original: the trimmed range, the editor's crop, scaled to
 * the rear display, no audio, H.264, a key frame every half second — then its reversed copy
 * (ReverseExport), which the short stretches between key frames keep quick and small in
 * memory. Transformer runs on the main looper, as it requires; the reversing on a thread of
 * its own. One progress for both, polled every 100 ms. A cancel or a failure deletes what
 * was written. The clip without its reversed copy is still a clip: if only the reversing
 * fails, the clip is kept and plays forward.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ClipExport(private val context: Context) {
    private var transformer: Transformer? = null
    private var output: File? = null
    private var reversed: File? = null
    @Volatile private var cancelled = false
    private val main = Handler(Looper.getMainLooper())

    fun start(
        source: Uri, startMs: Long, endMs: Long, crop: FloatArray,
        onProgress: (Int) -> Unit, onDone: (File, File?, List<Float>) -> Unit, onError: (String) -> Unit,
    ) {
        cancelled = false
        val (w, h) = RearSize.pixels(context)
        val out = VideoFiles.newClip(context).also { output = it }
        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(source).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
            ).build(),
        )
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(
                Crop(crop[0], crop[1], crop[2], crop[3]),
                Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT),
            )))
            .build()
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setiFrameIntervalSeconds(0.5f).build())
            .build()
        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoders)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish()
                    reverse(out, onProgress, onDone)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finish()
                    VideoFiles.discard(out)
                    onError(exportException.message ?: "The video could not be processed")
                }
            })
            .build()
        transformer = t
        // HDR made SDR: left as HDR, Media3 falls back to 10-bit HEVC when there is no H.264
        // HDR encoder, and the panel draws frames as sRGB — a phone's HDR clip came out washed.
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        t.start(composition, out.path)
        val holder = ProgressHolder()
        main.post(object : Runnable {
            override fun run() {
                val now = transformer ?: return
                if (now.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress * FORWARD_SHARE / 100)
                main.postDelayed(this, PROGRESS_MS)
            }
        })
    }

    /** The clip played backwards, on a thread of its own; then everything back on main. */
    private fun reverse(clip: File, onProgress: (Int) -> Unit, onDone: (File, File?, List<Float>) -> Unit) {
        val back = VideoFiles.newClip(context).also { reversed = it }
        Thread {
            val ok = ReverseExport.run(clip, back,
                onProgress = { p -> main.post { if (!cancelled) onProgress(FORWARD_SHARE + p * (100 - FORWARD_SHARE) / 100) } },
                cancelled = { cancelled },
            )
            val cells = luminance(clip)
            main.post {
                if (cancelled) return@post
                if (!ok) VideoFiles.discard(back)
                onDone(clip, back.takeIf { ok }, cells)
            }
        }.start()
    }

    fun cancel() {
        cancelled = true
        transformer?.cancel()
        finish()
        output?.let(VideoFiles::discard)
        reversed?.let(VideoFiles::discard)
    }

    private fun finish() {
        transformer = null
        main.removeCallbacksAndMessages(null)
    }

    /** The clip's middle frame, in the panel's brightness cells. */
    private fun luminance(clip: File): List<Float> = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(clip.path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            // Microseconds: half the duration in ms, times a thousand.
            val frame = r.getFrameAtTime(ms * 500, MediaMetadataRetriever.OPTION_CLOSEST) ?: return emptyList()
            val side = GlassLuminance.SIDE
            val small = Bitmap.createScaledBitmap(frame, side, side, true)
            List(side * side) { GlassLuminance.of(small.getPixel(it % side, it / side)) }
        } finally {
            r.release()
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val PROGRESS_MS = 100L
        /** The forward cut's share of the progress; the reversing is the rest. */
        const val FORWARD_SHARE = 70
    }
}
