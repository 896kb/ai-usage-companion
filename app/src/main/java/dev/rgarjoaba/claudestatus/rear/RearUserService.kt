package dev.rgarjoaba.claudestatus.rear

import android.content.Context
import android.util.Log
import dev.rgarjoaba.claudestatus.IRearService
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

class RearUserService : IRearService.Stub {

    @Suppress("unused") constructor()
    @Suppress("unused") constructor(context: Context)

    private var placedDisplayId = -1

    override fun destroy() = exitProcess(0)

    /**
     * Task existence only — see the AIDL doc on IRearService.isAlive(). Deliberately
     * NOT `placedDisplayId == ... && taskId() > 0`: placedDisplayId is this
     * process's own in-memory bookkeeping and is meaningless after this service
     * process itself restarts (Shizuku respawns it independently of RearActivity's
     * lifecycle), whereas `am stack list` is always a live, authoritative read.
     */
    override fun isAlive(): Boolean = taskId() > 0

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
        // Composition-level check first: it is the only one that has never lied.
        // A failed move is now corrected by moving again rather than by tearing the
        // panel down — the user asked for recovery, not eviction, and a second move
        // usually lands when the first did not.
        if (compositingOnRear() == false) {
            Log.w(TAG, "compositing on the MAIN display — re-issuing the move")
            exec("service call activity_task 50 i32 $task i32 $displayId")
            wake(displayId)
            if (compositingOnRear() == false) {
                Log.e(TAG, "still on the MAIN display after a second move; finishing")
                hide()
                return
            }
        } else if (!isOnDisplay(displayId)) {
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
    /**
     * Whether SurfaceFlinger is actually COMPOSITING RearActivity on the rear panel.
     *
     * This is the only signal on this device that has not lied. Every window-manager
     * source — Activity.getDisplay(), `dumpsys window displays`, `am stack list` bounds
     * — reports display 1 while the panel renders visibly on the FRONT screen, and
     * panel power state cannot distinguish "our activity lit it" from "MIUI lit it".
     *
     * SurfaceFlinger is different in kind: `Display <id> (active|inactive) HWC layers:`
     * sections list what each display is composing. That is where pixels physically go,
     * not what the window manager believes about task ownership.
     *
     * Returns null when the output cannot be parsed, so an unknown answer never
     * triggers a corrective move.
     */
    private fun compositingOnRear(): Boolean? {
        val out = exec("dumpsys SurfaceFlinger")
        if (!out.contains("HWC layers:")) return null

        val header = Regex("""Display (\d+) \((?:active|inactive)\) HWC layers:""")
        val matches = header.findAll(out).toList()
        if (matches.isEmpty()) return null

        for ((i, m) in matches.withIndex()) {
            val start = m.range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else out.length
            val body = out.substring(start, end)
            if (body.contains("RearActivity")) {
                return m.groupValues[1].endsWith(REAR_PHYSICAL_ID_SUFFIX)
            }
        }
        // Layer not composited on any display yet (panel asleep, activity not drawn).
        // Unknown rather than wrong — do not act on it.
        return null
    }

    private fun isOnDisplay(displayId: Int): Boolean {
        val out = exec("dumpsys window displays")
        // A bare `indexOf("mDisplayId=$displayId")` would let displayId=1 match as a
        // prefix of mDisplayId=10, =11, ... =19. The lookahead anchors so the digit
        // run cannot continue past the id we're actually looking for.
        val marker = Regex("Display: mDisplayId=$displayId(?!\\d)").find(out) ?: return false
        val start = marker.range.first
        val next = Regex("Display: mDisplayId=\\d+").find(out, start + marker.value.length)
        val section = if (next == null) out.substring(start) else out.substring(start, next.range.first)
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
        /**
         * Tail of the rear panel's physical display id (local:4630946949513469332).
         * Matched as a suffix so the constant does not have to track the full 19-digit
         * value appearing in two different formats across dumpsys outputs.
         */
        const val REAR_PHYSICAL_ID_SUFFIX = "332"

        const val TAG = "ClaudeStatusRear"
        const val PKG = "dev.rgarjoaba.claudestatus"
        const val ACTIVITY = ".rear.RearActivity"
        const val ACTION_STATE = "dev.rgarjoaba.claudestatus.STATE"
        const val ACTION_FINISH = "dev.rgarjoaba.claudestatus.FINISH"
    }
}
