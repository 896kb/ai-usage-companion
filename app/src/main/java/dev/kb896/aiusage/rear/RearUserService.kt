package dev.kb896.aiusage.rear

import android.content.Context
import android.util.Log
import dev.kb896.aiusage.BuildConfig
import dev.kb896.aiusage.IRearService
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.SurfaceDump.Where
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

    /** Consecutive failed attempts to move the panel off the main display. */
    private var strandStrikes = 0

    /** When the panel was last rebuilt from scratch, for the cooldown below. */
    private var lastReplaceAt = 0L

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
     * Only a DEFINITE false counts as dead. `null` means the layer is composited
     * nowhere, which is genuinely ambiguous: it is what a silently-dead panel looks
     * like, but it is ALSO what a blanked display looks like, because SurfaceFlinger
     * stops listing HWC layers for a display that is not being composed. Treating
     * unknown as dead made every screen-off look like a death and respawned the panel
     * through `am start` on display 0 — which is the takeover this whole area exists to
     * prevent. Ambiguity has to resolve to "leave it alone".
     */
    override fun isAlive(): Boolean {
        if (taskId() <= 0) return false
        // NOWHERE counts as alive: an asleep panel is not a broken one, and treating it
        // as dead is what once turned every screen-off into a relaunch. DISPLACED does
        // not — the task existing is exactly what makes that failure invisible.
        return when (panelWhere()) {
            Where.REAR, Where.NOWHERE -> true
            Where.MAIN, Where.DISPLACED -> false
        }
    }

    override fun hide() {
        // -p $PKG scopes the broadcast to our own app. The receiver stays exported
        // (the sender here is shell uid 2000, which an unexported receiver can't
        // reach), so without this any app declaring a matching intent-filter could
        // receive — or spoof — panel state. Do not drop this as "redundant."
        exec("am broadcast -p $PKG -a $ACTION_FINISH")
        placedDisplayId = -1
        // A hidden panel is a panel nobody wants back: the keep-alive would otherwise read
        // the empty rear display as "taken" and re-place it within a tick.
        keepAlive?.interrupt()
        keepAlive = null
    }

    override fun update(payload: String) {
        // See hide() — same exported-receiver-plus-shell-sender reasoning.
        exec("am broadcast -p $PKG -a $ACTION_STATE --es state ${shellQuote(payload)}")
    }

    /**
     * Whether the panel is asleep on purpose.
     *
     * Volatile because the keep-alive thread reads it and a binder thread writes it.
     */
    @Volatile private var dozing = false

    override fun setDozing(dozing: Boolean) {
        this.dozing = dozing
        Log.i(TAG, if (dozing) "standing down — the panel is asleep on purpose" else "resuming")
    }

    /**
     * The keeper, for phones whose firmware switches the rear display off ten seconds
     * after the last touch whatever the panel's window asks. A wake key sent to display
     * 1 counts as a touch there; every few seconds, while the panel is up and holding,
     * one goes out. MiRearScreenSwitcher does the same twice a second, and it is what
     * makes that app usable on those phones. Off unless the user turns it on.
     */
    @Volatile private var keeper = false
    private var keeperThread: Thread? = null

    override fun setKeeper(on: Boolean) {
        if (keeper == on) return
        keeper = on
        Log.i(TAG, if (on) "keeper: poking the rear display every ${KEEPER_MS / 1000}s while the panel holds it" else "keeper: off")
        if (!on) { keeperThread?.interrupt(); keeperThread = null; return }
        keeperThread = Thread {
            try {
                while (keeper && !Thread.currentThread().isInterrupted) {
                    if (!dozing && keepAlive?.isAlive == true) exec("input -d 1 keyevent KEYCODE_WAKEUP")
                    Thread.sleep(KEEPER_MS)
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; name = "rear-keeper"; start() }
    }

    override fun wake(displayId: Int) {
        // An explicit wake is somebody deciding the panel should be lit, which ends the
        // sleep by definition. Not gated on `dozing` — gating it here would make the
        // notification that wakes a dozing panel the one thing that cannot.
        dozing = false
        // Required. The activity is created on display 0 where the screen is
        // already on, so FLAG_TURN_SCREEN_ON has nothing to act on; without
        // this the task is placed and focused but the panel stays state=OFF.
        exec("input -d $displayId keyevent KEYCODE_WAKEUP")
    }

    /** show() and ensureOnRear() both place; two at once moved the same task twice and the second threw. */
    private val placing = Any()

    override fun show(payload: String, displayId: Int) = synchronized(placing) {
        if (displayId < 0) return@synchronized
        lastPayload = payload
        startKeepAlive(displayId)
        // The cheap path — already placed, just push new numbers — must ALSO require
        // that the panel is genuinely composited. Checking only "same display and the
        // task exists" is how a silently-dead panel became unrecoverable: the task
        // outlives composition, so every subsequent show() took this branch and
        // broadcast an update to a window nobody was drawing, instead of relaunching.
        if (placedDisplayId == displayId && taskId() > 0 && panelWhere() == Where.REAR) {
            // The payload still goes through — the panel should be correct whenever it is
            // next seen — but it is NOT lit for it. A status update is not a reason to
            // wake a display somebody deliberately let go.
            update(payload)
            if (!dozing) wake(displayId)
            return
        }
        // Straight onto the rear display first: the manifest's miui.rear.policy makes
        // HyperOS allow it (ActivityStarterImpl logs "allow app"), so the panel never
        // touches the front screen. HyperOS still aborts the start while the keyguard
        // is locked, for every app outside its own list — then no task appears, and the
        // old way is taken: start on the front screen, move the task across.
        exec("am start --display $displayId -n $PKG/$ACTIVITY --es state ${shellQuote(payload)}")
        var task = pollTaskId()
        // A start HyperOS aborts still leaves a task record for a moment, so one look
        // is not proof; the second, a beat later, is — an aborted start has no task
        // and nothing composited on the rear by then.
        if (task > 0 && panelWhere() == Where.REAR) {
            Thread.sleep(DIRECT_START_SETTLE_MS)
            task = taskId()
        }
        if (task > 0 && panelWhere() == Where.REAR) {
            Log.d(TAG, "task=$task started on display $displayId directly")
        } else {
            if (task <= 0) {
                Log.i(TAG, "start on display $displayId was refused (keyguard?) — starting on the front and moving")
                exec("am start -n $PKG/$ACTIVITY --es state ${shellQuote(payload)}")
                task = pollTaskId()
                if (task <= 0) { Log.e(TAG, "RearActivity task never appeared"); return }
            }
            val moved = moveToDisplay(task, displayId)
            Log.d(TAG, "move task=$task -> display=$displayId : $moved")
        }

        // A command that did not complain still proves only that it was accepted, not
        // that the panel is on screen. Confirm with the window manager.
        // Composition-level check first: it is the only one that has never lied.
        // A failed move is now corrected by moving again rather than by tearing the
        // panel down — the user asked for recovery, not eviction, and a second move
        // usually lands when the first did not.
        // Never hide() from here. Both branches used to, and they were the last
        // teardown paths left after verifyPlacement's and the activity's own eviction
        // guard were removed. They fired in the normal case, not an exceptional one:
        // immediately after `am start` the layer is often not composited yet, so
        // panelWhere() answers NOWHERE rather than MAIN, which fell through to
        // isOnDisplay() — the `dumpsys window displays` read that has lied in every
        // stranding incident in this project and also simply lags a fresh placement.
        // A false reading there destroyed the panel we had just created, and the
        // watchdog then rebuilt it, which is the panel dying for no reason.
        //
        // A second move is the whole remedy. If it does not take, leaving the activity
        // alone is strictly better than killing it: ensureOnRear() runs every keep-alive
        // tick and will move it later, whereas a finished activity has to be relaunched
        // through display 0.
        if (panelWhere() == Where.MAIN) {
            Log.w(TAG, "compositing on the MAIN display — re-issuing the move")
            moveToDisplay(task, displayId)
            wake(displayId)
        }
        placedDisplayId = displayId
        wake(displayId)
    }

    /**
     * Pulls a stranded panel back onto the rear display. See the AIDL doc for why this
     * cannot live inside show().
     *
     * Deliberately does nothing unless the panel is definitely on the MAIN display. An
     * unknown answer (panel asleep, layer not drawn, unparseable dump) must not move a
     * task: this runs every watchdog tick, and a check that acts on uncertainty would
     * shove a correctly-placed panel around the display topology every 20 seconds.
     *
     * DISPLACED is not this function's problem either, and that is not an oversight: the
     * task is already on the rear display, merely behind something, and moving a task to
     * the display it is already on does not raise it. That case needs a full placement —
     * see the keep-alive.
     */
    /**
     * Launch the system camera onto the rear display.
     *
     * The component and flags are MIUI's own, read off a live trace of opening the rear
     * camera by hand: com.xiaomi.subscreencenter starts com.android.camera/.Camera with
     * these five flags and no extras, and the camera app then brings up its own
     * companion UI on the front by itself. Nothing here has to arrange that half.
     *
     * Launching straight at the rear display works for the camera where it does not for
     * this app's own panel — which needs the start-then-move dance above — because the
     * restriction is on the caller, not the display. From shell uid it is simply allowed.
     *
     * The panel is not dismissed first: the camera stacks on top of it, and closing the
     * camera reveals it again. That is measured behaviour, not an assumption.
     */
    override fun openCamera(displayId: Int): Boolean {
        if (displayId < 0) return false
        // The panel puts a close button over the camera — the camera's rear-screen layout
        // has no way out of its own — and that button is an overlay window. Shell may
        // grant the app "display over other apps"; idempotent, so asked every time.
        exec("appops set $PKG SYSTEM_ALERT_WINDOW allow")
        val out = exec("am start --display $displayId -f $CAMERA_FLAGS -n $CAMERA_COMPONENT")
        val ok = !out.contains("Error", ignoreCase = true)
        if (!ok) Log.w(TAG, "camera launch refused: ${out.take(200)}")
        if (ok) {
            cameraLaunched = true
            cameraWasOnTop = false
        }
        return ok
    }

    /**
     * The camera we started, and whether we have yet seen it in front of the panel.
     *
     * NOTHING EVER CLOSED IT. The camera was launched onto the rear display and left
     * there: dismissing it takes its UI away and hands the display back, but the process
     * stays resident with its task still stacked under the panel. The hardware is
     * released — the camera service logs the DISCONNECT — so this is not a sensor left
     * running, but it is an application kept alive for nothing and a task that can be
     * resumed by accident.
     *
     * The pair of flags is what makes closing it safe. Seeing the panel on top is not on
     * its own evidence the camera is finished with; the panel is on top for the whole
     * time before the camera has come up too. Only a panel that is on top AFTER the
     * camera was seen in front of it means the user has closed it.
     */
    private var cameraLaunched = false
    private var cameraWasOnTop = false

    /**
     * `am kill` rather than `am force-stop`, and the difference matters here.
     *
     * kill declines to touch a process the system considers foreground, so if any part of
     * the camera is still in use — its companion UI on the front screen, a recording
     * still finishing — this does nothing at all. force-stop would take those with it.
     * Getting this wrong costs somebody their video; getting it too gently costs one
     * resident process until the next time.
     */
    /**
     * The close button on the back: Back, sent to the rear display — the camera's own way
     * out, which takes its front-screen half with it, finishes a recording properly and
     * hands the display back to the panel. Only while the camera has focus there, so a
     * Back meant for it never reaches anything else.
     */
    override fun closeCamera(displayId: Int): Boolean {
        if (displayId < 0) return false
        val focused = FocusDump.focusedPackage(exec("dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp='"), displayId)
        if (focused != CAMERA_PACKAGE) {
            Log.w(TAG, "camera close: display $displayId has $focused in focus, not the camera — no Back")
            return false
        }
        Log.i(TAG, "camera close: Back to display $displayId")
        exec("input -d $displayId keyevent KEYCODE_BACK")
        // Seen in front, so the panel coming back next releases its process — see below.
        if (cameraLaunched) cameraWasOnTop = true
        return true
    }

    private fun closeCamera() {
        cameraLaunched = false
        cameraWasOnTop = false
        Log.i(TAG, "camera dismissed and the panel is back — releasing it")
        exec("am kill $CAMERA_PACKAGE")
    }

    override fun ensureOnRear(displayId: Int): Boolean = synchronized(placing) {
        if (displayId < 0) return@synchronized false
        if (panelWhere() != Where.MAIN) { strandStrikes = 0; return false }
        val task = taskId()
        if (task <= 0) return false
        strandStrikes++
        Log.w(TAG, "task $task compositing on the MAIN display (strike $strandStrikes) — moving it back")
        moveToDisplay(task, displayId)
        wake(displayId)
        placedDisplayId = displayId

        // Last resort. If repeated moves will not get it off the main display, the
        // panel is sitting on top of whatever the user is actually doing, and no
        // amount of patience improves that. Tearing it down is the lesser harm — the
        // keep-alive re-places it afterwards, and a panel that flickers is better than
        // one parked over the foreground app.
        if (strandStrikes >= MAX_STRAND_STRIKES) {
            Log.e(TAG, "still on the main display after $strandStrikes moves — finishing")
            strandStrikes = 0
            hide()
        }
        return true
    }

    /**
     * Whether SurfaceFlinger is actually COMPOSITING RearActivity on the rear panel —
     * see [SurfaceDump] for why composition is the signal, and what each answer means.
     */
    private fun panelWhere(): Where {
        val rear = rearPhysicalId()
        return SurfaceDump.where(exec("dumpsys SurfaceFlinger")) { id ->
            if (rear != null) id == rear else id.endsWith(REAR_PHYSICAL_ID_SUFFIX)
        }
    }

    /** The rear display's physical id, once read. It belongs to the panel, so it never changes. */
    @Volatile private var knownRearId: String? = null
    private var warnedNoRearId = false

    /**
     * The physical id SurfaceFlinger knows the rear display by, read from the system.
     *
     * It was a constant — the tail of the 17 Pro's rear panel id — and on a Pro Max,
     * whose rear display is another panel, nothing matched it: the panel on the back read
     * as on the MAIN display, every tick tried to move it to the display it was already
     * on, the system refused, and three refusals finished it as stranded. See [SurfaceDump].
     *
     * Null when `dumpsys display` does not say, and then [panelWhere] falls back to the
     * 17 Pro's suffix, which is what it always did. Asked again next time rather than
     * remembered as missing: a dump that came back empty once is not a phone without one.
     */
    private fun rearPhysicalId(): String? {
        knownRearId?.let { return it }
        val dump = exec("dumpsys display | grep -E 'mDisplayId=|mPrimaryDisplayDevice='")
        val id = DisplayDump.physicalId(dump, Panel.DISPLAY_ID)
        if (id != null) {
            Log.i(TAG, "rear display ${Panel.DISPLAY_ID} is physical display $id")
            knownRearId = id
        } else if (!warnedNoRearId) {
            warnedNoRearId = true
            Log.w(TAG, "no physical id for display ${Panel.DISPLAY_ID} in dumpsys display — matching the 17 Pro's")
        }
        return id
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

    /**
     * The facts a bug report about the rear display needs, from the shell: the firmware,
     * every package that could own the rear screen and its state, the launcher
     * components registered for it, the rear-display settings, and what the window and
     * power managers say about display 1. Bounded by `head` everywhere; it is pasted
     * into a chat, not a file.
     */
    override fun rearReport(): String = exec(REPORT)

    override fun removeXiaomiRearApp(): Boolean {
        val out = exec("pm uninstall -k --user 0 ${Panel.XIAOMI_REAR_APP}")
        Log.i(TAG, "remove ${Panel.XIAOMI_REAR_APP}: $out")
        return out.contains("Success")
    }

    override fun restoreXiaomiRearApp(): Boolean {
        val out = exec("pm install-existing --user 0 ${Panel.XIAOMI_REAR_APP}")
        Log.i(TAG, "restore ${Panel.XIAOMI_REAR_APP}: $out")
        // Installed is not the same as allowed to run. A phone that kept this app out of
        // the way by SUSPENDING it (through shell, the other common workaround) gets it
        // back still suspended — present, dead, and looking exactly like a failed
        // restore. Seen on the Xiaomi: suspendingPackage=com.android.shell survived the
        // uninstall, the reinstall and a reboot. Shell suspended it, so shell may lift it.
        val lifted = exec("pm unsuspend --user 0 ${Panel.XIAOMI_REAR_APP}")
        Log.i(TAG, "unsuspend ${Panel.XIAOMI_REAR_APP}: $lifted")
        return out.contains("installed for user", ignoreCase = true) || out.contains("Success")
    }

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
    /**
     * Rebuild the panel, at most once per [REPLACE_COOLDOWN_MS].
     *
     * The cooldown is insurance against a fight rather than a normal-path delay. A single
     * takeover — the ordinary case — is recovered on the very next tick. But if the system
     * is REPEATEDLY bringing its own rear-screen launcher forward, replacing every tick
     * would put the panel and HyperOS in a visible tug of war, and losing that contest
     * slowly is better than losing it at six-second intervals in front of the user.
     *
     * Deliberately not a strike counter that gives up. It gives up on nothing: the panel
     * is meant to be there, and it keeps asking for as long as the app is running.
     */
    private fun replace(displayId: Int, why: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReplaceAt < REPLACE_COOLDOWN_MS) return
        lastReplaceAt = now
        Log.w(TAG, "keep-alive: $why — re-placing")
        show(lastPayload, displayId)
    }

    private fun startKeepAlive(displayId: Int) {
        if (keepAlive?.isAlive == true) return
        keepAlive = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(KEEPALIVE_MS)
                    // No keyguard skip. It was here because a respawn woke the main
                    // screen — RearActivity carried FLAG_TURN_SCREEN_ON and is created
                    // on display 0 before being moved. The flag is gone and the wake is
                    // now aimed at the rear display alone, so the watchdog can do its
                    // job while the phone is asleep, which is exactly when the panel
                    // needs it: double-tapping the rear display awake and finding
                    // nothing there was this skip.
                    
                    // NOTHING TO RECOVER ON A DISPLAY THAT IS OFF ON PURPOSE. Every
                    // reading below is about where the panel is being composited, and a
                    // dark display composites nothing of ours and often something of
                    // MIUI's — which reads as "taken" and gets the panel re-placed, and
                    // re-placing wakes it. Skipping the tick is also simply correct: the
                    // panel cannot be in the wrong place on a screen nobody can see, and
                    // the moment anything lights it again this resumes.
                    if (dozing) continue

                    // ONE reading per tick. Each call is a full `dumpsys SurfaceFlinger`,
                    // and the old code asked twice per tick for two of its branches —
                    // which also meant the two answers could disagree across the gap.
                    when (val where = panelWhere()) {
                        // Definitely on the main display: a move is enough, and a move
                        // is cheap and invisible. Never relaunch for this.
                        Where.MAIN -> ensureOnRear(displayId)

                        // Alive, and behind whatever has taken the rear display. A move
                        // cannot fix this — the task is already on the right display, it
                        // is simply not on top — so it needs the full placement, which
                        // goes out through display 0 and comes back to the front.
                        //
                        // This is the case that had no branch. HyperOS puts its own
                        // rear-screen launcher up and our panel goes on existing behind
                        // it: task alive, service running, nothing composited, every
                        // check reporting health, and the user looking at a clock.
                        Where.DISPLACED -> {
                            // Something is in front of the panel. If we put it there,
                            // note that it actually arrived — see closeCamera.
                            if (cameraLaunched) cameraWasOnTop = true
                            replace(displayId, "rear display taken")
                        }

                        // Composited nowhere AND no task: genuinely gone, relaunch.
                        // The task check is what makes this safe — "composited nowhere"
                        // on its own is also what a blanked display reports, and
                        // relaunching on that turned every screen-off into an `am start`
                        // on display 0.
                        Where.NOWHERE -> if (taskId() <= 0) {
                            replace(displayId, "no panel and no task")
                        }

                        // Back in front, and the camera was ahead of us until now:
                        // that is somebody closing it. Nothing else did.
                        Where.REAR -> if (cameraLaunched && cameraWasOnTop) closeCamera()
                    }
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

    /**
     * Moves [task] onto [displayId], and reports whether the command itself complained.
     *
     * `am display move-stack`, NOT `service call activity_task <n>`.
     *
     * This app used to issue binder transaction 50 directly, on the basis that 50 was
     * IActivityTaskManager#moveRootTaskToDisplay. Transaction numbers are assigned by
     * position in the AIDL, so they are not an ABI — they shift whenever a method is
     * added or removed above them. Android 16 shifted this one, and the failure mode was
     * the worst kind: `service call` does no type checking, so the call still returned a
     * success parcel (exception code 0) while doing nothing at all, and every check we
     * had downstream reported a panel that was simply never placed.
     *
     * The named command cannot drift like that. It has existed since Android 8, it is
     * resolved by name, and it prints a stack trace when it is unhappy instead of
     * silently succeeding.
     *
     * Not kept as a fallback, either: whatever slot 50 holds now is unknown, and calling
     * an arbitrary method on the activity manager because it once meant something else
     * is not a fallback, it is a loaded gun.
     */
    private fun moveToDisplay(task: Int, displayId: Int): Boolean {
        val out = exec("am display move-stack $task $displayId")
        val failed = out.contains("Exception", ignoreCase = true) ||
            out.contains("Error", ignoreCase = true)
        if (failed) Log.e(TAG, "move task=$task -> display=$displayId refused: $out")
        return !failed
    }

    private fun exec(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        p.waitFor()
        out.trim()
    } catch (e: Exception) {
        Log.e(TAG, "exec failed: $cmd", e); ""
    }

    private companion object {
        /** after a direct start on the rear display, before believing it took */
        private const val DIRECT_START_SETTLE_MS = 1_200L
        /** between two pokes of the keeper; the firmware's timer is ten seconds */
        private const val KEEPER_MS = 4_000L
        private val REPORT = """
echo "device: $(getprop ro.product.marketname) / $(getprop ro.product.model) / $(getprop ro.product.device)"
echo "os: HyperOS $(getprop ro.mi.os.version.name) $(getprop ro.mi.os.version.incremental) | android $(getprop ro.build.version.release) | region $(getprop ro.miui.region) | build $(getprop ro.build.version.incremental)"
echo "rear display: $(wm size -d 1 2>/dev/null | tr -d '\n') $(wm density -d 1 2>/dev/null | tr -d '\n')"
echo "physical ids: $(dumpsys display 2>/dev/null | grep -E 'mDisplayId=|mPrimaryDisplayDevice=' | head -8 | tr -s ' ' | tr '\n' ' ')"
echo "composited: $(dumpsys SurfaceFlinger 2>/dev/null | grep -oE 'Display [0-9]+ \((active|inactive)\) HWC layers' | tr '\n' ' ')"
echo "props: cover_mode=$(getprop persist.sys.dual_screen_cover_mode_enable) multidisplay_type=$(getprop persist.sys.muiltdisplay_type)"
echo "settings: subscreen_display_time=$(settings get system subscreen_display_time) rear_doze_always_on=$(settings get secure rear_doze_always_on) doze_always_on=$(settings get secure doze_always_on) stay_on_while_plugged_in=$(settings get global stay_on_while_plugged_in)"
echo
echo "packages that could own the rear screen:"
for p in $(pm list packages -u 2>/dev/null | sed 's/package://' | grep -iE 'subscreen|mirror|rearscreen|backscreen|tgwgroup' | sort -u); do
  st=$(dumpsys package ${'$'}p 2>/dev/null | grep -E 'User 0:|installed=|suspended=' | head -1 | tr -s ' ')
  echo "  ${'$'}p:${'$'}st"
done
echo
echo "system apps around the rear display: factory build or installed update (code path), and version:"
for p in com.xiaomi.subscreencenter com.xiaomi.mirror com.android.systemui miui.systemui.plugin com.miui.securitycenter com.xiaomi.misubscreenui; do
  cp=$(dumpsys package ${'$'}p 2>/dev/null | grep -E '^ *codePath=' | head -1 | sed 's/ *codePath=//')
  vn=$(dumpsys package ${'$'}p 2>/dev/null | grep -E '^ *versionName=' | head -1 | sed 's/ *versionName=//')
  [ -n "${'$'}cp" ] && echo "  ${'$'}p: ${'$'}vn @ ${'$'}cp"
done
echo "system apps running an installed update:"
pm list packages -s -f 2>/dev/null | grep '/data/app/' | sed 's/.*=//' | sort | xargs -n 5 | sed 's/^/  /'
echo
echo "proximity sensors (the panel's pocket mode wants the one on the back):"
dumpsys sensorservice 2>/dev/null | grep -i prox | grep -viE 'connection|active' | cut -c1-120 | sed 's/^/  /' | head -6
echo
echo "rear launcher components registered:"
dumpsys package 2>/dev/null | grep -oiE '[a-z0-9_.]+/[a-z0-9_.]*Sub[sS]creen[a-zA-Z]*Launcher[a-zA-Z]*' | sort -u | head -8
echo
echo "rear-display settings:"
for ns in system global secure; do settings list ${'$'}ns 2>/dev/null | grep -iE 'subscreen|sub_screen|rear_display|back_screen|rearscreen' | grep -v theme_rear_widget | sed "s/^/  ${'$'}ns: /"; done
echo
echo "--- waking the rear display, then watching it for 16 seconds ---"
logcat -c 2>/dev/null
input -d 1 keyevent KEYCODE_WAKEUP
sleep 3
echo "hold-screen window per display:"
dumpsys window 2>/dev/null | grep -E 'Display: mDisplayId=|mHoldScreenWindow' | tr '\n' ' ' | sed 's/  Display:/\n  Display:/g'; echo
echo "power groups (global, group 0, group 1):"
dumpsys power 2>/dev/null | grep -E 'mWakeLockSummary|mUserActivitySummary' | tr '\n' ' '; echo
dumpsys power 2>/dev/null | sed -n '/Secondary display power state/,/mAlwaysWakeUp/p'
for i in 1 2 3 4 5 6 7 8; do s=$(dumpsys display 2>/dev/null | grep -E 'mScreenState=' | sed 's/.*=//' | tr '\n' ' '); echo "  t=$((i*2))s screens(front,rear)=[${'$'}s]"; sleep 2; done
echo
echo "framework log since the wake (power / cover / rear):"
logcat -d 2>/dev/null | grep -E 'DualScreenCover|Going to sleep|Waking up|turned OFF|groupId\\[1\\]|Rear screen|SecondaryDisplay|goToSleep|userActivity.*displayId=1|subscreen_display_time' | grep -viE 'BrightnessEvent|MultiFinger|acquireWakeLock|releaseWakeLock|extCmdAndArgs' | tail -30
echo
echo "the panel's own log since the wake:"
logcat -d 2>/dev/null | grep -E 'AiUsageRear|AiUsagePoll' | grep -vE 'double tap: hidden' | tail -25
echo
echo "wake locks:"
dumpsys power 2>/dev/null | sed -n '/^Wake Locks:/,/^${'$'}/p' | head -12
"""

        /**
         * Tail of the 17 Pro's rear panel physical display id (local:4630946949513469332).
         * Only the fallback now, for when [rearPhysicalId] cannot read the real one: it
         * is the 17 Pro's panel, and nothing says another phone's rear display ends in it.
         */
        const val REAR_PHYSICAL_ID_SUFFIX = "332"

        /**
         * Keep-alive period. Matches UsagePollService's watchdog rather than racing it:
         * both may re-place, and show() is idempotent when the panel is healthy (it
         * short-circuits to update+wake), so a duplicate tick costs one dumpsys.
         */
        /**
         * How often the panel's presence is checked.
         *
         * Was 20s, which is a long time to look at the wrong screen. A tick is one
         * `dumpsys SurfaceFlinger` — measured at 40ms on this device — so at six seconds
         * this costs under one per cent of one core, and it used to spend two of those
         * calls per tick in some branches where it now spends one.
         *
         * The number is a trade against how long a failure is visible, not against
         * battery in any meaningful sense.
         */
        const val KEEPALIVE_MS = 6_000L

        /** The shortest gap between two full rebuilds. See replace(). */
        const val REPLACE_COOLDOWN_MS = 15_000L

        /** Corrective moves to try before evicting a panel stuck on the main display. */
        const val MAX_STRAND_STRIKES = 3

        const val TAG = "AiUsageRear"

        /** MIUI's camera, and the exact flags its own sub-screen launcher uses. */
        private const val CAMERA_COMPONENT = "com.android.camera/.Camera"

        /** The same app, without the component, for releasing it again. */
        private const val CAMERA_PACKAGE = "com.android.camera"

        /** NEW_TASK | SINGLE_TOP | EXCLUDE_FROM_RECENTS | RESET_TASK_IF_NEEDED | CLEAR_TASK */
        private const val CAMERA_FLAGS = "0x30a08000"
        /**
         * This build's own app id. A debug build is dev.kb896.aiusage.dev, installed
         * beside the release, and with the release's id written here a debug build
         * started, fed and finished the RELEASE app's panel instead of its own.
         */
        const val PKG = BuildConfig.APPLICATION_ID

        /** In full: `.rear.RearActivity` resolves against PKG, which is not the namespace on a debug build. */
        const val ACTIVITY = "dev.kb896.aiusage.rear.RearActivity"
        const val ACTION_STATE = "dev.kb896.aiusage.STATE"
        const val ACTION_FINISH = "dev.kb896.aiusage.FINISH"
    }
}
