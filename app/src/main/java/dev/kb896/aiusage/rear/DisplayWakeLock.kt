package dev.kb896.aiusage.rear

import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

/**
 * A bright screen wake lock on ONE display, held by the shell: IPowerManager.acquireWakeLock
 * with its displayId, which is what the hidden PowerManager.newWakeLock(level, tag, displayId)
 * comes down to, as `cmd power set-wakelock -d <id>` does.
 *
 * IPowerManager and not a PowerManager, because of the package: this process's Context (Shizuku
 * 13+) is OUR package's, while the caller is uid 2000, so a WakeLock from it would say a
 * package that is not the caller's. And the name "com.android.shell" is the truth.
 *
 * What the flags leave out matters as much as what they have. Not ACQUIRE_CAUSES_WAKEUP: it
 * must never light a dark back; on an asleep power group a screen lock simply does not count
 * (PowerManagerService.adjustWakeLockSummary strips it). Not ON_AFTER_RELEASE: letting go would
 * then be fresh user activity, the trap RearActivity.beginDimInner describes — a release that
 * lit the display straight back up. Bound to the display, so only the rear's power group is
 * kept up: the front's own timeout goes on as if this were not here.
 *
 * Should the process die, the system sees the token die with it and the lock goes (and a
 * SIGKILLed shell leaves nothing). By reflection, looked up once. A take that is refused — this
 * firmware lacks the call, or the shell the permission — is logged once and not asked again:
 * the rear then simply keeps HyperOS's own timeout, as it always did. Not thread-safe: the
 * caller's lock.
 */
class DisplayWakeLock(private val tag: String) {

    private class Api(val power: Any, val acquire: Method, val release: Method)

    private var token: IBinder? = null
    private var refused = false

    private val api: Api? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "power") as IBinder
            val power = Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val cls = Class.forName("android.os.IPowerManager")
            Api(
                power = power,
                // (lock, flags, tag, packageName, workSource, historyTag, displayId, callback)
                acquire = cls.getMethod(
                    "acquireWakeLock",
                    IBinder::class.java, Int::class.javaPrimitiveType, String::class.java, String::class.java,
                    android.os.WorkSource::class.java, String::class.java, Int::class.javaPrimitiveType,
                    Class.forName("android.os.IWakeLockCallback"),
                ),
                release = cls.getMethod("releaseWakeLock", IBinder::class.java, Int::class.javaPrimitiveType),
            )
        }.onFailure { Log.w(TAG, "portal: no display wake lock on this firmware (${(it.cause ?: it).message})") }.getOrNull()
    }

    /** Held on [displayId] now; false if it could not be had. */
    fun acquire(displayId: Int): Boolean {
        if (token != null) return true
        if (refused) return false
        val a = api ?: return false.also { refused = true }
        val t = Binder()
        return try {
            a.acquire.invoke(a.power, t, SCREEN_BRIGHT_WAKE_LOCK, tag, SHELL_PACKAGE, null, null, displayId, null)
            token = t
            true
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            refused = true
            Log.w(TAG, "portal: wake lock on display $displayId refused (${cause.javaClass.simpleName}: ${cause.message}) — not asked again")
            false
        }
    }

    /**
     * User activity on [displayId], as a touch would be: its own timeout starts over. By itself
     * it lights nothing dark. Failing costs only the tail, so it says so and carries on.
     */
    fun poke(displayId: Int) {
        val a = api ?: return
        try {
            a.power.javaClass.getMethod(
                "userActivity",
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).invoke(a.power, displayId, android.os.SystemClock.uptimeMillis(), 0, 0)
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Log.w(TAG, "portal: user activity on display $displayId failed (${cause.javaClass.simpleName}: ${cause.message})")
        }
    }

    fun release() {
        val t = token ?: return
        token = null
        val a = api ?: return
        try {
            a.release.invoke(a.power, t, 0)
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Log.w(TAG, "portal: wake lock release failed (${cause.javaClass.simpleName}: ${cause.message})")
        }
    }

    private companion object {
        const val TAG = "AiUsageRear"
        /** PowerManager.SCREEN_BRIGHT_WAKE_LOCK, deprecated for apps and exactly what is meant here. */
        const val SCREEN_BRIGHT_WAKE_LOCK = 0x0000000a
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
