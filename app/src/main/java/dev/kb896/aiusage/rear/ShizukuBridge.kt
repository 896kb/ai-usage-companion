package dev.kb896.aiusage.rear

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import dev.kb896.aiusage.IRearEvents
import dev.kb896.aiusage.IRearService
import dev.kb896.aiusage.Panel
import rikka.shizuku.Shizuku

class ShizukuBridge(private val context: Context) {

    data class Status(val running: Boolean, val granted: Boolean, val rearDisplayId: Int) {
        val ready get() = running && granted && rearDisplayId >= 0
    }

    @Volatile private var service: IRearService? = null
    /** A placement waiting for the service: the payload, and whether it was asked to light the back. */
    private var pending: Pair<String, Boolean>? = null

    /**
     * Supervision wants an answer from the service and there was no binder to bind with yet:
     * bound as soon as Shizuku's binder arrives (see [armBinderListener]).
     */
    @Volatile private var bindWanted = false

    /**
     * The last payload handed to the shell (show or update), sent or not. A new shell process
     * starts with none — its portal settings (frame colour, grid, DPI, confined apps) at their
     * defaults until the next payload — so a (re)connect sends it first.
     */
    @Volatile private var lastPayload: String? = null
    private var binderListenerArmed = false

    /**
     * May the panel go on the rear display right now? Set by Graph to the onboarding's
     * answer (Setup.panelAllowed); the default says yes so the bridge is usable alone.
     * Checked on every placement and re-placement, so a step coming undone is honoured
     * within one watchdog tick — the panel comes down instead of being fought for.
     */
    var gate: (Status) -> Boolean = { true }

    /**
     * Flush the queued payload as soon as Shizuku's binder shows up.
     *
     * Sticky, so it fires immediately if the binder is already there rather than
     * waiting for the next delivery — otherwise a race between the queue and the
     * binder's arrival strands the payload until something else calls show().
     * Registered at most once; Shizuku keeps listeners for the process's lifetime.
     */
    private fun armBinderListener() {
        if (binderListenerArmed) return
        binderListenerArmed = true
        try {
            Shizuku.addBinderReceivedListenerSticky {
                pending?.let { (p, light) -> pending = null; show(p, light) }
                if (bindWanted) { bindWanted = false; bind() }
            }
        } catch (e: Exception) {
            binderListenerArmed = false
            Log.e(TAG, "could not register the Shizuku binder listener", e)
        }
    }

    /**
     * Best-effort "is the panel currently meant to be in calibrate mode" flag — see
     * isCalibrating() below for what it can and cannot see.
     */
    @Volatile var calibrating = false
        private set

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, RearUserService::class.java.name))
            .daemon(false).processNameSuffix("rear").debuggable(false).version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IRearService.Stub.asInterface(binder) else null
            portalApps?.let { b -> runCatching { service?.setPortalApps(b) } }
            runCatching { service?.setPanelGuarded(panelGuarded) }
            // A new shell process starts awake (dozing false): a panel asleep on purpose
            // would otherwise be read and woken as if it were not. But only what this process
            // KNOWS: a new app process has not heard from the panel yet, and pushing its
            // default false overwrote the shell's true while the back slept. Not known, the
            // panel is asked to say it again (it answers ACTION_MIRROR_SYNC with ACTION_DOZING),
            // and the answer comes through setDozing below, to the shell as well.
            dozing.let { d ->
                if (d != null) runCatching { service?.setDozing(d) }
                else context.sendBroadcast(android.content.Intent(RearActivity.ACTION_MIRROR_SYNC).setPackage(context.packageName))
            }
            runCatching { service?.setEvents(events) }
            quickStates?.let { (states, reachable) -> runCatching { service?.setQuickStates(states, reachable) } }
            // The settings the shell runs on, before anything is placed with them.
            lastPayload?.let { p -> runCatching { service?.update(p) } }
            pending?.let { (p, light) -> pending = null; show(p, light) }
            // Now there is somebody to ask whether the panel is there: supervision, again.
            if (service != null) onConnected()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    fun status(): Status {
        val running = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        val granted = try {
            running && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        return Status(running, granted, rearDisplayId())
    }

    /**
     * [Panel.DISPLAY_ID] is the single source of truth for which display the rear
     * panel is — never derive it. We only confirm that display actually exists, so
     * an absent panel still reports -1 and the front UI can say so.
     *
     * Deliberately NOT "first display that isn't the default": that heuristic is
     * correct only by topology coincidence and would silently select a Chromecast,
     * an HDMI output, or a virtual display if one were ever enumerated.
     */
    fun rearDisplayId(): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return if (dm.getDisplay(Panel.DISPLAY_ID) != null) Panel.DISPLAY_ID else -1
    }

    fun requestPermission(@Suppress("UNUSED_PARAMETER") activity: Activity, requestCode: Int = 4117) {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { bind(); return }
        Shizuku.requestPermission(requestCode)
    }

    fun bind() {
        if (service != null) return
        try { Shizuku.bindUserService(args, connection) } catch (e: Exception) { Log.e(TAG, "bind failed", e) }
    }

    /**
     * Place the panel. [light]: somebody ASKED for it — the "Show on the back" button, the
     * notification's tap, the onboarding finishing — so the back is lit whatever it was. The
     * watchdog's recoveries pass false, and the back is then left as they find it (PlaceLight):
     * a new app process recovering a panel must not light a back asleep in a pocket.
     */
    fun show(payload: String, light: Boolean = true) {
        calibrating = CalibrateMode.isCalibrate(payload)
        val s = status()
        if (!gate(s)) {
            // Not placed, and not queued either: the onboarding is not done. A panel that
            // is up from before the step came undone is taken down, so the two rear-screen
            // apps stop trading the display every tick.
            pending = null
            if (panelAlive() == true) {
                Log.i(TAG, "onboarding incomplete — taking the panel down")
                hide()
            } else {
                Log.i(TAG, "onboarding incomplete — not placing the panel")
            }
            return
        }
        lastPayload = payload
        if (!s.ready) {
            // Hold the payload instead of dropping it. This is the normal state for the
            // first seconds after the app is killed and restarted: Shizuku has to
            // re-deliver its binder to the fresh process, so status().ready is false
            // even though everything is installed and granted. Dropping the request
            // here meant the panel could not return until the next 20s watchdog tick
            // happened to land after the binder arrived — measured at ~30s. Queued
            // plus the listener below, it goes back the moment Shizuku is reachable.
            Log.w(TAG, "not ready: $s — queuing")
            pending = payload to light
            armBinderListener()
            return
        }
        val svc = service ?: run { pending = payload to light; bind(); return }
        try { svc.place(payload, s.rearDisplayId, light) } catch (e: Exception) { Log.e(TAG, "show failed", e); service = null }
    }

    /**
     * Run when the service connects (main thread). Set by UsagePollService to supervise again:
     * an unbound bridge cannot say whether the panel is there ([panelAlive] is null), so the
     * watchdog leaves it alone until this.
     */
    @Volatile var onConnected: () -> Unit = {}

    /** Bind now if Shizuku is reachable, or the moment its binder arrives. */
    private fun bindWhenReachable() {
        if (status().ready) bind() else { bindWanted = true; armBinderListener() }
    }

    fun update(payload: String) {
        calibrating = CalibrateMode.isCalibrate(payload)
        lastPayload = payload
        // To the panel from here, not relayed by the shell: its receiver takes only this app.
        // The shell is still told — it keeps the payload for its own re-placing and frame.
        toPanel(RearActivity.ACTION_STATE, payload)
        try { service?.update(payload) } catch (e: Exception) { Log.e(TAG, "update failed", e); service = null }
    }

    fun hide() {
        toPanel(RearActivity.ACTION_FINISH)
        try { service?.hide() } catch (e: Exception) { Log.e(TAG, "hide failed", e) }
    }

    /**
     * Hide or restore HyperOS's rear-screen app. Null when the user service is not bound
     * yet — a bind is started, and the caller should say "try again in a moment" rather
     * than "failed".
     */
    /**
     * The user service, bound if it was not — waiting up to [timeoutMs] for Shizuku to
     * start the process and hand over the binder. Blocks: call it off the main thread.
     * Null when Shizuku never answered, which with the permission granted means it is
     * not running.
     */
    fun awaitService(timeoutMs: Long = 6_000): IRearService? {
        service?.let { return it }
        bind()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (service == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        return service
    }

    fun setXiaomiRearApp(installed: Boolean): Boolean? {
        // Nothing else binds the service while the onboarding is incomplete — placement,
        // which used to do it as a side effect, is gated on that very onboarding — so
        // the row binds and waits rather than asking the user to tap twice.
        val svc = awaitService() ?: return null
        return try {
            if (installed) svc.restoreXiaomiRearApp() else svc.removeXiaomiRearApp()
        } catch (e: Exception) {
            Log.e(TAG, "xiaomi rear app change failed", e); service = null; false
        }
    }

    /** The rear-display report for a bug report; null when the service does not answer. */
    fun rearReport(): String? {
        val svc = awaitService() ?: return null
        return try {
            svc.rearReport()
        } catch (e: Exception) {
            Log.e(TAG, "rear report failed", e); service = null; null
        }
    }

    /** The rear display's brightness, 1 to 255; null when the service does not answer. */
    fun rearBrightness(): Int? {
        val svc = awaitService() ?: return null
        return try {
            svc.rearBrightness().takeIf { it in 1..255 }
        } catch (e: Exception) {
            Log.e(TAG, "rear brightness read failed", e); service = null; null
        }
    }

    /** Set the rear display's brightness, 1 to 255; false when the service does not answer. */
    fun setRearBrightness(value: Int): Boolean {
        val svc = awaitService() ?: return false
        return try {
            svc.setRearBrightness(value); true
        } catch (e: Exception) {
            Log.e(TAG, "rear brightness write failed", e); service = null; false
        }
    }

    /** Whether the rear brightness is automatic; null when the service does not answer. */
    fun rearAutoBrightness(): Boolean? {
        val svc = awaitService() ?: return null
        return try { svc.rearAutoBrightness() } catch (e: Exception) {
            Log.e(TAG, "rear auto brightness read failed", e); service = null; null
        }
    }

    fun setRearAutoBrightness(on: Boolean): Boolean {
        val svc = awaitService() ?: return false
        return try { svc.setRearAutoBrightness(on); true } catch (e: Exception) {
            Log.e(TAG, "rear auto brightness write failed", e); service = null; false
        }
    }

    /** Bluetooth, mobile data and Wi-Fi, on or off; null when the service does not answer. */
    fun quickStates(): BooleanArray? {
        val svc = awaitService() ?: return null
        return try {
            svc.quickStates().split(",").map { it.trim() == "1" }.takeIf { it.size == 3 }?.toBooleanArray()
        } catch (e: Exception) {
            Log.e(TAG, "quick states failed", e); service = null; null
        }
    }

    fun setQuick(which: String, on: Boolean): Boolean {
        val svc = awaitService() ?: return false
        return try { svc.setQuick(which, on); true } catch (e: Exception) {
            Log.e(TAG, "quick $which failed", e); service = null; false
        }
    }

    /**
     * Whether the panel is there (IRearService.isAlive); NULL when there is no service to ask.
     *
     * Not bound used to read as DEAD, and that is the state of every new app process — HyperOS
     * restarting the app, an update, a swipe from recents — so each of those re-placed a panel
     * that was most likely fine, and the placement lit the back in a pocket. Unknown is not
     * dead: a bind is started here, and [onConnected] runs supervision again with a real answer.
     * A call that fails is unknown too — the service has gone, and is bound again.
     */
    fun panelAlive(): Boolean? {
        val svc = service ?: run { bindWhenReachable(); return null }
        return try { svc.isAlive() } catch (e: Exception) {
            Log.e(TAG, "isAlive failed", e); service = null; bindWhenReachable(); null
        }
    }

    /**
     * Pulls the panel back to the rear display if it has been hoisted onto the main
     * one. Cheap and silent when nothing is wrong — see IRearService.ensureOnRear.
     */
    fun ensureOnRear(): Boolean {
        val svc = service ?: return false
        val id = rearDisplayId()
        if (id < 0) return false
        if (!gate(status())) return false
        return try { svc.ensureOnRear(id) } catch (e: Exception) {
            Log.e(TAG, "ensureOnRear failed", e); service = null; false
        }
    }

    /**
     * Whether the last payload THIS bridge instance was asked to show or update was
     * a calibrate payload — the watchdog's guard against re-placing a panel that is
     * mid-calibration (Task 15 Step 2b).
     *
     * DEAD CODE TODAY, on purpose, not by oversight: calibrate mode was reachable ONLY
     * via a manual `adb shell am broadcast ... --es state '{"mode":"calibrate"}'`, straight
     * to RearActivity's receiver — which no longer takes broadcasts from outside the app
     * (2026-10-30), so not even that now; the in-app Advanced geometry fields cover it.
     * Nothing in the app calls show()/update() with a calibrate
     * payload yet, so this field is always false in practice and the guard can
     * never fire — until Task 17 (or whatever adds an in-app calibrate control)
     * wires it through this bridge, which is the natural integration (it already
     * shares Graph with UsagePollService, exactly like the "Show on back" button
     * does today), not a change made "just to activate this guard."
     *
     * This is intent tracking, not a live query, and that distinction matters here:
     * RearActivity runs in its own process (:rear), so once it dies there is
     * nothing left to ask "are you calibrating?" — this field is deliberately kept
     * in THIS process instead, specifically so it survives that death. That means
     * it is NOT equivalent to "RearActivity is currently rendering calibrate mode
     * right now" (which would trivially go false the instant the activity dies) —
     * it is "the last thing we told it to show was calibrate," which is exactly
     * what lets Watchdog.decide() still choose CALIBRATING (not DEAD) for a task
     * that crashed mid-calibration, so a crash there is left dead rather than
     * silently resurrected into the normal dial view. Once a calibrate control
     * exists and uses this bridge, that crash-recovery behaviour is real, not
     * merely documented.
     */
    /**
     * Ask the privileged service to put the system camera on the rear display.
     *
     * Silent on failure by design. This is triggered by a tap on the back of a phone
     * nobody is looking at a screen for — there is nowhere to show an error, and the
     * camera not appearing IS the message.
     */
    fun openCamera() {
        val display = rearDisplayId()
        if (display < 0) return
        runCatching { service?.openCamera(display) }
            .onFailure { Log.w(TAG, "camera launch failed: ${it.message}") }
    }

    /** The pinned apps' names and icons, kept to hand on whenever the service (re)connects. */
    @Volatile private var portalApps: android.os.Bundle? = null

    /**
     * Hands [apps] to the shell service, now or — not connected — when it connects. False
     * when the call failed (too big for the binder's room just then, the service gone):
     * the caller sends again later, since an equal load would otherwise never be re-sent.
     */
    fun setPortalApps(apps: android.os.Bundle): Boolean {
        portalApps = apps
        if (service == null) { bind(); return true }
        return runCatching { service?.setPortalApps(apps) }.onFailure {
            Log.w(TAG, "portal apps: ${it.message}")
            if (it is android.os.DeadObjectException) service = null
        }.isSuccess
    }

    /** Blocks while waiting for the service: call off the main thread. */
    fun sendFocusedToBack(): String {
        val display = rearDisplayId()
        if (display < 0) return ""
        val svc = awaitService() ?: return ""
        return runCatching { svc.sendFocusedToBack(display).orEmpty() }
            .onFailure {
                Log.w(TAG, "send to back failed: ${it.message}")
                if (it is android.os.DeadObjectException) service = null
            }.getOrDefault("")
    }

    /**
     * Light the rear panel, if it has gone to sleep.
     *
     * The display dozes on its own to save battery, and a panel nobody woke is a panel
     * that missed whatever it was about to show — a notification arrives, the strip
     * animates, and the whole five seconds happen on a dark screen. So the things worth
     * looking at ask for the light first.
     *
     * Aimed at ONE display, which is the entire reason this goes through the privileged
     * service rather than a window flag. RearActivity used to carry FLAG_TURN_SCREEN_ON
     * and it woke the phone's main screen every time the panel was re-placed, because the
     * activity is created on display 0 before being moved. A keyevent can be pointed
     * somewhere; a flag on a window cannot.
     *
     * Silent on failure, like openCamera: there is nowhere to report it, and a rear panel
     * that stays dark is its own message.
     */
    /**
     * Light the rear display, for something that just happened on it.
     *
     * [reason] is only for the log, and it is there because this is the kind of thing
     * that fails silently: the panel is usually lit already, so a wake that never
     * happened looks exactly like one that did. When a charge or a notice turns out to
     * have animated in the dark, the question is whether this was reached at all.
     *
     * Costs nothing when the display is already on, which is most of the time.
     */
    fun wake(reason: String = ""): Boolean {
        // In a pocket (pocket mode switched the back off for it): a notification, the
        // charger, always-on after the lock — none of them lights the back inside it. What
        // arrived meanwhile is in the panel's list when the pocket is left, the slider up.
        if (pocketed) {
            if (reason.isNotEmpty()) Log.i(TAG, "wake($reason) declined — the back is in a pocket")
            return false
        }
        val display = rearDisplayId()
        if (display < 0) return false
        val svc = service
        if (svc == null) {
            Log.w(TAG, "wake($reason) with no service — display stays as it was")
            return false
        }
        // READ THE STATE BEFORE THE KEYEVENT, because afterwards there is nothing to
        // read. This is the only moment anyone can tell the difference between a panel
        // somebody is looking at and a panel that is about to light up in a pocket, and
        // that difference is the whole basis for locking. A fifth of a second later, when
        // the notification reaches the panel, both look identical.
        val wasDark = darkNow(display)
        // The other build (release vs dev) has the back: this install's notification,
        // charger or always-on is not a reason to light it. The shell checks, as it wakes.
        runCatching { svc.wakeOwn(display) }
            .onSuccess { woke ->
                if (!woke) {
                    Log.i(TAG, "wake($reason) skipped — the other build's panel has the back, or it is in a pocket")
                    return false
                }
                if (reason.isNotEmpty()) {
                    Log.i(TAG, "woke rear display for $reason (was dark: $wasDark)")
                }
            }
            .onFailure { Log.w(TAG, "wake($reason) failed: ${it.message}"); return false }
        // Somebody has decided the panel should be lit, so it is no longer asleep on
        // purpose. Cleared on this side too, not just in the service, or the watchdog here
        // would go on standing down over a panel that is wide awake.
        dozing = false
        return wasDark
    }

    /**
     * Whether the rear display is currently showing nothing.
     *
     * Anything that is not STATE_ON counts. The states this device actually reports are
     * ON, OFF and the two DOZE flavours, and a dozing panel is as unwatched as a dark
     * one — MIUI drops this display to DOZE_SUSPEND to save battery while the window
     * carries on believing it is visible. Treating only OFF as dark would miss the case
     * the user reported.
     *
     * An unknown display is NOT dark. Failing that way round means an unreadable state
     * leaves the panel usable rather than locking somebody out of their own phone.
     */
    private fun darkNow(displayId: Int): Boolean {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val state = dm.getDisplay(displayId)?.state ?: return false
        return state != android.view.Display.STATE_ON
    }

    fun isCalibrating(): Boolean = calibrating

    /**
     * The panel is asleep on purpose, or is not any more.
     *
     * Held HERE as well as forwarded, for the same reason `calibrating` is: the watchdog
     * in this process has to be able to ask, and asking across the binder would fail
     * exactly when the service is not bound — which is one of the states the watchdog
     * exists to notice.
     */
    fun setDozing(value: Boolean) {
        dozing = value
        runCatching { service?.setDozing(value) }
            .onFailure { Log.w(TAG, "setDozing($value) failed: ${it.message}") }
    }

    /**
     * Whether the App Portal's guest is in front of the panel on the back (IRearService.guestOnTop);
     * null with no service to ask. Never binds: asked at the lock, where not knowing is an answer.
     */
    fun guestOnTop(): Boolean? {
        val svc = service ?: return null
        return runCatching { svc.guestOnTop() }.onFailure { Log.w(TAG, "guestOnTop failed: ${it.message}") }.getOrNull()
    }

    /** Asleep on purpose as far as this process knows; not known counts as not. */
    fun isDozing(): Boolean = dozing == true

    /** The panel's last word on its hold: null until this process has heard one. */
    fun dozingOrNull(): Boolean? = dozing

    /**
     * The panel's lock slider / pocket lock / always-on is up, or not. Kept, like [dozing],
     * and handed on again whenever the service (re)connects: a new shell process starts
     * unguarded. Never binds — a panel that says this is already being served.
     */
    fun setPanelGuarded(value: Boolean) {
        panelGuarded = value
        runCatching { service?.setPanelGuarded(value) }
            .onFailure { Log.w(TAG, "setPanelGuarded($value) failed: ${it.message}") }
    }

    @Volatile private var panelGuarded = false

    /**
     * Pocket mode switched the back off for a pocket, and has not brought it back: [wake]
     * declines. Here as well as in the shell so the decline costs no binder call, and is
     * logged where the wake was asked for. Lost with this process — the shell's own holds.
     */
    @Volatile private var pocketed = false

    /** In a pocket as far as this process knows. */
    fun isPocketed(): Boolean = pocketed

    /**
     * Switch the back off for a pocket (IRearService.pocketSleep); false if it was not —
     * no service, or the shell refused. Blocking (an exec in the shell): off the main thread.
     */
    fun pocketSleep(): Boolean {
        val display = rearDisplayId()
        val svc = service ?: return false
        if (display < 0) return false
        val done = runCatching { svc.pocketSleep(display) }
            .onFailure { Log.w(TAG, "pocketSleep failed: ${it.message}") }.getOrDefault(false)
        if (done) pocketed = true
        return done
    }

    /**
     * Out of the pocket, the back switched on again when [light]; or the pocket called off,
     * the back left as it is. Wakes work again either way — here at once, in the shell when
     * the call lands. Blocking, like [pocketSleep].
     */
    fun pocketEnd(light: Boolean) {
        pocketed = false
        val display = rearDisplayId()
        runCatching { service?.pocketEnd(display, light) }
            .onFailure { Log.w(TAG, "pocketEnd($light) failed: ${it.message}") }
    }

    /**
     * Null until the panel (or a wake from this process) has said: a new process does not
     * know, and guessing "awake" is what overwrote the shell's correct "asleep" on connect.
     */
    @Volatile private var dozing: Boolean? = null

    /**
     * The control centre's tiles — silent, Bluetooth, mobile data, Wi-Fi — and whether the
     * last three can be switched, for the one the shell holds over a guest. Kept, and handed
     * on again whenever the service (re)connects. Never binds.
     */
    fun setQuickStates(states: BooleanArray, reachable: Boolean) {
        quickStates = states.copyOf() to reachable
        runCatching { service?.setQuickStates(states, reachable) }
            .onFailure { Log.w(TAG, "setQuickStates failed: ${it.message}") }
    }

    @Volatile private var quickStates: Pair<BooleanArray, Boolean>? = null

    /**
     * An app's DPI nudged on the back: [dpi] for the package, 0 for its default. Set by
     * Graph, which saves it in the config and pushes the payload back; run on the main
     * thread, where the settings screen writes the config too, so the two never interleave.
     */
    var onPortalDpi: (pkg: String, dpi: Int) -> Unit = { _, _ -> }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** What the shell service tells this process (IRearEvents); handed over on every (re)connect. */
    private val events = object : IRearEvents.Stub() {
        override fun onPortalDpi(pkg: String?, dpi: Int) {
            if (pkg.isNullOrEmpty() || pkg.length > 255) return
            mainHandler.post { this@ShizukuBridge.onPortalDpi(pkg, dpi) }
        }

        // The shell's control centre over a guest: its tiles go the way the panel's do, as
        // the broadcasts UsagePollService already serves (RearActivity.ACTION_SET_QUICK,
        // ACTION_GET_QUICK), which switch them and send the states back to both.
        override fun onQuick(which: String?, on: Boolean) {
            if (which !in QUICK) return
            context.sendBroadcast(
                android.content.Intent(RearActivity.ACTION_SET_QUICK).setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_QUICK, which).putExtra(RearActivity.EXTRA_QUICK_ON, on),
            )
        }

        override fun onControlCenterOpening() {
            context.sendBroadcast(android.content.Intent(RearActivity.ACTION_GET_QUICK).setPackage(context.packageName))
        }

        override fun onPortalOpened() {
            mainHandler.post { this@ShizukuBridge.onPortalOpened() }
        }

        // The shell's two words to the panel, passed on: the panel's receiver hears only this
        // app (RearActivity), and the shell is uid 2000.
        override fun onPanelPoke() = toPanel(RearActivity.ACTION_PORTAL_POKE)

        override fun onPanelStepBack() = toPanel(RearActivity.ACTION_PORTAL_STEP_BACK)
    }

    /** A broadcast to the panel, from inside the app — the only sender its receiver takes. */
    private fun toPanel(action: String, state: String? = null) {
        val intent = android.content.Intent(action).setPackage(context.packageName)
        if (state != null) intent.putExtra(RearActivity.EXTRA_STATE, state)
        context.sendBroadcast(intent)
    }

    /** The portal sheet was opened on the back (IRearEvents.onPortalOpened). Set by Graph; run on the main thread. */
    var onPortalOpened: () -> Unit = {}

    private companion object {
        const val TAG = "AiUsageBridge"
        /** The tiles the shell may ask to switch (ControlCenterPainter.QUICK_NAMES); nothing else. */
        val QUICK = ControlCenterPainter.QUICK_NAMES.toSet()
    }
}
