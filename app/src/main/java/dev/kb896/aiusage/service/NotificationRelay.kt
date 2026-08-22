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

        // GROUP SUMMARIES. This is the one that matters in practice.
        //
        // An app that can produce several related notifications posts each of them AND a
        // summary standing for the whole group — WhatsApp sends the message, then "2
        // messages from 2 chats" a moment later. Both are real notifications and the
        // summary arrives second, so it replaced the actual message on the wheel a split
        // second after it appeared: you saw who it was from just long enough to know you
        // had missed it.
        //
        // The summary is the one to drop. It carries a count where the child carries the
        // content, and the child is what someone glancing at the back of a phone wants.
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return

        // Categories that are never news either. Same reasoning as isOngoing, but these
        // are how an app DECLARES it rather than how the system infers it.
        when (sbn.notification.category) {
            android.app.Notification.CATEGORY_SERVICE,
            android.app.Notification.CATEGORY_PROGRESS,
            android.app.Notification.CATEGORY_TRANSPORT,
            -> return
        }

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
        // The rest of the group, cascaded under the headline.
        //
        // A grouped app posts one notification per conversation plus a summary; we drop
        // the summary and show the newest child, which used to mean the other unread
        // ones simply vanished. The listener can already see them — they are live
        // notifications, not history — so the panel can show what the summary was
        // counting instead of the count itself.
        val next = cascade(sbn)

        Log.i(TAG, "relaying from ${sbn.packageName}${if (next != null) " (+1 in group)" else ""}")

        // LIGHT THE PANEL FIRST, then tell it.
        //
        // The rear display dozes on its own, and a notification delivered to a sleeping
        // one is five seconds of strip animating in the dark followed by nothing to see.
        // Worse, the panel now DROPS what arrives while it is dark — deliberately, so
        // that waking it does not replay the night — so without this a notification while
        // it dozed was simply lost rather than merely unseen.
        //
        // The order matters and so does the pause. The panel decides whether to keep a
        // notice by asking its display whether there is a screen to show it on, and that
        // answer does not change the instant the keyevent is sent. A fifth of a second is
        // below noticing and comfortably past the state settling.
        val bridge = runCatching { Graph.of(this).bridge }.getOrNull()
        // A panel that was DARK a moment ago is a panel nobody was looking at, and a
        // phone nobody is looking at is very often in a pocket. Lock it, so the
        // notification does not arrive on a surface where a thigh can work the transport
        // buttons. If it was already lit, nothing changes: it was already there to be
        // touched, and seizing up in front of somebody using it would be the cure doing
        // the harm.
        if (bridge?.wake("a notification") == true) {
            sendBroadcast(Intent(RearActivity.ACTION_LOCK).setPackage(packageName))
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            relay(app, sbn, title, body, next, config)
        }, WAKE_SETTLE_MS)
    }

    private fun relay(
        app: String,
        sbn: StatusBarNotification,
        title: String?,
        body: String?,
        next: Pair<String, String>?,
        config: dev.kb896.aiusage.data.PanelConfig,
    ) {
        sendBroadcast(
            Intent(RearActivity.ACTION_NOTIFICATION)
                .setPackage(packageName)
                .putExtra(RearActivity.EXTRA_NOTIFICATION_APP, app)
                // The package, not the icon itself. :rear can ask PackageManager for a
                // drawable; putting a Bitmap through a broadcast would copy it across
                // the process boundary on every notification for no gain.
                .putExtra(RearActivity.EXTRA_NOTIFICATION_PKG, sbn.packageName)
                .putExtra(RearActivity.EXTRA_NOTIFICATION_TITLE, title.orEmpty())
                .putExtra(RearActivity.EXTRA_NOTIFICATION_TEXT, body.orEmpty())
                .putExtra(RearActivity.EXTRA_NOTIFICATION_MORE_TITLE, next?.first.orEmpty())
                .putExtra(
                    RearActivity.EXTRA_NOTIFICATION_MORE_TEXT,
                    // The second card's body obeys the same switch as the first's.
                    if (config.showNotificationText) next?.second.orEmpty() else "",
                ),
        )
    }

    /**
     * The NEXT live notification in this one's group, or null.
     *
     * One, not a list. The panel shows it as a second card under the first rather than
     * as a stack of lines, and a card is a thing you read — two of them is a glance and
     * five is an inbox, which is what the phone in your hand is for.
     *
     * Its own title and text are kept separate so the panel can lay them out; flattening
     * them here would decide the design in the wrong place.
     */
    private fun cascade(sbn: StatusBarNotification): Pair<String, String>? {
        val group = sbn.notification.group ?: return null
        val active = runCatching { activeNotifications }.getOrNull() ?: return null
        return active
            .filter {
                it.notification.group == group &&
                    it.key != sbn.key &&
                    it.packageName == sbn.packageName &&
                    it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
            }
            .maxByOrNull { it.postTime }
            ?.let { other ->
                val e = other.notification.extras
                val h = e.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                val t = e.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                if (h.isNullOrBlank() && t.isNullOrBlank()) null else (h.orEmpty() to t.orEmpty())
            }
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
        /**
         * How long the wake is given before the notice is sent.
         *
         * The panel asks its display whether it is on, and that does not become true the
         * moment the keyevent is sent. Below noticing, comfortably past the settle.
         */
        const val WAKE_SETTLE_MS = 200L

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
