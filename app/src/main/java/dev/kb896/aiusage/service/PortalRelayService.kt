package dev.kb896.aiusage.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.kb896.aiusage.IPortalCallback
import dev.kb896.aiusage.IPortalRelay

/**
 * Hands the panel's IPortalCallback to the shell service. The panel lives in the :rear
 * process and the Shizuku binding in this one; a binder passes through both untouched, so
 * after this hop the shell service calls the panel directly — one hop per relayed touch.
 *
 * Not Shizuku's own multi-process support: a second client process binding the user
 * service could, when it dies, take the service down with it, and :rear is relaunched
 * far more often than this process.
 */
class PortalRelayService : Service() {
    override fun onBind(intent: Intent): IBinder = object : IPortalRelay.Stub() {
        override fun register(callback: IPortalCallback?) {
            Graph.of(this@PortalRelayService).bridge.setPortalCallback(callback)
        }
    }
}
