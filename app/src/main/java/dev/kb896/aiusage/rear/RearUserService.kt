package dev.kb896.aiusage.rear

import android.content.Context
import android.util.Log
import dev.kb896.aiusage.IRearService
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

class RearUserService : IRearService.Stub {

    @Suppress("unused") constructor()
    @Suppress("unused") constructor(context: Context)

    private var placedDisplayId = -1

    override fun destroy() = exitProcess(0)

    override fun hide() {
        // -p $PKG scopes the broadcast to our own app. The receiver stays exported
        // (the sender here is shell uid 2000, which an unexported receiver can't
        // reach), so without this any app declaring a matching intent-filter could
        // receive — or spoof — panel state. Do not drop this as "redundant."
        exec("am broadcast -p $PKG -a $ACTION_FINISH")
        placedDisplayId = -1
    }

    override fun update(payload: String) {
        // See hide() — same exported-receiver-plus-shell-sender reasoning.
        exec("am broadcast -p $PKG -a $ACTION_STATE --es state ${shellQuote(payload)}")
    }

    override fun wake(displayId: Int) {
        // Required. The activity is created on display 0 where the screen is
        // already on, so FLAG_TURN_SCREEN_ON has nothing to act on; without
        // this the task is placed and focused but the panel stays state=OFF.
        exec("input -d $displayId keyevent KEYCODE_WAKEUP")
    }

    override fun show(payload: String, displayId: Int) {
        if (displayId < 0) return
        if (placedDisplayId == displayId && taskId() > 0) {
            update(payload); wake(displayId); return
        }
        exec("am start -n $PKG/$ACTIVITY --es state ${shellQuote(payload)}")
        val task = pollTaskId()
        if (task <= 0) { Log.e(TAG, "RearActivity task never appeared"); return }
        // Transaction 50 = IActivityTaskManager#moveRootTaskToDisplay.
        val moved = exec("service call activity_task 50 i32 $task i32 $displayId")
        Log.d(TAG, "move task=$task -> display=$displayId : $moved")

        // Transaction 50 returns a success parcel even when it does nothing, so the
        // return value proves nothing. Confirm with the window manager.
        if (!isOnDisplay(displayId)) {
            Log.e(TAG, "move FAILED — task $task is not on display $displayId; finishing")
            hide()
            return
        }
        placedDisplayId = displayId
        wake(displayId)
    }

    /**
     * Asks the window manager which display actually owns the activity.
     *
     * Do NOT check this with `am stack list` bounds. A correctly placed panel and a
     * 904x572 box stranded at (0,0) on the main display report the SAME bounds, and
     * `grep RearActivity` discards the `Stack id=` header that would disambiguate
     * them. That ambiguity is what let a failed move strand the activity on the
     * user's main screen, fullscreen and immersive, until it was force-stopped.
     *
     * This is still a weak signal, not proof — `dumpsys` has been observed reporting
     * `mDisplayId=1` continuously while the activity visibly rendered on the front
     * screen. It is better than nothing as this service's own self-check, but it is
     * not evidence to present as confirmation of on-device placement to anyone.
     */
    private fun isOnDisplay(displayId: Int): Boolean {
        val out = exec("dumpsys window displays")
        val marker = "Display: mDisplayId=$displayId"
        val start = out.indexOf(marker)
        if (start < 0) return false
        val next = out.indexOf("Display: mDisplayId=", start + marker.length)
        val section = if (next < 0) out.substring(start) else out.substring(start, next)
        return section.contains("RearActivity")
    }

    private fun pollTaskId(): Int {
        repeat(20) {
            val id = taskId()
            if (id > 0) return id
            Thread.sleep(50)
        }
        return -1
    }

    private fun taskId(): Int = StackList.parseTaskId(exec("am stack list"), "RearActivity")

    /** Single-quote for `sh -c`, escaping embedded single quotes. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Runs via `sh -c` so PATH resolves am/service/input in the forked process. */
    private fun exec(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        p.waitFor()
        out.trim()
    } catch (e: Exception) {
        Log.e(TAG, "exec failed: $cmd", e); ""
    }

    private companion object {
        const val TAG = "ClaudeStatusRear"
        const val PKG = "dev.kb896.aiusage"
        const val ACTIVITY = ".rear.RearActivity"
        const val ACTION_STATE = "dev.kb896.aiusage.STATE"
        const val ACTION_FINISH = "dev.kb896.aiusage.FINISH"
    }
}
