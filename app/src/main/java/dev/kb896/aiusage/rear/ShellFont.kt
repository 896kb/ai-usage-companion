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
 *    a release build too: R8 shortens resource paths there (the font is `res/7g.otf` in
 *    the release APK), and only the resource table knows which entry it is.
 * 2. The APK as a zip, at [ENTRY] — what a debug build keeps. The APK is the Context's,
 *    else the first .apk on the class path (the user service is started with our APK as
 *    its class path), else `pm path`'s.
 *
 * Null if neither gives a font; the caller then opens no sheet.
 */
internal object ShellFont {

    const val ENTRY = "res/font/departure_mono.otf"
    private const val TAG = "AiUsageRear"

    fun load(context: Context?, pmPath: () -> String): Typeface? {
        val bytes = fromResources(context)
            ?: apkPath(context?.packageCodePath, System.getProperty("java.class.path"), pmPath)?.let { fromApk(it) }
        if (bytes == null) {
            Log.w(TAG, "portal sheet: the panel's font is not readable — no sheet")
            return null
        }
        return try {
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
            Typeface.CustomFallbackBuilder(FontFamily.Builder(Font.Builder(buffer).build()).build()).build()
        } catch (e: Throwable) {
            Log.w(TAG, "portal sheet: the panel's font did not build — no sheet: ${e.message}")
            null
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
     * Our APK: [codePath] if known, else the first `.apk` on [classPath], else the base APK
     * in [pmPath]'s `package:` lines (asked only when needed).
     */
    fun apkPath(codePath: String?, classPath: String?, pmPath: () -> String): String? {
        codePath?.takeIf { it.endsWith(".apk") }?.let { return it }
        classPath?.split(':')?.firstOrNull { it.endsWith(".apk") }?.let { return it }
        val apks = pmPath().lines().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }.filter { it.endsWith(".apk") }
        return apks.firstOrNull { it.endsWith("/base.apk") } ?: apks.firstOrNull()
    }
}
