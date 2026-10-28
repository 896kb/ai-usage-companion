package dev.kb896.aiusage.rear

import android.graphics.Rect
import android.os.IBinder
import android.util.Log

/**
 * A task's windowing mode and bounds, set as the system's own shell sets them: a
 * WindowContainerTransaction (setWindowingMode, setBounds) on the task's token, started as a
 * CHANGE transition through IActivityTaskManager.getWindowOrganizerController() — which shell
 * may call, holding MANAGE_ACTIVITY_TASKS (see [change] for why a transition). Unlike `am task resize`, it can turn a fullscreen task (an app
 * open on the front, reused by a launch) into a multi-window one, and back to fullscreen
 * when a guest goes to the front. By reflection, looked up once; false (logged once) where
 * any of it is missing or refused, and the caller falls back to `am task resize`.
 */
internal object TaskShaper {

    private const val TAG = "AiUsageRear"

    private class Api(
        val atm: Any,
        val rootTasks: java.lang.reflect.Method,
        val organizer: java.lang.reflect.Method,
        val wct: Class<*>,
        val setMode: java.lang.reflect.Method,
        val setBounds: java.lang.reflect.Method,
        val taskId: java.lang.reflect.Field,
        val token: java.lang.reflect.Field,
    )

    @Volatile private var missingLogged = false

    private val api: Api? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as IBinder
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val iatm = Class.forName("android.app.IActivityTaskManager")
            val wct = Class.forName("android.window.WindowContainerTransaction")
            val tokenCls = Class.forName("android.window.WindowContainerToken")
            val info = Class.forName("android.app.TaskInfo")
            Api(
                atm = atm,
                rootTasks = iatm.getMethod("getAllRootTaskInfos"),
                organizer = iatm.getMethod("getWindowOrganizerController"),
                wct = wct,
                setMode = wct.getMethod("setWindowingMode", tokenCls, Int::class.javaPrimitiveType),
                setBounds = wct.getMethod("setBounds", tokenCls, Rect::class.java),
                taskId = info.getField("taskId"),
                token = info.getField("token"),
            )
        }.onFailure { Log.w(TAG, "task shaper: none on this firmware (${(it.cause ?: it).message}); am task resize alone") }.getOrNull()
    }

    /** TRANSIT_CHANGE (WindowManager.TRANSIT_CHANGE). */
    private const val TRANSIT_CHANGE = 6

    /**
     * [task] in windowing [mode] with [bounds] (null: none of its own — fullscreen fills its
     * display), as a CHANGE transition ([change]). True when it was sent.
     */
    fun apply(task: Int, mode: Int, bounds: Rect?): Boolean = change(task, mode, bounds ?: Rect())

    /**
     * [task]'s surface moved to where its [bounds] are: a CHANGE transition to bounds a pixel
     * off ([bounds] left + 1), [settleMs], then to [bounds]. A transition to the bounds it
     * already has does nothing (measured), so it must carry a real change. True when both
     * were sent; the caller reads where it is drawn after.
     */
    fun nudge(task: Int, bounds: Rect, settleMs: Long): Boolean {
        if (!change(task, null, Rect(bounds.left + 1, bounds.top, bounds.right, bounds.bottom))) return false
        Thread.sleep(settleMs)
        return change(task, null, bounds)
    }

    /**
     * The WindowContainerTransaction ([mode] if given, [bounds]) on [task]'s token, started as
     * a CHANGE transition — IWindowOrganizerController.startNewTransition. ROOT CAUSE, found on
     * the phone (2026-10-28): these multi-window tasks are organised by the system's shell
     * (MIUI's split organiser), and the window manager never moves an organised task's leash
     * itself; only a shell transition does. So applyTransaction, and `am task resize`, set the
     * bounds and left the surface at x 0, while a sleep and wake, or a close animation, — each a
     * transition — put it right. Measured: a CHANGE transition to 297..904 then to 296..904
     * moved it to x 296, 15–20 ms each. applyTransaction where there is no startNewTransition.
     */
    private fun change(task: Int, mode: Int?, bounds: Rect): Boolean {
        val a = api ?: return false
        return try {
            val infos = a.rootTasks.invoke(a.atm) as List<*>
            val info = infos.firstOrNull { it != null && a.taskId.getInt(it) == task } ?: return false
            val token = a.token.get(info) ?: return false
            val t = a.wct.getDeclaredConstructor().newInstance()
            if (mode != null) a.setMode.invoke(t, token, mode)
            a.setBounds.invoke(t, token, bounds)
            val organizer = a.organizer.invoke(a.atm) ?: return false
            val start = runCatching {
                organizer.javaClass.getMethod("startNewTransition", Int::class.javaPrimitiveType, a.wct).apply { isAccessible = true }
            }.getOrNull()
            if (start != null) start.invoke(organizer, TRANSIT_CHANGE, t)
            else {
                if (!noTransitionLogged) { noTransitionLogged = true; Log.w(TAG, "task shaper: no startNewTransition; applyTransaction, which may leave the surface where it was") }
                organizer.javaClass.getMethod("applyTransaction", a.wct).apply { isAccessible = true }.invoke(organizer, t)
            }
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); false
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            if (!missingLogged) { missingLogged = true; Log.w(TAG, "task shaper: refused (${cause.javaClass.simpleName}: ${cause.message}); am task resize instead") }
            false
        }
    }
    @Volatile private var noTransitionLogged = false
}
