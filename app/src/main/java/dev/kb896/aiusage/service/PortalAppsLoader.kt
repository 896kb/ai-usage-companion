package dev.kb896.aiusage.service

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PortalApps
import dev.kb896.aiusage.rear.PortalAppsSnapshot
import dev.kb896.aiusage.rear.PortalSheet
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Loads the pinned apps' labels and icons in the main app's process and hands them to
 * [send] (the shell service's setPortalApps, via the bridge). Everything runs on one
 * background thread; [refresh] is cheap enough to call on every config write, because a
 * load that would equal the last one sent (same apps, same install times, same language) sends nothing.
 * The bridge re-sends the last bundle itself when the shell service reconnects.
 */
class PortalAppsLoader(private val context: Context, private val send: (android.os.Bundle) -> Unit) {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "portal-apps").apply { isDaemon = true } }
    private var lastSignature: List<String>? = null

    fun refresh() {
        runCatching { worker.execute { runCatching { load() }.onFailure { Log.w(TAG, "load: ${it.message}") } } }
    }

    fun stop() { worker.shutdownNow() }

    private fun load() {
        val pm = context.packageManager
        val pinned = ConfigStore(context).load().portalApps
        val infos = pinned.mapNotNull { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()?.let { pkg to it }
        }.toMap()
        val shown = PortalApps.present(pinned, infos.keys).take(PortalSheet.MAX_APPS)
        // The locale too: the labels are in the language they were loaded in.
        val signature = listOf(context.resources.configuration.locales.toLanguageTags()) + shown.map { pkg ->
            "$pkg@${runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)}"
        }
        if (signature == lastSignature) return
        val labels = shown.map { pkg -> runCatching { infos.getValue(pkg).loadLabel(pm).toString() }.getOrDefault(pkg) }
        val bitmaps = shown.map { pkg -> runCatching { pm.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX) }.getOrNull() }
        // Lossy WebP, not PNG: up to 48 icons go in one oneway call, and the shell's binder
        // has half a megabyte for every oneway call in flight. Measured on 37 desktop app
        // icons scaled to 96 px (libpng/libwebp, not the phone's encoder): PNG 8.9 KB each on
        // average (48 of them 416 KB; 668 KB were all as big as the largest), WebP at 90
        // 3.5 KB (164 KB; 280 KB). Over the budget anyway: once more at a lower quality, then
        // the icons past the budget go without (their names still show).
        var icons = bitmaps.map { encode(it, QUALITY) }
        if (icons.sumOf { it?.size ?: 0 } > PortalAppsSnapshot.ICON_BUDGET) icons = bitmaps.map { encode(it, QUALITY_TIGHT) }
        icons = PortalAppsSnapshot.withinBudget(icons, PortalAppsSnapshot.ICON_BUDGET)
        val bytes = shown.indices.mapNotNull { i -> icons[i]?.let { shown[i] to it } }.toMap()
        send(PortalAppsSnapshot.of(shown, labels, bytes).toBundle())
        lastSignature = signature
    }

    private fun encode(bmp: Bitmap?, quality: Int): ByteArray? = bmp?.let {
        runCatching { ByteArrayOutputStream().also { out -> it.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out) }.toByteArray() }.getOrNull()
    }

    private companion object {
        const val TAG = "PortalApps"
        const val ICON_PX = 96
        const val QUALITY = 90
        const val QUALITY_TIGHT = 60
    }
}
