package dev.rgarjoaba.claudestatus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.rgarjoaba.claudestatus.Panel
import dev.rgarjoaba.claudestatus.R
import dev.rgarjoaba.claudestatus.data.DashboardState
import dev.rgarjoaba.claudestatus.data.LimitAlerts

/**
 * Raises a notification when a limit passes half its allowance, then again at 90% and
 * every 2% after that.
 *
 * Owns the durable "already warned" thresholds, because the decision has to survive
 * process death: this app's service is restarted often (installs, memory pressure,
 * MIUI), and thresholds held only in memory would re-fire every warning on each
 * restart. Six duplicate warnings about one bar is how a user learns to swipe these
 * away without reading them.
 */
class LimitNotifier(private val context: Context) {

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadThresholds(): Map<String, Int> =
        prefs.all.entries.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()

    private fun saveThresholds(map: Map<String, Int>) = prefs.edit().apply {
        clear()
        map.forEach { (k, v) -> putInt(k, v) }
    }.apply()

    /** Evaluates [state] and posts whatever it warrants. Safe to call on every poll. */
    fun onState(state: DashboardState) {
        val outcome = LimitAlerts.evaluate(state, loadThresholds())
        saveThresholds(outcome.thresholds)
        if (outcome.alerts.isEmpty()) return
        ensureChannel()
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        for (alert in outcome.alerts) {
            mgr.notify(alert.key, NOTIFICATION_TAG_ID, build(alert))
        }
    }

    private fun build(a: LimitAlerts.Alert): Notification {
        val window = LimitAlerts.windowName(a.kind)
        // The label is included as well as the duration: for a scoped window the label
        // is a model name, and "7-day" alone would not say WHICH 7-day limit.
        val what = if (a.label.equals(window, ignoreCase = true)) window else "${a.label} · $window"
        // The halfway mark is news, the rest is a warning, and they should not read the
        // same. An identical tone for 50% and 98% trains the user to skim both.
        val title = if (a.isMilestone) {
            "${a.account.label} — half of the $window limit used"
        } else {
            "${a.account.label} at ${a.percent}%"
        }
        val text = if (a.isMilestone) {
            "$what · ${a.percent}% — ${ResetClock.phrase(a.resetsAt)}"
        } else {
            "$what limit — ${ResetClock.phrase(a.resetsAt)}"
        }
        return Notification.Builder(context, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            // The mascot, not a stock system glyph — a notification from this app should
            // be identifiable in the status bar without opening the shade.
            //
            // Milestone and warning used to be told apart by using two DIFFERENT system
            // icons, which cost the app its identity to carry one bit. The bit now rides
            // on the tint instead: the system colours the small icon with this, so the
            // same mascot arrives green for a milestone and red for a limit.
            .setColor(if (a.isMilestone) Panel.Palette.WORK else Panel.Palette.CRIT)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            // The lock screen is visible to anyone near the phone, and the panel this
            // app drives already faces outward. Keep the detail behind an unlock.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    /**
     * IMPORTANCE_HIGH, unlike the ongoing status notification's LOW. The whole point is
     * to interrupt — a warning that arrives silently in the shade is one the user reads
     * after they have already hit the limit.
     */
    private fun ensureChannel() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Limit warnings", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Half of a usage limit, then 90% and every 2% after." }
        )
    }

    private companion object {
        const val PREFS = "limit_alerts"
        const val CHANNEL = "limits"

        /**
         * One id, distinguished by tag. notify(tag, id, …) keys on the pair, so each
         * account+window gets its own slot and a later warning for the same limit
         * REPLACES the earlier one rather than stacking. Six live notifications about
         * one bar climbing is noise, not information.
         */
        const val NOTIFICATION_TAG_ID = 2
    }
}
