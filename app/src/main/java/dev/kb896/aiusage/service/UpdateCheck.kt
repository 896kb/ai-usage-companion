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
 * Every two hours, ask GitLab for the newest release. If it is newer than this build, say so
 * once, in a notification that carries the release notes; the settings screen also shows it
 * at the top (UpdateCard). Both update through the 896 Store when it is on the phone, else
 * offer to get the store or the APK's permanent address — the same one the site's buttons
 * use. Nothing is downloaded by the app itself.
 *
 * A dev build compares by its base version: 0.7.4-dev is the work after 0.7.3, so a
 * 0.7.4 release does not nag it, and a 0.7.5 does.
 */
object UpdateCheck {
    private const val TAG = "AiUsageUpdate"
    private const val PREFS = "update_check"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 4711
    private const val EVERY_MS = 2L * 60 * 60 * 1000
    private const val API = "https://gitlab.com/api/v4/projects/85495687/releases?per_page=1"
    const val APK = "https://gitlab.com/896kb/ai-usage-companion/-/releases/permalink/latest/downloads/ai-usage-companion.apk"
    /** The 896 Store, and the site to get it from. */
    const val STORE = "dev.kb896.store"
    const val STORE_SITE = "https://store.896.ro/"

    data class Release(val tag: String, val notes: String)

    private val client by lazy { OkHttpClient() }

    /**
     * The store's page for this app, starting the update; a store too old for the link just
     * opens. Null without the store.
     */
    fun inStore(context: Context): Intent? {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("kb896store://app/${context.packageName}?update=1")).setPackage(STORE)
        return if (link.resolveActivity(context.packageManager) != null) link
        else context.packageManager.getLaunchIntentForPackage(STORE)
    }

    fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    fun version(context: Context): String = BuildConfig.VERSION_NAME

    /**
     * The two-hourly check, from the poll loop and the settings screen. Cheap when it is not
     * time yet. True when it found a newer release than this build.
     */
    suspend fun maybe(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < EVERY_MS) return false
        prefs.edit().putLong("last_check", now).apply()
        val release = withContext(Dispatchers.IO) {
            runCatching { fetch() }.onFailure { Log.w(TAG, "check failed: ${it.message}") }.getOrNull()
        } ?: return false
        if (!newer(release.tag, BuildConfig.VERSION_NAME)) return false
        val told = prefs.getString("notified", "") == release.tag
        // The notes again even when told: an edited release reads right on the card.
        prefs.edit().putString("notified", release.tag).putString("notes", release.notes).apply()
        if (!told) notify(context, release)
        return true
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
        fun open(intent: Intent, code: Int): PendingIntent = PendingIntent.getActivity(
            context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // As the card: the store first, and with it on the phone the only way.
        val link = inStore(context)
        val store = open(link ?: view(STORE_SITE), 3)
        val version = release.tag.removePrefix("v")
        val text = plain(release.notes).ifBlank { "Tap to update." }
        val n = Notification.Builder(context, CHANNEL)
            .setContentTitle("AI Usage $version is out — you have ${BuildConfig.VERSION_NAME}")
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(store)
            .addAction(Notification.Action.Builder(null, "Update via 896 Store", store).build())
            .apply { if (link == null) addAction(Notification.Action.Builder(null, "Update", open(view(APK), 1)).build()) }
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIFICATION_ID, n)
        Log.i(TAG, "told about ${release.tag}")
    }
}
