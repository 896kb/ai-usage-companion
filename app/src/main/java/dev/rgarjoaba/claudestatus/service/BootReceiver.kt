package dev.rgarjoaba.claudestatus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts UsagePollService after a device reboot.
 *
 * The watchdog (Step 2b) revives a killed RearActivity because the SERVICE stays
 * up to notice — but a full reboot kills the service too, not just the activity.
 * Without this receiver, "the panel comes back after a reboot" (the requirement
 * Step 2b was written against) would simply be false: nothing would ever start
 * UsagePollService again.
 *
 * Deliberately does nothing but start the service. Placement itself is still only
 * ever done by UsagePollService.ensurePlaced() on its first tick — this receiver
 * does not call ShizukuBridge.show() directly, so there remains exactly one code
 * path that places the panel, not two that could disagree.
 *
 * IMPORTANT CAVEAT, not a bug: placement after a reboot is not immediate, and on
 * this device it cannot be. Shizuku's own privileged server does not survive a
 * reboot either — on a non-rooted phone it has to be started by hand (the adb
 * command MainActivity surfaces when `status().running` is false). So immediately
 * after boot, ShizukuBridge.isPanelAlive() reads false (not bound), the watchdog
 * correctly treats that as DEAD, and app.bridge.show() is attempted — but
 * ShizukuBridge.show() itself checks status().ready first and no-ops (logging a
 * warning) rather than throwing when Shizuku isn't there yet. UsagePollService's
 * OWN "rear panel not present" log — the one Watchdog.decide() gates on a state
 * TRANSITION — fires once, not every tick; ShizukuBridge.show()'s pre-existing
 * "not ready" warning is not deduplicated the same way and will still log on
 * every attempt, but that is bounded by the tick cadence (SLOW_MS while
 * unplaced, so at most once every 5 minutes), not a tight loop — retrying is
 * exactly what's wanted here, only the noise level differs from the ideal. This
 * continues silently until the user restarts Shizuku, at which point the very
 * next tick places the panel. "Comes back after a reboot" therefore means
 * "comes back once Shizuku is running again," not "comes back with zero user
 * action" — a real limitation of this device, not something this receiver or the
 * watchdog can paper over.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        UsagePollService.start(context)
    }
}
