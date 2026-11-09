package dev.kb896.aiusage.rear

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * A screenshot or recording of the back, made by the shell (RearUserService.capture) and handed
 * here open, copied into the gallery: Pictures/Screenshots, or Movies/Screen recordings, as the
 * phone's own land. The app's to do: an app adds to the media store without any permission
 * (scoped storage). Handed over open, not by a path: a file the shell wrote into this app's
 * external files was the shell's, and this app could not read it (2026-11-09).
 */
object CaptureStore {
    private const val TAG = "AiUsageRear"

    /** The last screenshot saved, and whether it is wanted gone (binned before its save was done). Under this object's lock. */
    private var lastShot: android.net.Uri? = null
    private var binLast = false

    /** The last screenshot out of the gallery, now or as soon as it is in. */
    fun deleteLastShot(context: Context) {
        val uri = synchronized(this) { lastShot.also { if (it == null) binLast = true; lastShot = null } } ?: return
        Thread({
            runCatching { context.applicationContext.contentResolver.delete(uri, null, null) }
                .onSuccess { Log.i(TAG, "capture: screenshot binned from the back") }
                .onFailure { Log.w(TAG, "capture: not binned: ${it.message}") }
        }, "capture-bin").apply { isDaemon = true; start() }
    }

    /** [file] into the gallery, off the caller's thread; closed after. */
    fun save(context: Context, file: android.os.ParcelFileDescriptor, video: Boolean) {
        Thread({
            runCatching { copy(context.applicationContext, file, video) }
                .onFailure { Log.w(TAG, "capture: not saved: ${it.message}") }
            runCatching { file.close() }
        }, "capture-store").apply { isDaemon = true; start() }
    }

    private fun copy(context: Context, file: android.os.ParcelFileDescriptor, video: Boolean) {
        val resolver = context.contentResolver
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Back_$stamp.${if (video) "mp4" else "png"}")
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/Screen recordings" else "Pictures/Screenshots")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("the media store took nothing")
        try {
            val copied = resolver.openOutputStream(uri)?.use { out ->
                java.io.FileInputStream(file.fileDescriptor).use { it.copyTo(out) }
            } ?: error("no stream")
            if (copied == 0L) error("nothing in it")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Log.i(TAG, "capture: ${if (video) "recording" else "screenshot"} of the back saved ($copied bytes)")
            if (!video) {
                // Binned on the back before it was in: out again at once.
                val bin = synchronized(this) { binLast.also { binLast = false; lastShot = if (it) null else uri } }
                if (bin) { resolver.delete(uri, null, null); Log.i(TAG, "capture: screenshot binned from the back") }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
