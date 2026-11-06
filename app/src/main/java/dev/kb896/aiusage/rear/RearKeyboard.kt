package dev.kb896.aiusage.rear

import android.util.Log

/**
 * The keyboard on the back. Android sends the keyboard of an app on any display but the front one
 * to the front (DISPLAY_IME_POLICY_FALLBACK_DISPLAY), so a text field tapped on the back opened a
 * keyboard nobody could see. IWindowManager.setDisplayImePolicy(display, LOCAL) keeps it on that
 * display; the shell may call it (tried 2026-11-05: Gboard on the private display, typed into Brave).
 *
 * Nothing holds the setting for us: the private display is new each time a guest opens, and the
 * rear's own could be put back by anyone. So it is asked for wherever a guest arrives on the back
 * (see the callers of [showOn]) and set only when it is not already LOCAL: the rear's is written to
 * the system's display settings on each set, and reading is one cheap binder call.
 */
object RearKeyboard {
    private const val TAG = "AiUsageRear"
    /** WindowManager.DISPLAY_IME_POLICY_LOCAL. */
    private const val LOCAL = 0

    private class Api(val wm: Any, val get: java.lang.reflect.Method, val set: java.lang.reflect.Method)

    /**
     * Straight from the service manager, not WindowManagerGlobal.getWindowManagerService: that one
     * also sets the animator scale, and the first such call in this process throws "ApplicationSharedMemory
     * not initialized" (see RearCapture.retried). A lazy kept that first failure for good, so no
     * display was ever set (found 2026-11-06: display 18 still FALLBACK, nothing logged). Kept only
     * once found; a failure is logged and tried again on the next call.
     */
    @Volatile private var found: Api? = null

    private fun api(): Api? = found ?: try {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "window") as? android.os.IBinder ?: throw IllegalStateException("no window service")
        val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder) ?: throw IllegalStateException("no window manager")
        val iwm = Class.forName("android.view.IWindowManager")
        val int = Int::class.javaPrimitiveType
        Api(wm, iwm.getMethod("getDisplayImePolicy", int), iwm.getMethod("setDisplayImePolicy", int, int)).also { found = it }
    } catch (e: Exception) {
        Log.w(TAG, "keyboard on the back: no window manager (${(e.cause ?: e).message})")
        null
    }

    /** Keyboards of apps on [displayId] show there. False, logged, where the system says no. */
    fun showOn(displayId: Int): Boolean {
        if (displayId <= 0) return false
        val a = api() ?: return false
        return try {
            if (a.get.invoke(a.wm, displayId) == LOCAL) return true
            a.set.invoke(a.wm, displayId, LOCAL)
            val now = a.get.invoke(a.wm, displayId)
            Log.i(TAG, "keyboard on the back: display $displayId policy now $now")
            now == LOCAL
        } catch (e: Exception) {
            Log.w(TAG, "keyboard on the back: display $displayId refused (${(e.cause ?: e).message})")
            false
        }
    }
}
