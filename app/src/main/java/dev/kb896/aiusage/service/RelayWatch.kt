package dev.kb896.aiusage.service

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Keeps the notification relay bound.
 *
 * Android binds a granted listener by itself, and is meant to bind it again when its
 * process dies. HyperOS does not always: measured on the Xiaomi 17 Pro, the main process
 * restarted and the relay stayed unbound for sixteen hours with its access still granted —
 * missing from the live listeners, no notification reaching the back, nothing in the app
 * to say so. Re-granting the same access bound it again at once, so the access was fine
 * and only the binding was lost.
 *
 * So the poll service, which is always running, checks on its watchdog tick, and asks for
 * a rebind ([NotificationListenerService.requestRebind]) when access is granted and the
 * relay has not said it is connected.
 */
object RelayWatch {

    /** A bind just asked for is given this long to happen before it is asked for again. */
    const val RETRY_MS = 60_000L

    /** Set by the relay itself as Android connects and disconnects it (same process). */
    @Volatile
    var connected = false

    private var lastAskAt: Long? = null

    fun shouldRebind(granted: Boolean, connected: Boolean, sinceLastAskMs: Long?): Boolean =
        granted && !connected && (sinceLastAskMs == null || sinceLastAskMs >= RETRY_MS)

    fun check(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        if (!shouldRebind(granted, connected, lastAskAt?.let { now - it })) return
        lastAskAt = now
        Log.i(TAG, "notification relay not connected — asking for a rebind")
        runCatching {
            NotificationListenerService.requestRebind(ComponentName(context, NotificationRelay::class.java))
        }.onFailure { Log.w(TAG, "rebind refused: ${it.message}") }
    }

    private const val TAG = "AiUsageNotif"
}
