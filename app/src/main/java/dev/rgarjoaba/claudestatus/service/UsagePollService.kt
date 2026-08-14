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
        Graph.of(this).repository.onFetchCompleted = { recordFetch() }
        displays = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displays.registerDisplayListener(displayListener, null)
        scope.launch {
            val initial = initialDelayMs()
            if (initial > 0) {
                Log.i(TAG, "recent fetch ${(System.currentTimeMillis() - lastFetchAt()) / 1000}s ago — first poll in ${initial / 1000}s")
                delay(initial)
            }
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
    /**
     * Confirms a placement actually landed on the rear panel, and tears it down if not.
     *
     * This exists because placement success is otherwise UNOBSERVABLE on this device.
     * `am stack list` reports identical bounds whether the activity is on display 1 or
     * stranded at (0,0) on the main display; `dumpsys window displays` and the
     * activity's own `getDisplay()` both report display 1 while it is visibly
     * rendering on the FRONT screen. Trusting any of them stranded a fullscreen,
     * immersive activity on the user's main screen three times, each needing a
     * force-stop to clear.
     *
     * So this asks the hardware instead. RearActivity holds FLAG_KEEP_SCREEN_ON, so if
     * it is genuinely on display 1 that display physically cannot doze. If the move
     * failed, MIUI powers the rear panel down within a few seconds. Panel state is
     * therefore a signal the window manager cannot misreport.
     *
     * Failure is bounded rather than eliminated: a bad placement is visible for up to
     * VERIFY_DELAY_MS before being cleared, instead of sitting there until someone
     * notices.
     */
    private suspend fun verifyPlacement(app: Graph) {
        delay(VERIFY_DELAY_MS)
        if (panelOn()) return
        Log.w(TAG, "placement did not light the rear panel — clearing a probable strand")
        app.bridge.hide()
    }

    private suspend fun safeTick() {
        try {
            tick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "tick failed", e)
        }
    }

    /**
     * Wall-clock time of the last completed fetch, persisted so it survives the process
     * dying. elapsedRealtime would be wrong here: it resets on reboot, and the point is
     * to remember across restarts.
     */
    private fun lastFetchAt(): Long =
        getSharedPreferences("poll", MODE_PRIVATE).getLong("lastFetchAt", 0L)

    private fun recordFetch() =
        getSharedPreferences("poll", MODE_PRIVATE).edit()
            .putLong("lastFetchAt", System.currentTimeMillis()).apply()

    /**
     * How long to wait before the first fetch after the service starts.
     *
     * Every service start used to fetch immediately. That is fine once, but the service
     * restarts far more often than expected — every app update during development, and
     * in normal use whenever MIUI kills and revives it. Each restart then fired a
     * request, producing bursts against the very endpoint that reports rate limits, and
     * that burst pattern is what got the work account 429'd. Rate is not the problem;
     * bunching is.
     *
     * So a restart resumes the existing cadence rather than restarting it: if the last
     * fetch was recent, wait out the remainder instead of fetching again.
     */
    private fun initialDelayMs(): Long {
        val since = System.currentTimeMillis() - lastFetchAt()
        if (since < 0) return 0L // clock moved backwards; do not stall forever
        return (Cadence.FAST_MS - since).coerceIn(0L, Cadence.FAST_MS)
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
        if (decision.shouldRespawn) {
            app.bridge.show(payload)
            scope.launch { verifyPlacement(app) }
        }
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
        /**
         * How long to wait before checking whether a placement lit the rear panel.
         * Must exceed MIUI's own rear-display timeout (observed ~5s) so a genuine
         * success is not mistaken for a failure, while keeping a bad placement's time
         * on the user's main screen short.
         */
        private const val VERIFY_DELAY_MS = 8_000L

        private const val TAG = "ClaudeStatusPoll"
        private const val CHANNEL = "usage"
        private const val NOTIFICATION_ID = 1

        fun start(c: Context) = c.startForegroundService(Intent(c, UsagePollService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, UsagePollService::class.java))
    }
}
