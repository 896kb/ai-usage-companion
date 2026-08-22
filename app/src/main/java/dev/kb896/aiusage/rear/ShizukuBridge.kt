package dev.kb896.aiusage.rear

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import dev.kb896.aiusage.IRearService
import dev.kb896.aiusage.Panel
import rikka.shizuku.Shizuku

class ShizukuBridge(private val context: Context) {

    data class Status(val running: Boolean, val granted: Boolean, val rearDisplayId: Int) {
        val ready get() = running && granted && rearDisplayId >= 0
    }

    private var service: IRearService? = null
    private var pending: String? = null
    private var binderListenerArmed = false

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
                pending?.let { p -> pending = null; show(p) }
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
    @Volatile private var calibrating = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, RearUserService::class.java.name))
            .daemon(false).processNameSuffix("rear").debuggable(false).version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IRearService.Stub.asInterface(binder) else null
            pending?.let { p -> pending = null; show(p) }
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

    fun show(payload: String) {
        calibrating = CalibrateMode.isCalibrate(payload)
        val s = status()
        if (!s.ready) {
            // Hold the payload instead of dropping it. This is the normal state for the
            // first seconds after the app is killed and restarted: Shizuku has to
            // re-deliver its binder to the fresh process, so status().ready is false
            // even though everything is installed and granted. Dropping the request
            // here meant the panel could not return until the next 20s watchdog tick
            // happened to land after the binder arrived — measured at ~30s. Queued
            // plus the listener below, it goes back the moment Shizuku is reachable.
            Log.w(TAG, "not ready: $s — queuing")
            pending = payload
            armBinderListener()
            return
        }
        val svc = service ?: run { pending = payload; bind(); return }
        try { svc.show(payload, s.rearDisplayId) } catch (e: Exception) { Log.e(TAG, "show failed", e); service = null }
    }

    fun update(payload: String) {
        calibrating = CalibrateMode.isCalibrate(payload)
        try { service?.update(payload) } catch (e: Exception) { Log.e(TAG, "update failed", e); service = null }
    }

    fun hide() {
        try { service?.hide() } catch (e: Exception) { Log.e(TAG, "hide failed", e) }
    }

    /**
     * Asks the shell-uid service whether RearActivity's task still exists — see the
     * KDoc on IRearService.isAlive(). Not yet bound counts as "not alive": that is
     * exactly the state a killed panel would be in immediately after Shizuku itself
     * restarts, and the caller (UsagePollService's watchdog) already treats "not
     * alive" as "call show()", whose existing `pending` queue-and-bind handles an
     * unbound service correctly.
     */
    fun isPanelAlive(): Boolean {
        val svc = service ?: return false
        return try { svc.isAlive() } catch (e: Exception) { Log.e(TAG, "isAlive failed", e); service = null; false }
    }

    /**
     * Pulls the panel back to the rear display if it has been hoisted onto the main
     * one. Cheap and silent when nothing is wrong — see IRearService.ensureOnRear.
     */
    fun ensureOnRear(): Boolean {
        val svc = service ?: return false
        val id = rearDisplayId()
        if (id < 0) return false
        return try { svc.ensureOnRear(id) } catch (e: Exception) {
            Log.e(TAG, "ensureOnRear failed", e); service = null; false
        }
    }

    /**
     * Whether the last payload THIS bridge instance was asked to show or update was
     * a calibrate payload — the watchdog's guard against re-placing a panel that is
     * mid-calibration (Task 15 Step 2b).
     *
     * DEAD CODE TODAY, on purpose, not by oversight: calibrate mode is currently
     * reachable ONLY via the manual `adb shell am broadcast ... --es state
     * '{"mode":"calibrate"}'` workaround (see MainActivity's comment), which goes
     * straight to RearActivity's receiver and never touches ShizukuBridge/
     * IRearService. Nothing in the app calls show()/update() with a calibrate
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
        // Somebody has decided the panel should be lit, so it is no longer asleep on
        // purpose. Cleared on this side too, not just in the service, or the watchdog here
        // would go on standing down over a panel that is wide awake.
        dozing = false
        runCatching { svc.wake(display) }
            .onSuccess {
                if (reason.isNotEmpty()) {
                    Log.i(TAG, "woke rear display for $reason (was dark: $wasDark)")
                }
            }
            .onFailure { Log.w(TAG, "wake($reason) failed: ${it.message}"); return false }
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

    fun isDozing(): Boolean = dozing

    @Volatile private var dozing = false

    private companion object { const val TAG = "AiUsageBridge" }
}
