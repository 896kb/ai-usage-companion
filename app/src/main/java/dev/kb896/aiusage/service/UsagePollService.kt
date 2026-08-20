package dev.kb896.aiusage.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.Display
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.StatePayload
import dev.kb896.aiusage.rear.RearActivity
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

    private val limitNotifier by lazy { LimitNotifier(this) }

    /** Last panel power state this listener acted on — see onDisplayChanged. */
    private var panelWasOn = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}

        /**
         * Repaints the panel when it wakes. Deliberately does NOT fetch.
         *
         * This callback fires on every change to a display's properties — brightness
         * ramps, refresh-rate switches between 60 and 120Hz, rotation — not only on
         * power transitions. It previously ran a full safeTick() on each one, so an
         * ordinary brightness ramp produced a burst of requests, observed at up to
         * three per second.
         *
         * Two things fix that, and both are needed. The state check makes this act only
         * on a genuine off -> on transition instead of every property change. And a
         * wake does not need new data anyway: showing the panel what the repository
         * already holds is exactly the "no extra calls, just render what's already in"
         * rule the rest of this app follows. Fresh numbers arrive on the poll loop's
         * own schedule.
         */
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Panel.DISPLAY_ID) return
            val on = panelOn()
            val woke = on && !panelWasOn
            panelWasOn = on
            if (!woke) return
            scope.launch { safeSupervise() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The type must be passed here as well as declared in the manifest — they are
        // two separate assertions and the framework checks both. specialUse rather than
        // dataSync: see the manifest for why the six-hour cap on dataSync would take the
        // panel down every afternoon.
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        val app = Graph.of(this)
        app.repository.onFetchCompleted = {
            val current = app.repository.state.value
            // Only cache a refresh that actually produced numbers. onFetchCompleted
            // fires whatever the outcome, so caching unconditionally let one throttled
            // cycle overwrite good values with an empty snapshot — destroying exactly
            // the data this cache exists to preserve.
            //
            // The timestamp is still recorded either way: a failed attempt is still an
            // attempt, and must not license an immediate retry on the next restart.
            if (current.statuses.any { it.snapshot != null }) {
                recordFetch(StatePayload.encode(current))
            } else {
                recordFetchTimeOnly()
            }
        }
        // Render the last known values immediately rather than waiting out the
        // remaining interval with a blank panel.
        cachedState()?.let { app.repository.restore(StatePayload.decode(it).state) }
        displays = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displays.registerDisplayListener(displayListener, null)
        // RECEIVER_NOT_EXPORTED is required, not optional: on API 34+ a receiver whose
        // filter contains a NON-system action must declare its export state or
        // registerReceiver throws SecurityException and takes the service down at
        // startup. ACTION_STRANDED is ours, so this filter is no longer system-only.
        // NOT_EXPORTED still receives it — RearActivity is a different process but the
        // same UID.
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            // RearActivity shouting that it has landed on the wrong display. It cannot
            // move itself — moveRootTaskToDisplay needs shell privileges — so it tells
            // us and we go through Shizuku. Handled on the same path as the screen
            // broadcasts: ensureOnRear() first, which is the whole remedy when it works.
            addAction(RearActivity.ACTION_STRANDED)
            // The panel's camera button, pressed on the back of the phone. Same reason
            // it comes here: the Shizuku binding lives in this process and the panel's
            // does not.
            addAction(RearActivity.ACTION_OPEN_CAMERA)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Place the panel NOW, before either loop's first delay.
        //
        // This is the whole recovery story for an app that has been killed: the panel
        // dies with the app (RearActivity is ours, in our own :rear process, and
        // force-stop takes the Shizuku user service with it since Shizuku names that
        // process after our package). Nothing outside the app can bring it back, so
        // starting the app has to. Without this the poll loop waits out
        // initialDelayMs() — up to a full 5 minutes if a fetch completed recently —
        // and the watchdog waits its own 20s, so opening the app to fix a dead panel
        // left it dead for another 20s at best.
        //
        // Uses cached state and makes no network call, so it costs nothing against
        // the rate limit and cannot re-introduce the request bursts.
        scope.launch { safeSupervise() }

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

        // Supervising the display is a separate job from fetching data, on its own
        // clock. Coupling them meant a panel dismissed by MIUI's double-tap gesture
        // stayed gone until the next poll — up to five minutes of blank back screen
        // for something that should return in seconds. This loop makes no network
        // calls: it re-places using whatever the repository already holds.
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                safeSupervise()
            }
        }
    }

    /**
     * "Do not relaunch right now" — screen off OR keyguard up.
     *
     * isKeyguardLocked() alone is not enough and was silently useless on this device:
     * it reports the SECURE lock state, so with a swipe-only keyguard it returns false
     * the whole time the lock screen is showing. The window manager's
     * isKeyguardShowing said true at the same moment. A respawn was observed firing 25s
     * into a lock because of that gap, which is exactly the `am start` across display 0
     * this guard exists to prevent.
     *
     * PowerManager.isInteractive() is the reliable half: it goes false when the main
     * screen goes off, regardless of how the keyguard is configured.
     */
    private fun screenOffOrLocked(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) return true
        return getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
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
     * Fires on lock and unlock. Both are moments the system re-evaluates which
     * activity shows over the keyguard, and both are when the panel was observed
     * jumping to the front screen — so the corrective check runs immediately rather
     * than waiting out the watchdog interval, which would leave the lock screen
     * covered for up to 20 seconds. Makes no network call.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // The camera is not a placement problem, so it does not go through the
            // supervisor: that path exists to get the panel back where it belongs, and
            // running it here would have a camera tap second-guessing the panel's
            // position at the exact moment something else is about to cover it.
            if (intent?.action == RearActivity.ACTION_OPEN_CAMERA) {
                Graph.of(this@UsagePollService).bridge.openCamera()
                return
            }
            safeSupervise()
        }
    }

    /**
     * Post-placement check. Corrects, never tears down.
     *
     * This used to call bridge.hide() when the rear panel was not lit 8s after a
     * placement, on the theory that an unlit panel meant a failed move. That theory is
     * obsolete — SurfaceFlinger now tells us directly which display is compositing the
     * activity — and the action was actively destructive: hide() FINISHES the activity,
     * so every placement armed a timer that could kill the panel, and the watchdog then
     * respawned it ~20s later. Measured on device while the app sat in the background:
     * the task was destroyed and recreated on a 28-32s cycle, which is exactly this 8s
     * teardown plus the 20s watchdog. The panel was fighting itself.
     *
     * ensureOnRear() moves a genuinely stranded task back and does nothing when the
     * answer is uncertain, so an unlit-but-fine panel now costs one dumpsys instead of
     * the panel's life.
     */
    private suspend fun verifyPlacement(app: Graph) {
        delay(VERIFY_DELAY_MS)
        if (app.bridge.ensureOnRear()) Log.w(TAG, "post-placement: was on the main display, moved back")
    }

    /** Watchdog-only pass: no fetch, just make sure the panel is still up. */
    private fun safeSupervise() {
        try {
            val app = Graph.of(this)
            if (app.bridge.ensureOnRear()) Log.w(TAG, "panel was on the main display — moved back")
            ensurePlaced(app, StatePayload.encode(app.repository.state.value, config()))
        } catch (e: Exception) {
            Log.e(TAG, "supervise failed", e)
        }
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

    /**
     * Persists the fetch time AND the values themselves. Carrying only the timestamp
     * meant a restart knew not to re-fetch but had nothing to show, so the panel sat
     * empty for the remainder of the interval — blank for data we already had.
     *
     * The payload is the same one broadcast to the panel: utilisations and timestamps
     * only, never a credential. See StatePayload.
     */
    private fun recordFetch(state: String) =
        getSharedPreferences("poll", MODE_PRIVATE).edit()
            .putLong("lastFetchAt", System.currentTimeMillis())
            .putString("lastState", state)
            .apply()

    /** Records the attempt without touching the cached values. See onFetchCompleted. */
    private fun recordFetchTimeOnly() =
        getSharedPreferences("poll", MODE_PRIVATE).edit()
            .putLong("lastFetchAt", System.currentTimeMillis()).apply()

    private fun cachedState(): String? =
        getSharedPreferences("poll", MODE_PRIVATE).getString("lastState", null)

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

    /**
     * Read fresh on every send, never cached in a field. A settings edit must reach the
     * panel on the next push without this service being restarted to notice it.
     */
    private fun config() = ConfigStore(this).load()

    private suspend fun tick() {
        val app = Graph.of(this)
        app.repository.refresh()
        // UsageRepository.refresh() keeps each account's last good snapshot when a
        // fetch degrades (see its KDoc), so this is never a blank/empty payload
        // just because one fetch failed — the panel keeps showing the last real
        // numbers with a non-OK Health, not nothing.
        // Alert BEFORE placement work: a limit warning must not be delayed, or lost
        // entirely, because the panel happened to need re-placing on this tick.
        // Evaluates against the freshly-refreshed state and is a no-op when nothing
        // crossed a band.
        limitNotifier.onState(app.repository.state.value)

        // Re-post the ongoing notification so the shade and the island follow the new
        // reading. startForeground is not repeated — the service is already foreground,
        // and notify() on the same id is the documented way to update it.
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification())

        val payload = StatePayload.encode(app.repository.state.value, config())
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
            // NOT while the keyguard is up. Respawning means ShizukuBridge.show(), and
            // show() launches on display 0 before moving to display 1 — the rear panel
            // rejects direct launches, so there is no other route. RearActivity holds
            // FLAG_SHOW_WHEN_LOCKED and setTurnScreenOn(true), so that transient launch
            // does not pass unnoticed on a locked phone: it draws over the keyguard and
            // wakes the main screen. That is the "every time I lock the screen it gets
            // sent to the main screen" report, and the cause is this respawn, not the
            // system moving anything.
            //
            // Deferring costs a dark rear panel until the phone is unlocked, which
            // nobody is looking at anyway — the alternative is hijacking the lock
            // screen every time. USER_PRESENT drives screenReceiver, so the panel is
            // back within a moment of unlocking.
            if (screenOffOrLocked()) {
                Log.i(TAG, "panel needs re-placing but the keyguard is up — deferring to unlock")
                return decision
            }
            // `payload` carries the config, so a freshly launched panel comes up with
            // the user's ring geometry already applied. This used to need a second,
            // delayed push from a separate store — the panel runs in ":rear" and could
            // not see values kept only in this process — and that delay was a visible
            // window in which every respawn showed the compiled-in defaults.
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
            NotificationChannel(CHANNEL, "AI Usage", NotificationManager.IMPORTANCE_LOW))
        // Tapping the notification re-places the panel. This is the only recovery path
        // that does not require opening the app, and it exists because the panel can
        // end up in states the watchdog cannot see — a task that still exists while
        // nothing composites it will read as ALIVE forever.
        val tap = PendingIntent.getForegroundService(
            this, 0,
            Intent(this, UsagePollService::class.java).setAction(ACTION_SHOW_PANEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle("AI Usage")
            .setContentText("Tap to show on the rear panel")
            // Ours, not a stock system glyph — this one is permanently in the
            // status bar, so it is the app's face more than any other icon.
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Panel.Palette.WORK)
            .setContentIntent(tap)
            .setOngoing(true)
            // Once the numbers exist, this notification carries them — into the shade
            // and, on HyperOS, into the island. Decorating the notification the service
            // must keep alive anyway costs nothing; a second permanent one would.
            .let { IslandNotification.decorate(it, Graph.of(this).repository.state.value) }
        return builder.build()
    }

    /**
     * The app was swiped out of recents.
     *
     * A foreground service is supposed to survive this, and on stock Android it does.
     * HyperOS is more aggressive: "close all" takes the process, and with it the panel —
     * which is why the setup advice says to lock the app in recents. Asking for the
     * service back immediately is the one thing this side can do about it, and it costs
     * nothing when the system was going to keep us anyway.
     *
     * Not a substitute for the autostart permission. If MIUI has decided to kill the
     * process, this call goes with it; what it recovers is the milder case where the
     * TASK is removed but the process survives.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed — asking for the service back")
        start(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        displays.unregisterDisplayListener(displayListener)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Handles the notification tap: re-place the panel, now, from cached state.
     *
     * Deliberately a re-place rather than a nudge — this is the button you reach for
     * precisely when the panel is NOT there, so it must work from a dead panel, not
     * only from a live one. It calls show() directly instead of going through
     * ensurePlaced(), because the watchdog's Watchdog.decide() will happily answer
     * ALIVE for a task that exists but is composited nowhere, which is the exact state
     * this recovers from.
     *
     * Makes no network call. The payload is whatever the repository already holds, the
     * same rule the rest of the app follows.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_PANEL) {
            val app = Graph.of(this)
            app.bridge.show(StatePayload.encode(app.repository.state.value, config()))
        }
        return START_STICKY
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

        /**
         * How often to check the panel is still placed. Short because the user
         * dismisses it by double-tapping the rear panel, and a status display that
         * takes minutes to come back is not one you trust to be there.
         *
         * Cheap to run: no network, just an aliveness check and, if needed, a re-place
         * using cached state.
         */
        /**
         * How long after a placement to send the tuned ring geometry. Long enough for
         * the activity to have registered its receiver, short enough that the default
         * geometry is not visible for long.
         */
        private const val GEOMETRY_PUSH_DELAY_MS = 1_500L

        private const val WATCHDOG_INTERVAL_MS = 20_000L

        /** Notification tap → re-place the panel. See onStartCommand. */
        const val ACTION_SHOW_PANEL = "dev.kb896.aiusage.SHOW_PANEL"

        private const val TAG = "AiUsagePoll"
        private const val CHANNEL = "usage"
        private const val NOTIFICATION_ID = 1

        fun start(c: Context) = c.startForegroundService(Intent(c, UsagePollService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, UsagePollService::class.java))
    }
}
