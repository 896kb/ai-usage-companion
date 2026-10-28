package dev.kb896.aiusage.rear

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.util.Log
import dev.kb896.aiusage.R
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * The panel's font (res/font/departure_mono.otf) for the shell's sheet. The shell service
 * runs in app_process, which has NO default typeface — text drawn without one aborts
 * (gDefaultTypeface == nullptr) — so the font is read out of our own APK and built with
 * Font.Builder, which throws on a bad font where Typeface.Builder would quietly fall back
 * to the default that is not there. Never Typeface.DEFAULT, for the same reason.
 *
 * Two ways to the bytes, in order:
 * 1. Through Resources, when Shizuku handed the service a Context of our package (its
 *    UserService constructor with a Context, Shizuku 13+). This is the one that works for
 *    a release build too: AGP shortens resource paths when it shrinks resources (the font
 *    is `res/7g.otf` in the release APK), and only the resource table knows which entry it is.
 * 2. Our APK as a zip, at [ENTRY] — what a debug build keeps. Each candidate APK is tried
 *    in turn until one HAS the font: the Context's code path, then `pm path`'s (base APK
 *    first). Not the class path: the user service runs in Shizuku's process, whose class
 *    path is Shizuku's own APK.
 *
 * Null if neither gives a font; the caller then opens no sheet.
 */
internal object ShellFont {

    const val ENTRY = "res/font/departure_mono.otf"
    private const val TAG = "AiUsageRear"

    fun load(context: Context?, pmPath: () -> String): Typeface? {
        val bytes = fromResources(context)
            ?: apkCandidates(context?.packageCodePath, pmPath).firstNotNullOfOrNull { fromApk(it) }
        if (bytes == null) {
            Log.w(TAG, "portal sheet: the panel's font is not readable — no sheet")
            return null
        }
        return try {
            systemFonts()
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
            Typeface.CustomFallbackBuilder(FontFamily.Builder(Font.Builder(buffer).build()).build()).build()
        } catch (e: Throwable) {
            Log.w(TAG, "portal sheet: the panel's font did not build — no sheet: ${e.message}")
            null
        }
    }

    /**
     * Typeface's own state, which every app gets from the zygote and app_process never does:
     * without it CustomFallbackBuilder.build() throws "The Typeface is not fully initialized"
     * (measured on the 17 Pro, OS4.0.5.0). The hidden loadPreinstalledSystemFontMap() is what
     * the zygote calls; once is enough, and a firmware without it fails the build below as
     * before, with no sheet.
     */
    @Volatile private var systemFontsLoaded = false

    private fun systemFonts() {
        if (systemFontsLoaded) return
        synchronized(this) {
            if (systemFontsLoaded) return
            runCatching { Typeface::class.java.getMethod("loadPreinstalledSystemFontMap").invoke(null) }
                .onFailure { Log.w(TAG, "portal sheet: system fonts not loaded: ${it.message}") }
            systemFontsLoaded = true
        }
    }

    private fun fromResources(context: Context?): ByteArray? = context?.let { c ->
        runCatching { c.resources.openRawResource(R.font.departure_mono).use { it.readBytes() } }
            .onFailure { Log.i(TAG, "portal sheet: font not in our resources (${it.message}) — reading the APK") }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun fromApk(path: String): ByteArray? = runCatching {
        ZipFile(path).use { zip -> zip.getEntry(ENTRY)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }
    }.onFailure { Log.w(TAG, "portal sheet: $path unreadable: ${it.message}") }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Our APKs to look in, in order: [codePath] if it is one, then the `package:` lines of
     * [pmPath] with the base APK before any split. Lazy — `pm path` is asked only if the
     * code path did not have the font.
     */
    fun apkCandidates(codePath: String?, pmPath: () -> String): Sequence<String> = sequence {
        codePath?.takeIf { it.endsWith(".apk") }?.let { yield(it) }
        val apks = pmPath().lines().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }.filter { it.endsWith(".apk") && it != codePath }
        yieldAll(apks.sortedBy { if (it.endsWith("/base.apk")) 0 else 1 })
    }
}
