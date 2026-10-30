package dev.kb896.aiusage.rear

import android.graphics.Rect
import android.os.IBinder
import android.util.Log

/**
 * What the system says of a task, typed: IActivityTaskManager.getAllRootTaskInfos, which shell may
 * read, as TaskShaper does. A read and nothing else, no window organizer in it, so it may be asked
 * on a keep-alive tick or in a short watch.
 *
 * WHERE THE APP REALLY IS. A portrait-locked app on a private display's landscape task is
 * letterboxed: measured 2026-10-30, Bolt at 240 dpi, activity bounds Rect(70, 0 - 539, 572) in a
 * 608x572 task. TaskInfo.appCompatTaskInfo carries that, and is checked on this firmware
 * (OS4.0.5.0 framework.jar and services.jar, by dexdump): AppCompatUtils.fillAppCompatTaskInfo
 * sets topActivityLetterboxBounds to the activity's getBounds() when areBoundsLetterboxed(), and
 * topActivityAppBounds always, and AppCompatTaskInfo.writeToParcel writes both. TaskInfo's
 * topActivityMainWindowFrame will not do: it is null when the window fits the activity's bounds.
 * Every field by reflection, looked up once; where any is missing, [Unread], and the caller keeps
 * what it has.
 */
internal object TaskRead {

    private const val TAG = "AiUsageRear"

    sealed interface Read
    /** The task as it is: its top activity's rectangle in its display's pixels (null unread), and whether it is asked to be visible. */
    data class Seen(val bounds: GuestShape.Bounds?, val visibleRequested: Boolean?) : Read
    /** No such task. */
    data object Gone : Read
    /** The system could not be asked: says nothing about the task. */
    data object Unread : Read

    private class Api(
        val atm: Any,
        val rootTasks: java.lang.reflect.Method,
        val taskId: java.lang.reflect.Field,
        val appCompat: java.lang.reflect.Field,
        val letterbox: java.lang.reflect.Field,
        val appBounds: java.lang.reflect.Field,
        val visibleRequested: java.lang.reflect.Field,
    )

    private val api: Api? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as IBinder
            val info = Class.forName("android.app.TaskInfo")
            val compat = Class.forName("android.app.AppCompatTaskInfo")
            Api(
                atm = Class.forName("android.app.IActivityTaskManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!,
                rootTasks = Class.forName("android.app.IActivityTaskManager").getMethod("getAllRootTaskInfos"),
                taskId = info.getField("taskId"),
                appCompat = info.getField("appCompatTaskInfo"),
                letterbox = compat.getField("topActivityLetterboxBounds"),
                appBounds = compat.getField("topActivityAppBounds"),
                visibleRequested = info.getField("isVisibleRequested"),
            )
        }.onFailure { Log.w(TAG, "task read: not on this firmware (${(it.cause ?: it).message}); the frame stays on the whole area") }.getOrNull()
    }

    @Volatile private var refusedLogged = false

    fun of(task: Int): Read {
        val a = api ?: return Unread
        return try {
            val infos = a.rootTasks.invoke(a.atm) as List<*>
            val info = infos.firstOrNull { it != null && a.taskId.getInt(it) == task } ?: return Gone
            val compat = a.appCompat.get(info)
            val rect = compat?.let { (a.letterbox.get(it) as? Rect) ?: (a.appBounds.get(it) as? Rect) }
            Seen(rect?.let { GuestShape.Bounds(it.left, it.top, it.right, it.bottom) }, a.visibleRequested.getBoolean(info))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); Unread
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            if (!refusedLogged) { refusedLogged = true; Log.w(TAG, "task read: refused (${cause.javaClass.simpleName}: ${cause.message})") }
            Unread
        }
    }
}
