package dev.kb896.aiusage.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PortalApps
import dev.kb896.aiusage.rear.PortalAppsSnapshot
import dev.kb896.aiusage.rear.PortalSheet
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Loads the pinned apps' labels and icons in the main app's process and hands them to
 * [send] (the shell service's setPortalApps, via the bridge). Everything runs on one
 * background thread; [refresh] is cheap enough to call on every config write, because a
 * load that would equal the last one sent (same apps, same install times, same language) sends nothing.
 * The bridge re-sends the last bundle itself when the shell service reconnects. A send that
 * failed ([send] false: a oneway call too big for the binder's room just then) is not
 * counted as sent, and is tried again after [RETRY_MS].
 */
class PortalAppsLoader(private val context: Context, private val send: (android.os.Bundle) -> Boolean) {

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "portal-apps").apply { isDaemon = true } }
    private var lastSignature: List<String>? = null
    /** A retry is waiting. On the worker. */
    private var retrying = false

    fun refresh() {
        runCatching { worker.execute { runCatching { load() }.onFailure { Log.w(TAG, "load: ${it.message}") } } }
    }

    fun stop() { worker.shutdownNow() }

    private fun load() {
        val pm = context.packageManager
        val infos = shown(pm, ConfigStore(context).load().portalApps)
        val shown = infos.map { it.first }
        // The locale too: the labels are in the language they were loaded in.
        val signature = listOf(context.resources.configuration.locales.toLanguageTags()) + shown.map { pkg ->
            "$pkg@${runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)}"
        }
        if (signature == lastSignature) return
        val read = read(pm, infos)
        val labels = read.map { it.label }
        val bitmaps = read.map { it.icon }
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
        if (send(PortalAppsSnapshot.of(shown, labels, bytes).toBundle())) {
            lastSignature = signature
        } else if (!retrying) {
            retrying = true
            runCatching {
                worker.schedule({ retrying = false; runCatching { load() }.onFailure { Log.w(TAG, "load: ${it.message}") } }, RETRY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun encode(bmp: Bitmap?, quality: Int): ByteArray? = bmp?.let {
        runCatching { ByteArrayOutputStream().also { out -> it.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out) }.toByteArray() }.getOrNull()
    }

    /** A pinned app as the sheet shows it: its [label] and its [icon] at [ICON_PX] square. */
    class Shown(val pkg: String, val label: String, val icon: Bitmap?)

    companion object {
        private const val TAG = "PortalApps"
        private const val ICON_PX = 96

        /**
         * Of [pinned], the apps the sheet shows: those still installed, in order, no more
         * than PortalSheet.MAX_APPS, each with its ApplicationInfo. The front's portal
         * preview asks the same, so it shows what the back does.
         */
        fun shown(pm: PackageManager, pinned: List<String>): List<Pair<String, ApplicationInfo>> {
            val infos = pinned.mapNotNull { pkg ->
                runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()?.let { pkg to it }
            }.toMap()
            return PortalApps.present(pinned, infos.keys).take(PortalSheet.MAX_APPS).map { it to infos.getValue(it) }
        }

        /** The labels and icons of [apps] (from [shown]), in their order; a label that will not load is the package. */
        fun read(pm: PackageManager, apps: List<Pair<String, ApplicationInfo>>): List<Shown> = apps.map { (pkg, info) ->
            Shown(
                pkg,
                runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg),
                runCatching { pm.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX) }.getOrNull(),
            )
        }

        private const val QUALITY = 90
        private const val QUALITY_TIGHT = 60
        /** After a failed send, the next try. */
        private const val RETRY_MS = 5_000L
    }
}
