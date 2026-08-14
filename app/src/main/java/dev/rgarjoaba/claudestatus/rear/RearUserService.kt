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

    /** Last payload shown, so the keep-alive can re-place without the app's help. */
    private var lastPayload: String = "{}"
    private var keepAlive: Thread? = null

    override fun destroy() = exitProcess(0)

    /**
     * Whether the panel is actually THERE — composited on the rear display, not merely
     * recorded as a task.
     *
     * This used to be `taskId() > 0`, and that is the hole the panel kept falling into:
     * observed on device with RearActivity's window reporting mDisplayId=1 while
     * SurfaceFlinger composited it on NEITHER display, and the rear panel showed MIUI's
     * SubScreenLauncher. The task existed the whole time, so the watchdog read ALIVE
     * and never respawned — the panel stayed silently gone until someone noticed.
     *
     * Task existence answers "did something get created", which is not the question.
     * The question is "is the user looking at it", and composition is the only signal
     * on this device that answers it honestly.
     *
     * Both non-true answers count as dead, deliberately: compositing on the MAIN
     * display is a strand, and compositing nowhere is the silent death above. Respawn
     * is the right response to each, and it is bounded — the watchdog ticks every 20s
     * and defers entirely while the keyguard is up.
     */
    override fun isAlive(): Boolean = taskId() > 0 && compositingOnRear() == true

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
        lastPayload = payload
        startKeepAlive(displayId)
        // The cheap path — already placed, just push new numbers — must ALSO require
        // that the panel is genuinely composited. Checking only "same display and the
        // task exists" is how a silently-dead panel became unrecoverable: the task
        // outlives composition, so every subsequent show() took this branch and
        // broadcast an update to a window nobody was drawing, instead of relaunching.
        if (placedDisplayId == displayId && taskId() > 0 && compositingOnRear() == true) {
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
     * Pulls a stranded panel back onto the rear display. See the AIDL doc for why this
     * cannot live inside show().
     *
     * Deliberately does nothing unless compositingOnRear() is a definite false. An
     * unknown answer (panel asleep, layer not drawn, unparseable dump) must not move a
     * task: this runs every watchdog tick, and a check that acts on uncertainty would
     * shove a correctly-placed panel around the display topology every 20 seconds.
     */
    override fun ensureOnRear(displayId: Int): Boolean {
        if (displayId < 0) return false
        if (compositingOnRear() != false) return false
        val task = taskId()
        if (task <= 0) return false
        Log.w(TAG, "task $task stranded on the main display — moving it back to $displayId")
        exec("service call activity_task 50 i32 $task i32 $displayId")
        wake(displayId)
        placedDisplayId = displayId
        return true
    }

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
     * Deliberately asymmetric: false is returned ONLY when the layer is present on the
     * main display AND absent from the rear one. A capture taken while placement was
     * correct showed MainActivity's layer listed under BOTH display sections, so a
     * section listing a layer is not proof that display currently owns it — leftovers
     * linger. Under a "first section that mentions it wins" rule, one stale main-side
     * entry would report a strand that isn't happening and trigger a pointless move of
     * a correctly-placed panel. Requiring absence from the rear section costs nothing
     * in the real failure case, where the activity genuinely never reached the panel.
     *
     * Returns null when the output cannot be parsed or the layer appears nowhere (panel
     * asleep, activity not yet drawn), so an unknown answer never triggers a move.
     */
    private fun compositingOnRear(): Boolean? {
        val out = exec("dumpsys SurfaceFlinger")
        if (!out.contains("HWC layers:")) return null

        val header = Regex("""Display (\d+) \((?:active|inactive)\) HWC layers:""")
        val matches = header.findAll(out).toList()
        if (matches.isEmpty()) return null

        var onRear = false
        var onMain = false
        for ((i, m) in matches.withIndex()) {
            val start = m.range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else out.length
            if (!out.substring(start, end).contains("RearActivity")) continue
            if (m.groupValues[1].endsWith(REAR_PHYSICAL_ID_SUFFIX)) onRear = true else onMain = true
        }
        return when {
            onRear -> true
            onMain -> false
            else -> null
        }
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
    /**
     * Keeps the panel placed from INSIDE the Shizuku service, on its own thread.
     *
     * UsagePollService already runs a 20s watchdog doing the same job, and this does
     * not replace it — it survives what that one cannot. That watchdog lives in the
     * app process, so it dies with the app: an `adb install` (pm install -r kills the
     * app), a low-memory kill, or MIUI's background management all leave the panel
     * dead with nothing left running to notice. Observed exactly that on device — no
     * app process, no services, panel gone, and no path back without user action.
     *
     * This thread lives in Shizuku's user-service process at shell uid, which Shizuku
     * manages independently of the app's lifecycle, so it keeps checking through all
     * of the above.
     *
     * The two guards match the app-side watchdog's, for the same reasons:
     *   - nothing happens while the keyguard is up, because re-placing means `am start`
     *     on display 0, which on a locked phone draws over the lock screen and wakes
     *     the main display;
     *   - only a definite "not composited on the rear" triggers a re-place, so an
     *     unparseable dump or a momentary gap does not restart a healthy panel.
     */
    private fun startKeepAlive(displayId: Int) {
        if (keepAlive?.isAlive == true) return
        keepAlive = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(KEEPALIVE_MS)
                    if (keyguardShowing()) continue
                    if (compositingOnRear() == true) continue
                    Log.w(TAG, "keep-alive: panel not on the rear panel — re-placing")
                    show(lastPayload, displayId)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    Log.e(TAG, "keep-alive tick failed", e)
                }
            }
        }.apply { isDaemon = true; name = "rear-keepalive"; start() }
    }

    /** Cheap targeted read — `dumpsys window` in full is megabytes. */
    private fun keyguardShowing(): Boolean =
        exec("dumpsys window | grep -m1 isKeyguardShowing").contains("true")

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

        /**
         * Keep-alive period. Matches UsagePollService's watchdog rather than racing it:
         * both may re-place, and show() is idempotent when the panel is healthy (it
         * short-circuits to update+wake), so a duplicate tick costs one dumpsys.
         */
        const val KEEPALIVE_MS = 20_000L

        const val TAG = "ClaudeStatusRear"
        const val PKG = "dev.rgarjoaba.claudestatus"
        const val ACTIVITY = ".rear.RearActivity"
        const val ACTION_STATE = "dev.rgarjoaba.claudestatus.STATE"
        const val ACTION_FINISH = "dev.rgarjoaba.claudestatus.FINISH"
    }
}
