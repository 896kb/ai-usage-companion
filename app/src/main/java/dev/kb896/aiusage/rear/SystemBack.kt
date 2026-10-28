package dev.kb896.aiusage.rear

import android.os.IBinder
import android.os.SystemClock
import android.window.BackEvent
import dev.kb896.aiusage.Panel
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Back for the guest on the rear, as the system's own back gesture does it — not a key.
 *
 * WHY NOT A KEY. On HyperOS (OS4.0.5.0, API 36) a BACK key on display 1 never reaches an
 * app that uses predictive back: MIUI's ViewRootImpl$QueuedInputEvent.shouldSkipIme() makes
 * every BACK with displayId 1 skip the pre-IME stages where such apps take it. Xiaomi's own
 * strip and `input keyevent` alike arrive and do nothing (spike tools/spikes/BackSpike.java,
 * spec "Back on the rear").
 *
 * WHAT WORKS — SystemUI's BackAnimationController's way, measured twice on Brave (~3 ms):
 * IActivityTaskManager.startBackNavigation(observer, adapter) hands back a
 * BackNavigationInfo for the globally focused window, with that window's
 * IOnBackInvokedCallback; then onBackStarted (a BackMotionEvent from the right edge),
 * setTriggerBack(true), onBackInvoked, and the navigation finished. At the app's root its
 * own callback runs (finish(), say), the task closes, and the rear falls back to our panel.
 *
 * Only the guest's: the focused window may be an app on the front, so the navigation is
 * finished undone, and nothing invoked, unless it is the guest's task (PortalAction.backStep).
 * Always finished, or the window manager keeps the navigation open. Focus is not moved
 * (IActivityTaskManager.focusTopTask would steal it from the front, and is untested).
 *
 * The adapter must not be null (system_server throws on it) and must have its animators set
 * (its writeToParcel throws without): a bare Binder for a runner, never called back.
 *
 * Every hidden method and constructor is looked up once ([api]); where any is missing,
 * there is no Back of ours, and [invoke] says so. Not thread-safe by design: one caller,
 * RearUserService's Back worker.
 */
internal object SystemBack {

    /** One Back's outcome, for the log: the step taken, the navigation's type, and how long it took. */
    data class Outcome(
        val step: PortalAction.BackStep?, val type: String, val focusedTask: Int, val ms: Long, val error: String? = null,
        /** The navigation's type as given (PortalAction.BACK_*), -1 for none. */
        val typeCode: Int = -1,
    )

    private class Api(
        val atm: Any,
        val start: Method,
        val remoteCallback: Constructor<*>,
        val listenerType: Class<*>,
        val adapter: Constructor<*>,
        val updateAnimators: Method,
        val runnerAsInterface: Method,
        val getType: Method,
        val getFocusedTask: Method,
        val getCallback: Method,
        val finished: Method,
        val onStarted: Method,
        val setTrigger: Method,
        val onInvoked: Method,
        val motion: Constructor<*>,
    )

    /** The hidden API, resolved once; null (and why, in [missing]) where this firmware lacks any of it. */
    private val api: Api? by lazy {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "activity_task") as IBinder
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val rc = Class.forName("android.os.RemoteCallback")
            val listener = Class.forName("android.os.RemoteCallback\$OnResultListener")
            val adapterCls = Class.forName("android.window.BackAnimationAdapter")
            val runnerCls = Class.forName("android.window.IBackAnimationRunner")
            val info = Class.forName("android.window.BackNavigationInfo")
            val cb = Class.forName("android.window.IOnBackInvokedCallback")
            val motionCls = Class.forName("android.window.BackMotionEvent")
            Api(
                atm = atm,
                start = Class.forName("android.app.IActivityTaskManager").getMethod("startBackNavigation", rc, adapterCls),
                remoteCallback = rc.getConstructor(listener),
                listenerType = listener,
                adapter = adapterCls.getConstructor(runnerCls),
                updateAnimators = adapterCls.getMethod("updateSupportedAnimators", ArrayList::class.java),
                runnerAsInterface = Class.forName("android.window.IBackAnimationRunner\$Stub").getMethod("asInterface", IBinder::class.java),
                getType = info.getMethod("getType"),
                getFocusedTask = info.getMethod("getFocusedTaskId"),
                getCallback = info.getMethod("getOnBackInvokedCallback"),
                finished = info.getMethod("onBackNavigationFinished", Boolean::class.javaPrimitiveType),
                onStarted = cb.getMethod("onBackStarted", motionCls),
                setTrigger = cb.getMethod("setTriggerBack", Boolean::class.javaPrimitiveType),
                onInvoked = cb.getMethod("onBackInvoked"),
                // (touchX, touchY, frameTime, progress, triggerBack, swipeEdge) on API 36.
                motion = motionCls.getDeclaredConstructor(
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true },
            )
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            missing = "${cause.javaClass.simpleName}: ${cause.message}"
            null
        }
    }
    @Volatile private var missing: String? = null

    /** Back for the guest's [guestTask], from the rear's right edge. Never throws. */
    fun invoke(guestTask: Int): Outcome {
        val began = SystemClock.uptimeMillis()
        fun took() = SystemClock.uptimeMillis() - began
        val a = api ?: return Outcome(null, "-", -1, took(), "no system back on this firmware ($missing)")
        var info: Any? = null
        var done = false
        try {
            info = a.start.invoke(a.atm, observer(a), adapter(a))
            val focused = info?.let { a.getFocusedTask.invoke(it) as Int } ?: -1
            val code = info?.let { a.getType.invoke(it) as Int } ?: -1
            val type = if (info != null) typeName(code) else "-"
            val cb = info?.let { a.getCallback.invoke(it) }
            val step = PortalAction.backStep(info != null, focused, guestTask, cb != null)
            if (step == PortalAction.BackStep.INVOKE) {
                val event = a.motion.newInstance(
                    (Panel.WIDTH - 1).toFloat(), Panel.HEIGHT / 2f, SystemClock.uptimeMillis(), 0f, false, BackEvent.EDGE_RIGHT,
                )
                // Started and triggered as the gesture would have them; their failure does not stop the Back itself.
                runCatching { a.onStarted.invoke(cb, event) }
                runCatching { a.setTrigger.invoke(cb, true) }
                a.onInvoked.invoke(cb)
                done = true
            }
            return Outcome(step, type, focused, took(), typeCode = code)
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            return Outcome(null, "-", -1, took(), "${cause.javaClass.simpleName}: ${cause.message}")
        } finally {
            // Always, or the window manager keeps the navigation open.
            if (info != null) runCatching { a.finished.invoke(info, done) }
        }
    }

    /** The navigation's observer: told when it is over; nothing for us to do then. */
    private fun observer(a: Api): Any {
        val listener = java.lang.reflect.Proxy.newProxyInstance(a.listenerType.classLoader, arrayOf(a.listenerType)) { _, _, _ -> null }
        return a.remoteCallback.newInstance(listener)
    }

    /** A BackAnimationAdapter whose runner is a bare Binder: we never animate, and are never called. */
    private fun adapter(a: Api): Any {
        val runner = a.runnerAsInterface.invoke(null, object : android.os.Binder() {})
        return a.adapter.newInstance(runner).also { a.updateAnimators.invoke(it, ArrayList<Int>()) }
    }

    private fun typeName(t: Int) = when (t) {
        -1 -> "UNDEFINED"; 0 -> "DIALOG_CLOSE"; 1 -> "RETURN_TO_HOME"; 2 -> "CROSS_ACTIVITY"
        3 -> "CROSS_TASK"; 4 -> "CALLBACK"; else -> "type $t"
    }
}
