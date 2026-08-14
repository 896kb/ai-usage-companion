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
        val s = status()
        if (!s.ready) { Log.w(TAG, "not ready: $s"); return }
        val svc = service ?: run { pending = payload; bind(); return }
        try { svc.show(payload, s.rearDisplayId) } catch (e: Exception) { Log.e(TAG, "show failed", e); service = null }
    }

    fun update(payload: String) {
        try { service?.update(payload) } catch (e: Exception) { Log.e(TAG, "update failed", e); service = null }
    }

    fun hide() {
        try { service?.hide() } catch (e: Exception) { Log.e(TAG, "hide failed", e) }
    }

    private companion object { const val TAG = "ClaudeStatusBridge" }
}
