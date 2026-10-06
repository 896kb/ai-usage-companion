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
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import dev.kb896.aiusage.rear.GlassLuminance
import java.io.File

/**
 * Cuts the panel's clip from the original: the trimmed range, the editor's crop, scaled to
 * the rear display, no audio, H.264. Runs on the main looper, as Transformer requires;
 * progress is polled every 100 ms. A cancel or a failure deletes the partial file.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ClipExport(private val context: Context) {
    private var transformer: Transformer? = null
    private var output: File? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(
        source: Uri, startMs: Long, endMs: Long, crop: FloatArray,
        onProgress: (Int) -> Unit, onDone: (File, List<Float>) -> Unit, onError: (String) -> Unit,
    ) {
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
        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish()
                    onDone(out, luminance(out))
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finish()
                    VideoFiles.discard(out)
                    onError(exportException.message ?: "The video could not be processed")
                }
            })
            .build()
        transformer = t
        t.start(item, out.path)
        val holder = ProgressHolder()
        main.post(object : Runnable {
            override fun run() {
                val now = transformer ?: return
                if (now.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                main.postDelayed(this, PROGRESS_MS)
            }
        })
    }

    fun cancel() {
        transformer?.cancel()
        finish()
        output?.let(VideoFiles::discard)
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
    }
}
