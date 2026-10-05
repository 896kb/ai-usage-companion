package dev.kb896.aiusage.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Copies a chosen image into the app's own storage, downscaled.
 *
 * COPYING, not remembering a URI. A picked content:// URI is a grant, and grants expire —
 * they do not survive a reboot without persistable permission, and the provider that
 * issued one can revoke it or vanish with the app that owns it. The panel is redrawn
 * every few seconds forever; it cannot depend on another app still being installed and
 * still willing.
 *
 * DOWNSCALED, because the source is a camera photo and the destination is a 278x70 strip
 * on a 904x572 display. Decoding a 12-megapixel JPEG into the :rear process to draw it
 * at postage-stamp size is how a panel that must never die gets killed for memory.
 */
object ArtImport {

    /**
     * Which picture this is. Each has a file of its own and choosing a new one replaces
     * only that slot — a background and a maker's mark are unrelated decisions, and
     * sharing a filename would make setting one silently destroy the other.
     */
    enum class Slot(val file: String) {
        ART("panel-art.png"),
        BACKGROUND("panel-background.png"),
        /** A background picked and not yet cropped — see [promote]. */
        BACKGROUND_PENDING("panel-background-pending.png"),
    }

    /**
     * Longest edge kept, in pixels.
     *
     * The art region is about 278x70 panel units and zoom goes to 4x, so 1024 is already
     * generous — beyond it there is no visible detail to gain, only memory to lose. A
     * background covers the whole 904-unit panel, which is still well inside it.
     */
    private const val MAX_EDGE = 1024

    /**
     * @return the absolute path of the stored copy, or null if the image could not be
     *   read or decoded. Null must leave any existing art alone: a failed pick should not
     *   also destroy what was already there.
     */
    fun store(context: Context, uri: Uri, slot: Slot = Slot.ART): String? {
        val bitmap = decodeScaled(context, uri) ?: return null
        val file = File(context.filesDir, slot.file)
        return try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.absolutePath
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    fun clear(context: Context, slot: Slot = Slot.ART) {
        File(context.filesDir, slot.file).delete()
    }

    /**
     * Moves the picture in [from] into [to], replacing what is there; its path, or null.
     *
     * A picked background waits in a slot of its own until its crop is accepted. Written
     * straight into the background's file, it replaced the panel's picture the moment it
     * was picked — the panel reloads a file whose date changes — so Cancel left it set.
     */
    fun promote(context: Context, from: Slot, to: Slot): String? {
        val source = File(context.filesDir, from.file)
        val target = File(context.filesDir, to.file)
        if (!source.exists()) return null
        if (!source.renameTo(target)) {
            if (runCatching { source.copyTo(target, overwrite = true) }.isFailure) return null
            source.delete()
        }
        return target.absolutePath
    }

    /**
     * Two passes: measure, then decode with a sample size.
     *
     * inSampleSize only takes powers of two, so this lands at or under MAX_EDGE rather
     * than exactly on it. That is the point — the cheap decode is the one that never
     * allocates the full-size bitmap at all, and an exact fit is not worth a second
     * scaling pass for artwork this small on screen.
     */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        } catch (_: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            context.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }
}
