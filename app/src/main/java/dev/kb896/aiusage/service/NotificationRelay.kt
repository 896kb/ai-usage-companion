package dev.kb896.aiusage.service

import android.content.Intent
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.rear.RearActivity

/**
 * Passes other apps' notifications to the rear panel.
 *
 * This is the same mechanism KDE Connect uses to mirror notifications to a desktop, and
 * the same one the phone's other rear-screen tools use — both were already holding
 * notification access on this device when the feature was asked for. It needs the user
 * to grant it in Settings, which cannot be done from code and is the whole cost of the
 * feature.
 *
 * What crosses to the panel is deliberately small: the app's label, a title and one line
 * of text. Not the icon, not the actions, not the extras bundle. The panel draws a strip
 * of pixels on the back of a phone — it can hold a sentence, and anything more would be
 * collected for nothing.
 *
 * PRIVACY, and it is the reason this is off by default and behind its own switch: this
 * display faces AWAY from its owner. Everything it shows is shown to whoever is behind
 * them and not to them. A message that would be private on the front screen is on
 * display here, so the content line is a separate opt-in from the feature itself.
 */
class NotificationRelay : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val config = ConfigStore(this).load()
        if (!config.showNotifications) return

        // Ours would be a loop: the poll service's own ongoing notification is posted
        // continuously, and relaying it would put the panel's own readings back onto the
        // panel once a minute.
        if (sbn.packageName == packageName) return
        // Ongoing notifications are the state of something rather than an event — a
        // media player, a download, another app's foreground service. They are posted
        // and re-posted constantly and none of them is news.
        if (sbn.isOngoing) return
        if (!sbn.isClearable) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        val app = appLabel(sbn.packageName)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // The body only when it was asked for separately. See the class note: the back of
        // the phone is the one screen its owner cannot see and everyone else can.
        val body = if (config.showNotificationText) text else null

        // The same notification arrives more than once, and not rarely: a single post
        // measured TWO onNotificationPosted calls two milliseconds apart. The system
        // re-delivers on ranking changes and on any update to a notification already
        // showing, and neither is news — but the panel treated each as a fresh arrival
        // and turned the wheel, so every message replaced itself with a copy of itself
        // the instant it appeared.
        //
        // Keyed on the CONTENT rather than on sbn.key alone: an app that genuinely edits
        // a notification in place — a download percentage, a message being corrected —
        // keeps the same key, and that IS worth showing again.
        val fingerprint = "${sbn.key}\u0000$title\u0000$body"
        if (fingerprint == lastFingerprint && SystemClock.elapsedRealtime() - lastAt < DEDUPE_MS) {
            return
        }
        lastFingerprint = fingerprint
        lastAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "relaying a notification from ${sbn.packageName}")
        sendBroadcast(
            Intent(RearActivity.ACTION_NOTIFICATION)
                .setPackage(packageName)
                .putExtra(RearActivity.EXTRA_NOTIFICATION_APP, app)
                // The package, not the icon itself. :rear can ask PackageManager for a
                // drawable; putting a Bitmap through a broadcast would copy it across
                // the process boundary on every notification for no gain.
                .putExtra(RearActivity.EXTRA_NOTIFICATION_PKG, sbn.packageName)
                .putExtra(RearActivity.EXTRA_NOTIFICATION_TITLE, title.orEmpty())
                .putExtra(RearActivity.EXTRA_NOTIFICATION_TEXT, body.orEmpty()),
        )
    }

    /**
     * The app's human name, falling back to its package.
     *
     * A package name is not nothing — it identifies the sender — and showing
     * "com.whatsapp" beats showing a blank where an app should be.
     */
    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** The last thing relayed, and when — see the de-duplication above. */
    private var lastFingerprint: String? = null
    private var lastAt = 0L

    private companion object {
        const val TAG = "AiUsageNotif"

        /**
         * How long an identical notification is treated as the same one.
         *
         * Generous. The duplicates measured were milliseconds apart, but the cost of
         * being wrong in this direction is a repeat that is silently dropped, and the
         * cost in the other is the panel flickering as a message replaces itself.
         */
        const val DEDUPE_MS = 3_000L
    }
}
