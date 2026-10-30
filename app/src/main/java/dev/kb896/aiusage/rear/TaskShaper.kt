package dev.kb896.aiusage.rear

import android.graphics.Rect
import android.os.IBinder
import android.util.Log

/**
 * A task's windowing mode and bounds, set as the system's own shell sets them: a
 * WindowContainerTransaction (setWindowingMode, setBounds) on the task's token, applied
 * through IActivityTaskManager.getWindowOrganizerController().applyTransaction — which shell
 * may call, holding MANAGE_ACTIVITY_TASKS. A guest on the whole rear is asserted fullscreen by it
 * after every move: a task left in multi-window by anything is reused so by the app's next launch.
 * By reflection, looked up once; false (logged once) where any of it is missing or refused, and
 * the caller falls back to `am task resize`.
 *
 * NEVER AS A TRANSITION. For a while (cf55517) the transaction was started as a CHANGE
 * transition (IWindowOrganizerController.startNewTransition), since only a transition moved
 * the surface of a multi-window task MIUI's split organiser holds. That crashed system_server
 * (2026-10-29 08:23, a soft restart): a transition started from our shell, which plays none,
 * is never finished or collected, and each one leaked a `Transition$Token` JNI global
 * reference in system_server — "global reference table overflow (max=51200)" with 48553 of
 * them, run up by the keep-alive's nudges over hours. So applyTransaction only, and callers
 * must keep every call bounded (RearUserService gives a guest a few goes, GuestShape.GiveUp).
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

    /** Whether this firmware has what it takes at all (looked up once); false: `am task resize` alone. */
    val available: Boolean get() = api != null

    /**
     * [task] in windowing [mode] with [bounds] (null: none of its own — fullscreen fills its
     * display), applied as a plain transaction — never a transition (see the class comment).
     * True when it was applied.
     */
    fun apply(task: Int, mode: Int, bounds: Rect?): Boolean {
        val a = api ?: return false
        return try {
            val infos = a.rootTasks.invoke(a.atm) as List<*>
            val info = infos.firstOrNull { it != null && a.taskId.getInt(it) == task } ?: return false
            val token = a.token.get(info) ?: return false
            val t = a.wct.getDeclaredConstructor().newInstance()
            a.setMode.invoke(t, token, mode)
            a.setBounds.invoke(t, token, bounds ?: Rect())
            val organizer = a.organizer.invoke(a.atm) ?: return false
            organizer.javaClass.getMethod("applyTransaction", a.wct).apply { isAccessible = true }.invoke(organizer, t)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); false
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            if (!missingLogged) { missingLogged = true; Log.w(TAG, "task shaper: refused (${cause.javaClass.simpleName}: ${cause.message}); am task resize instead") }
            false
        }
    }
}
