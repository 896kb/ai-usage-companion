package dev.kb896.aiusage.ui

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.kb896.aiusage.Panel

/**
 * What has to be true before the panel works, and whether it is.
 *
 * Ordered by what a user does first, with the one step nobody can act on for them last —
 * it is advice to read once, not a task to work through.
 *
 * Every step is a real question with a real answer — except one. A step whose state
 * cannot be read is marked [checkable] = false and shown as advice rather than as an
 * unticked box: a checkbox that can never tick reads as a step the user failed to
 * complete, and they will keep trying.
 */
object Setup {

    /**
     * @param satisfied null when the state cannot be determined, which is not the same
     *   as false and must not be rendered as one
     * @param open where to send the user, or null if there is nowhere to send them
     */
    data class Step(
        val title: String,
        val why: String,
        val satisfied: Boolean?,
        val open: (() -> Intent)?,
    ) {
        val checkable get() = satisfied != null
    }

    fun steps(context: Context, shizukuGranted: Boolean, shizukuRunning: Boolean): List<Step> =
        listOf(
            Step(
                title = "Shizuku",
                why = "The rear display refuses an ordinary app. Shizuku lends this one " +
                    "the privilege to move a window there.",
                satisfied = shizukuRunning && shizukuGranted,
                // Granting happens in-app through the bridge, not in a settings page, so
                // this row carries no intent — MainActivity wires the tap to the request
                // instead. Left null rather than pointed at Shizuku's own app, which may
                // not be installed and whose page cannot grant anything on our behalf.
                open = null,
            ),
            Step(
                title = "Notifications",
                why = "The ongoing notification is what puts the readings in the status " +
                    "bar and the island, and the limit warnings arrive the same way.",
                satisfied = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                open = {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                },
            ),
            Step(
                title = "No battery restrictions",
                why = "Restricted, the poll service is stopped and the panel freezes on " +
                    "its last reading — showing old numbers as though they were current.",
                satisfied = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(context.packageName),
                open = {
                    // The app's own battery page. ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    // shows a system dialog instead, and on HyperOS that dialog does not
                    // govern the vendor's own restriction — which is the one that stops
                    // the service.
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null))
                },
            ),
            Step(
                title = "Rear display",
                why = "Found by the system. If this is missing, the phone is not one " +
                    "this app can drive.",
                satisfied = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                    .getDisplay(Panel.DISPLAY_ID) != null,
                open = null,
            ),
            Step(
                title = "Lock the app in recents",
                why = "Lock this app in recents — open recents, long-press it, tap the " +
                    "padlock. Otherwise \"close all\" kills the poll service and takes " +
                    "the panel down with it.",
                // Xiaomi does not expose whether an app is locked in recents, so this is
                // advice with no answer rather than a box that can never be ticked.
                satisfied = null,
                open = null,
            ),
        )

    /**
     * Whether everything the user can actually DO is done.
     *
     * Only the steps with a readable state count. The recents lock cannot be verified at
     * all, and the rear display is a property of the phone rather than a task — waiting
     * on either would leave the list permanently expanded on a setup that is, in every
     * sense the user can act on, finished.
     */
    fun allDone(steps: List<Step>): Boolean =
        steps.filter { it.satisfied != null }.all { it.satisfied == true }
}
