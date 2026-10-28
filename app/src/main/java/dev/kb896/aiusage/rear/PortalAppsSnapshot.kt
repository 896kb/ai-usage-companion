package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle

/**
 * The pinned apps as the shell's sheet needs them: package, label, icon, in grid order.
 * Sent from the main app as a [Bundle] ([toBundle]) and stored in the shell service as
 * received ([fromBundle]); icons stay compressed bytes (WebP, or PNG from an older app —
 * BitmapFactory reads either) until [icon] is first asked for, then are
 * decoded once and kept. Immutable except for that cache.
 */
class PortalAppsSnapshot(
    val packages: List<String>,
    val labels: List<String>,
    private val pngs: List<ByteArray?>,
) {
    private val decoded = arrayOfNulls<Bitmap>(packages.size)
    private val tried = BooleanArray(packages.size)

    val size get() = packages.size

    /** The icon at [i], or null if none came or it would not decode. */
    @Synchronized
    fun icon(i: Int): Bitmap? {
        if (i !in packages.indices) return null
        if (!tried[i]) {
            tried[i] = true
            decoded[i] = pngs[i]?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        }
        return decoded[i]
    }

    fun toBundle(): Bundle = Bundle().apply {
        putStringArray(KEY_PACKAGES, packages.toTypedArray())
        putStringArray(KEY_LABELS, labels.toTypedArray())
        packages.forEachIndexed { i, pkg -> pngs[i]?.let { putByteArray(iconKey(pkg), it) } }
    }

    companion object {
        const val KEY_PACKAGES = "packages"
        const val KEY_LABELS = "labels"
        fun iconKey(pkg: String) = "icon:$pkg"

        /** The most icon bytes one bundle carries: well inside the binder's half megabyte for oneway calls. */
        const val ICON_BUDGET = 300 * 1024

        /** [icons] in order while their sizes add up to no more than [budget]; every one after that null. */
        fun withinBudget(icons: List<ByteArray?>, budget: Int): List<ByteArray?> {
            var total = 0
            var full = false
            return icons.map { b ->
                if (b == null || full) return@map null
                if (total + b.size > budget) { full = true; return@map null }
                total += b.size
                b
            }
        }

        val EMPTY = PortalAppsSnapshot(emptyList(), emptyList(), emptyList())

        /**
         * From parallel lists, de-duplicated and capped at [PortalSheet.MAX_APPS]; a missing
         * label becomes the package name, a missing icon is null.
         */
        fun of(packages: List<String>, labels: List<String>, pngs: Map<String, ByteArray>): PortalAppsSnapshot {
            val pkgs = packages.distinct().take(PortalSheet.MAX_APPS)
            return PortalAppsSnapshot(
                pkgs,
                pkgs.map { p -> labels.getOrNull(packages.indexOf(p)) ?: p },
                pkgs.map { pngs[it] },
            )
        }

        fun fromBundle(b: Bundle?): PortalAppsSnapshot {
            if (b == null) return EMPTY
            val pkgs = b.getStringArray(KEY_PACKAGES)?.toList() ?: return EMPTY
            val labels = b.getStringArray(KEY_LABELS)?.toList().orEmpty()
            return of(pkgs, labels, pkgs.mapNotNull { p -> b.getByteArray(iconKey(p))?.let { p to it } }.toMap())
        }
    }
}
