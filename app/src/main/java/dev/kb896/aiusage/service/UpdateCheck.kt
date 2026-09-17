package dev.kb896.aiusage.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.kb896.aiusage.BuildConfig
import dev.kb896.aiusage.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Once a day, ask GitLab for the newest release. If it is newer than this build, say so
 * once, in a notification that carries the release notes and opens the APK's permanent
 * address — the same one the site's buttons use. Nothing is downloaded by the app itself;
 * the browser does that, and the user installs.
 *
 * A dev build compares by its base version: 0.7.4-dev is the work after 0.7.3, so a
 * 0.7.4 release does not nag it, and a 0.7.5 does.
 */
object UpdateCheck {
    private const val TAG = "AiUsageUpdate"
    private const val PREFS = "update_check"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 4711
    private const val EVERY_MS = 24L * 60 * 60 * 1000
    private const val API = "https://gitlab.com/api/v4/projects/85495687/releases?per_page=1"
    const val APK = "https://gitlab.com/896kb/ai-usage-companion/-/releases/permalink/latest/downloads/ai-usage-companion.apk"
    const val RELEASES = "https://gitlab.com/896kb/ai-usage-companion/-/releases"

    data class Release(val tag: String, val notes: String)

    private val client by lazy { OkHttpClient() }

    /** The daily check, from the poll loop. Cheap when it is not time yet. */
    suspend fun maybe(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < EVERY_MS) return
        prefs.edit().putLong("last_check", now).apply()
        val release = withContext(Dispatchers.IO) {
            runCatching { fetch() }.onFailure { Log.w(TAG, "check failed: ${it.message}") }.getOrNull()
        } ?: return
        if (!newer(release.tag, BuildConfig.VERSION_NAME)) return
        if (prefs.getString("notified", "") == release.tag) return
        prefs.edit().putString("notified", release.tag).putString("notes", release.notes).apply()
        notify(context, release)
    }

    /** The release the check last found, while it is still newer than this build; for the settings screen's row. */
    fun available(context: Context): Release? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString("notified", "").orEmpty()
        if (tag.isEmpty() || !newer(tag, BuildConfig.VERSION_NAME)) return null
        return Release(tag, prefs.getString("notes", "").orEmpty())
    }

    fun fetch(): Release? {
        val request = Request.Builder().url(API).header("User-Agent", dev.kb896.aiusage.data.Agent.VALUE).build()
        val body = client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null
        val first = Json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return null
        val tag = first["tag_name"]?.jsonPrimitive?.content ?: return null
        val notes = first["description"]?.jsonPrimitive?.content.orEmpty()
        return Release(tag, notes)
    }

    /** "v0.7.4" against "0.7.3" or "0.7.4-dev": numerically, by the base version. */
    fun newer(remoteTag: String, local: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val r = parts(remoteTag)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** The release notes as plain text: the markdown that reads badly in a notification stripped. */
    fun plain(notes: String): String = notes
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("^#+[ \\t]*", RegexOption.MULTILINE), "")
        .replace(Regex("^[ \\t]*[-*][ \\t]+", RegexOption.MULTILINE), "• ")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .trim()

    private fun notify(context: Context, release: Release) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A new release of this app, once, with its notes." },
        )
        fun open(url: String, code: Int): PendingIntent = PendingIntent.getActivity(
            context, code, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val version = release.tag.removePrefix("v")
        val text = plain(release.notes).ifBlank { "Tap to download." }
        val n = Notification.Builder(context, CHANNEL)
            .setContentTitle("AI Usage $version is out — you have ${BuildConfig.VERSION_NAME}")
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open(APK, 1))
            .addAction(Notification.Action.Builder(null, "Download APK", open(APK, 1)).build())
            .addAction(Notification.Action.Builder(null, "All releases", open(RELEASES, 2)).build())
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIFICATION_ID, n)
        Log.i(TAG, "told about ${release.tag}")
    }
}
