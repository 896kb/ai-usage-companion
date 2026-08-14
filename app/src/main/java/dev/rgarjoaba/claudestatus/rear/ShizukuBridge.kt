package dev.rgarjoaba.claudestatus.rear

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import dev.rgarjoaba.claudestatus.IRearService
import dev.rgarjoaba.claudestatus.Panel
import rikka.shizuku.Shizuku

class ShizukuBridge(private val context: Context) {

    data class Status(val running: Boolean, val granted: Boolean, val rearDisplayId: Int) {
        val ready get() = running && granted && rearDisplayId >= 0
    }

    private var service: IRearService? = null
    private var pending: String? = null

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
        if (!s.ready) { Log.w(TAG, "not ready: $s"); return }
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
    fun isCalibrating(): Boolean = calibrating

    private companion object { const val TAG = "ClaudeStatusBridge" }
}
