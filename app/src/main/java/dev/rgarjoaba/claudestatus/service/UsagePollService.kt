package dev.rgarjoaba.claudestatus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import android.view.Display
import dev.rgarjoaba.claudestatus.Panel
import dev.rgarjoaba.claudestatus.data.StatePayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The engine: polls both accounts on a display-aware cadence (Cadence.kt) and pushes
 * the encoded state to the rear panel. Also owns the respawn watchdog (Task 15 Step
 * 2b, added after the rest of this class was designed and just as load-bearing):
 * the panel is always-on at the user's explicit request (RearActivity holds
 * FLAG_KEEP_SCREEN_ON unconditionally), so it no longer dies to the system's
 * display timeout — but it can still be killed by memory pressure, a crash, or a
 * reboot. A dead activity runs no code and cannot revive itself, so this service
 * checks for that on every tick and re-places it through the one verified path,
 * ShizukuBridge.show(). See ensurePlaced() for the two guards that matter there.
 */
class UsagePollService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var displays: DisplayManager

    /** Watchdog's own memory of the last observed state, so it can tell a genuine
     *  transition from a repeated reading — see ensurePlaced(). */
    private var watchdogState: Watchdog.State? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // Panel just woke: refresh immediately so it never shows a stale frame.
            if (displayId == Panel.DISPLAY_ID && panelOn()) scope.launch { safeTick() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        displays = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displays.registerDisplayListener(displayListener, null)
        scope.launch {
            while (isActive) {
                safeTick()
                delay(Cadence.forPanelState(panelOn()))
            }
        }
    }

    private fun panelOn(): Boolean =
        displays.getDisplay(Panel.DISPLAY_ID)?.state == Display.STATE_ON

    /**
     * tick(), guarded so one transient failure (a flaky network refresh, a Shizuku
     * hiccup) can't silently kill the polling loop forever. Without this, an
     * uncaught exception either escapes the `while (isActive)` loop in onCreate() —
     * ending polling for the rest of the process's life — or, from the
     * DisplayListener's fire-and-forget launch, an uncaught exception on
     * Dispatchers.Default with no installed handler. Cancellation must still
     * propagate normally, so it is rethrown rather than logged.
     */
    private suspend fun safeTick() {
        try {
            tick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "tick failed", e)
        }
    }

    private suspend fun tick() {
        val app = Graph.of(this)
        app.repository.refresh()
        // UsageRepository.refresh() keeps each account's last good snapshot when a
        // fetch degrades (see its KDoc), so this is never a blank/empty payload
        // just because one fetch failed — the panel keeps showing the last real
        // numbers with a non-OK Health, not nothing.
        val payload = StatePayload.encode(app.repository.state.value)
        val decision = ensurePlaced(app, payload)
        if (decision.state == Watchdog.State.ALIVE) userHasPlaced = true
        // A fresh respawn already delivered `payload` via the launch intent extra
        // (RearUserService.show()'s `am start --es state ...`), and calibrate mode
        // must never be touched by this service at all — only an already-alive,
        // non-calibrating panel gets the routine push.
        if (decision.state == Watchdog.State.ALIVE) app.bridge.update(payload)
    }

    /**
     * Re-places the panel if it has died. Always-on is a user requirement, so a
     * killed activity must not stay dead until the next manual tap.
     *
     * Goes through ShizukuBridge.show() deliberately: RearUserService.show() performs
     * launch -> poll-task-id -> move -> verify -> wake in one shell-uid process.
     * Hand-rolling that sequence over adb once stranded the activity fullscreen on
     * the MAIN display and required a force-stop to recover.
     *
     * The two guards the brief calls out both live in Watchdog.decide(), which is
     * plain Kotlin and unit tested directly (WatchdogTest):
     *  - a state TRANSITION is logged once, not every failing attempt, so a stuck
     *    "Shizuku dead" / "permission revoked" / "no rear display" condition
     *    doesn't spam the log every tick. The retry itself still happens every
     *    tick — show() is called on every DEAD reading — but only at the normal
     *    Cadence.FAST_MS/SLOW_MS pace; there is no faster inner retry loop, which
     *    is what "not a tight loop" actually requires.
     *  - CALIBRATING always wins over DEAD, so a panel that crashed mid-calibration
     *    is left dead rather than "helpfully" respawned with the normal usage
     *    payload, which would silently exit calibrate mode and reset the view
     *    under the user's hands. See ShizukuBridge.isCalibrating()'s KDoc for the
     *    one thing this cannot see: a calibrate session started via the manual adb
     *    broadcast workaround bypasses ShizukuBridge entirely and is invisible
     *    here — that is a pre-existing gap in how calibrate mode is reached today,
     *    not something this task can close without new infrastructure.
     */
    /**
     * Set once the panel has been placed deliberately — via MainActivity's button, or
     * a successful earlier respawn. Until then the watchdog reports state but does not
     * place anything. See the guard in ensurePlaced().
     */
    @Volatile
    private var userHasPlaced = false

    private fun ensurePlaced(app: Graph, payload: String): Watchdog.Decision {
        val decision = Watchdog.decide(watchdogState, app.bridge.isPanelAlive(), app.bridge.isCalibrating())
        watchdogState = decision.state
        if (decision.isNewState) {
            Log.i(TAG, when (decision.state) {
                Watchdog.State.DEAD -> "rear panel not present — re-placing"
                Watchdog.State.CALIBRATING -> "calibrate mode active — watchdog standing down"
                Watchdog.State.ALIVE -> "rear panel confirmed alive"
            })
        }
        // Respawn only if the user has placed the panel at least once since the
        // service started. The move to display 1 fails intermittently on this device,
        // and NEITHER of our detection methods can see it: `am stack list` reports the
        // same bounds either way, and `dumpsys` (plus Activity.getDisplay()) reports
        // display 1 while the panel is visibly rendering on the FRONT screen. So a
        // failed placement strands a fullscreen, immersive activity on the user's main
        // display, and the self-eviction guard cannot detect it either.
        //
        // Respawning unprompted after every install meant that happened three times
        // with no user action at all. Gating on an explicit placement keeps the
        // always-on respawn the user asked for, without the app seizing their main
        // screen on its own initiative.
        if (decision.shouldRespawn && userHasPlaced) app.bridge.show(payload)
        return decision
    }

    /**
     * Ongoing foreground notification. Deliberately static/generic text only — no
     * utilisation numbers, no account names, no timestamps rendered here. Nothing
     * about this notification, or any log line in this class, carries anything
     * beyond what's already true from the app being installed.
     */
    private fun notification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Claude Status", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Claude Status")
            .setContentText("Watching rate limits")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        displays.unregisterDisplayListener(displayListener)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ClaudeStatusPoll"
        private const val CHANNEL = "usage"
        private const val NOTIFICATION_ID = 1

        fun start(c: Context) = c.startForegroundService(Intent(c, UsagePollService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, UsagePollService::class.java))
    }
}
