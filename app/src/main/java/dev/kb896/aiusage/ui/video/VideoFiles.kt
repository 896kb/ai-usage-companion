package dev.kb896.aiusage.ui.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * Where a video background lives. The original stays where the user keeps it — the app
 * holds a lasting read permission on its URI — and the clip made from it is the app's own
 * file. Every export writes a NEW file: the back reloads a clip when its path changes,
 * and a clip rewritten in place would never be reloaded.
 */
object VideoFiles {
    private fun dir(context: Context) = File(context.filesDir, "video").apply { mkdirs() }

    fun newClip(context: Context): File = File(dir(context), "clip-${System.nanoTime()}.mp4")

    /** Deletes every clip but [keep] — the ones the config now names. */
    fun keepOnly(context: Context, vararg keep: String?) {
        dir(context).listFiles().orEmpty().filter { it.path !in keep }.forEach { it.delete() }
    }

    fun discard(file: File) { file.delete() }

    /** Keeps the read permission on a picked original across restarts. */
    fun hold(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun canOpen(context: Context, uri: String?): Boolean = uri != null && runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)
}
