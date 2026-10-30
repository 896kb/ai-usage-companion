package dev.kb896.aiusage.ui

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.ShizukuBridge

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
        /** What a tap does when it is not an intent. */
        val kind: Kind = Kind.OPEN,
        /**
         * What is said before the tap opens anything (Privacy.disclose), or null when the
         * step opens nothing that grants access. [next] is the line after it: where the
         * Continue leads, when that is not the page the step's title already names.
         */
        val access: Privacy.Access? = null,
        val next: String? = null,
    ) {
        val checkable get() = satisfied != null
    }

    enum class Kind {
        /** Opens [Step.open], or is plain advice when there is nothing to open. */
        OPEN,
        /** Asks Shizuku for permission, in-app. */
        SHIZUKU,
        /**
         * Hides or restores HyperOS's rear-screen app — a row that stays pressable in
         * both states, because the tap means the opposite thing in each.
         */
        XIAOMI_APP,
    }

    /** Shizuku's own app when it is installed; its site, where it is downloaded from, when not. */
    private fun shizukuIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/"))
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /**
     * Whether HyperOS's rear-screen app is in the panel's way for this user: true if it is
     * installed and free to run, false if it has been hidden with `pm uninstall --user 0`
     * or suspended, null if this phone has no such app at all — in which case there is
     * nothing to offer.
     *
     * Suspended counts as out of the way because on HyperOS 4 (Android 17) that is all
     * shell may do: uninstalling a system app for one user is refused there ("only root
     * can delete system app for a particular user"), and so is disabling it. A suspended
     * rear-screen app cannot bring its launcher up, which is the whole point.
     */
    fun xiaomiRearAppInstalled(context: Context): Boolean? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val onPhone = runCatching {
            pm.getApplicationInfo(Panel.XIAOMI_REAR_APP, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        }.isSuccess
        if (!onPhone) return null
        @Suppress("DEPRECATION")
        val info = runCatching { pm.getApplicationInfo(Panel.XIAOMI_REAR_APP, 0) }.getOrNull()
            ?: return false
        return info.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED == 0
    }

    fun steps(context: Context, shizukuGranted: Boolean, shizukuRunning: Boolean): List<Step> =
        listOfNotNull(
            // Two rows, because they are two different things to do: starting Shizuku
            // happens in Shizuku's own app (or, before that, on its site), and allowing
            // this app happens in a dialog Shizuku shows when this app asks. One row for
            // both left a running-but-not-allowed Shizuku looking the same as an absent
            // one, and a tap that could do nothing until Shizuku was up.
            Step(
                title = "Shizuku running",
                why = "The rear display refuses an ordinary app. Shizuku lends this one " +
                    "the privilege to move a window there. Start it from its own app — " +
                    "Wireless debugging is the way without a computer.",
                satisfied = shizukuRunning,
                open = { shizukuIntent(context) },
                kind = Kind.OPEN,
                // Said here, at the first of the two Shizuku rows, as well as at the second:
                // installing and starting Shizuku is already the decision to lend this app
                // shell rights, so what they are used for belongs before it, not after.
                access = Privacy.Access.SHIZUKU,
                next = "Next: Shizuku's own app, or its site (shizuku.rikka.app) while it " +
                    "is not installed.",
            ),
            Step(
                title = "Allow this app in Shizuku",
                why = "Shizuku asks once whether this app may use it. Until you allow it, " +
                    "nothing here can reach the rear display. This row asks again.",
                // Not ticked while Shizuku is down even if it once said yes: the privilege
                // is only real while Shizuku is there to lend it.
                satisfied = shizukuRunning && shizukuGranted,
                open = null,
                kind = Kind.SHIZUKU,
                access = Privacy.Access.SHIZUKU,
                next = "Next: Shizuku asks whether this app may use it.",
            ),
            // Xiaomi's rear-screen app is not a step any more: removing or restoring it is
            // the settings' show/hide toggle, offered once the steps here are done — see
            // panelAllowed, and MainActivity.refreshControl.
            // NOT HERE: this app's own notifications (the readings in the status bar, the
            // limit warnings) are the user's to allow or not, and nothing on the back needs
            // them. Notification access is asked for where it is needed — switching on the
            // panel's notifications or media controls (MainActivity.askNotificationAccess).
            Step(
                title = "No battery restrictions",
                why = "Restricted, the poll service is stopped and the panel freezes on " +
                    "its last reading — showing old numbers as though they were current.",
                satisfied = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(context.packageName),
                access = Privacy.Access.BATTERY,
                next = "Next: this app's page in Android's settings. Set its battery use to no restrictions.",
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
                title = "Autostart",
                why = "HyperOS will not let this app start itself otherwise — not after a " +
                    "reboot, and not after it has killed the process. This is the setting " +
                    "that makes the panel come back on its own instead of waiting for you " +
                    "to open the app.",
                // Not readable. MIUI keeps autostart in its own security centre and
                // exposes no permission, setting or dumpsys field for it — checked. So
                // this is advice with a button, not a box that could ever tick, and it
                // is shown as such rather than as a step the user keeps failing.
                satisfied = null,
                access = Privacy.Access.AUTOSTART,
                next = "Next: HyperOS's Autostart list. Turn it on for this app.",
                open = {
                    Intent().setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    )
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
    /**
     * Whether this app holds notification access — what mirroring other apps'
     * notifications onto the panel and the media controls both read from. Not a step:
     * asked for when one of those is switched on.
     */
    fun notificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun notificationAccessIntent(): Intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")

    fun allDone(steps: List<Step>): Boolean =
        steps.filter { it.satisfied != null }.all { it.satisfied == true }

    /**
     * Whether the panel may be placed on the rear display at all.
     *
     * The same question as [allDone], asked by the bridge before every placement and
     * every re-placement: until the onboarding is complete — Shizuku granted, Xiaomi's
     * own rear-screen app out of the way, the permissions given — the panel is left
     * alone rather than put back every twenty seconds against a system that keeps
     * taking the display from it. And if a step comes undone later, the next check
     * takes the panel down instead of fighting for it.
     */
    fun panelAllowed(context: Context, status: ShizukuBridge.Status): Boolean =
        allDone(steps(context, status.granted, status.running)) && !shownByXiaomi(context)

    /**
     * Whether Xiaomi's rear-screen app is in the way: installed and not suspended, on a
     * phone that has it.
     * The panel cannot stay on the back against it, so this is the show/hide switch —
     * "Show on the back" removes it, "Hide" brings it back and the panel goes.
     */
    fun shownByXiaomi(context: Context): Boolean = xiaomiRearAppInstalled(context) == true
}
