package dev.kb896.aiusage.rear

import android.content.Context
import android.util.Log
import dev.kb896.aiusage.IRearService
import dev.kb896.aiusage.Panel
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

    override fun show(payload: String, displayId: Int) {
        if (displayId < 0) return
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
        exec("am start -n $PKG/$ACTIVITY --es state ${shellQuote(payload)}")
        val task = pollTaskId()
        if (task <= 0) { Log.e(TAG, "RearActivity task never appeared"); return }
        val moved = moveToDisplay(task, displayId)
        Log.d(TAG, "move task=$task -> display=$displayId : $moved")

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
     * On MIUI, the component and flags are read off a live trace of opening the rear
     * camera by hand: com.xiaomi.subscreencenter starts com.android.camera/.Camera with
     * these five flags and no extras, and the camera app then brings up its own
     * companion UI on the front by itself. Nothing here has to arrange that half.
     *
     * That component is Xiaomi-specific — a name AOSP-style enough to look generic, and
     * wrong everywhere else. Samsung's stock camera is a different package with a
     * class name that has moved across One UI versions, so hardcoding another guess
     * would repeat the mistake the autostart step already made once for MIUI. Off MIUI
     * this launches the STANDARD still-camera intent action instead — resolved by the
     * system to whatever the default camera app is, which is exactly what the button
     * asks for, and stays correct as camera apps update or a user changes their default.
     * The AIDL's own doc for this method already draws the line at "a camera", not "an
     * arbitrary component"; a fixed system action is that same bound, portably.
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
        val cmd = if (isMiui()) "am start --display $displayId -f $CAMERA_FLAGS -n $CAMERA_COMPONENT"
        else "am start --display $displayId -a $CAMERA_ACTION"
        val out = exec(cmd)
        val ok = !out.contains("Error", ignoreCase = true)
        if (!ok) Log.w(TAG, "camera launch refused: ${out.take(200)}")
        if (ok) {
            cameraLaunched = true
            cameraWasOnTop = false
        }
        return ok
    }

    /** Whether this is a MIUI/HyperOS device — see [openCamera]'s KDoc for why it matters. */
    private fun isMiui(): Boolean =
        android.os.Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)

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
    private fun closeCamera() {
        cameraLaunched = false
        cameraWasOnTop = false
        Log.i(TAG, "camera dismissed and the panel is back — releasing it")
        exec("am kill $CAMERA_PACKAGE")
    }

    override fun ensureOnRear(displayId: Int): Boolean {
        if (displayId < 0) return false
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
     * "Nowhere" USED TO BE ONE ANSWER and it was two, which is the blind spot this enum
     * exists to close. The panel not being composited means either that it is asleep —
     * ordinary, and nothing to act on — or that something else has taken the rear display
     * while our task sits behind it, alive and invisible. Both read as null, so the
     * keep-alive's "composited nowhere AND no task" branch never fired for the second one:
     * the task is precisely what still exists. Observed on device with the rear display
     * showing HyperOS's own clock-and-steps widget, RearActivity paused in the background,
     * the service running, and every health check reporting a healthy panel.
     *
     * The two are told apart by what ELSE the rear section lists. A display composing
     * another app's layers is a display that is on and showing something; a display
     * composing nothing at all is asleep.
     */
    private enum class Where {
        /** On the rear display. The only healthy answer. */
        REAR,

        /** On the main display, over whatever the user is actually doing. */
        MAIN,

        /** Nowhere, while something else holds the rear display. Alive and invisible. */
        DISPLACED,

        /** Nowhere, and nothing else is there either: the panel is asleep. */
        NOWHERE,
    }

    /**
     * A layer name line in a HWC section: a window name ending in `#<id>`.
     *
     * Matched rather than counting lines, because a section always carries column
     * headings and rules whether or not it is composing anything. Names are truncated
     * with `[...]` in the middle when long, so this anchors on the end.
     */
    private val layerName = Regex("""^\s*\S+#\d+\s*$""", RegexOption.MULTILINE)

    private fun panelWhere(): Where {
        val out = exec("dumpsys SurfaceFlinger")
        // No per-display composition section in this dump at all — a newer SurfaceFlinger
        // format, confirmed on a Galaxy Z Flip 6 running Android 16, where this text does
        // not exist anywhere in the output. Composition cannot answer the question here,
        // so fall back to ActivityTaskManager's own view instead of defaulting to NOWHERE
        // — which is what silently made every DISPLACED strand permanent on that device:
        // Samsung's cover-display home task (SubHomeActivity) takes the front on every
        // lock/unlock, and this fallback is what lets the keep-alive notice and recover.
        // See ActivityDump's KDoc for the failure this closes.
        if (!out.contains("HWC layers:")) return taskDisplayWhere()

        val header = Regex("""Display (\d+) \((active|inactive)\) HWC layers:""")
        val matches = header.findAll(out).toList()
        if (matches.isEmpty()) return Where.NOWHERE

        var onRear = false
        var onMain = false
        // Whether the rear display is composing ANYTHING, ours or not. This is what
        // separates an asleep panel from a hijacked one.
        var rearBusy = false
        // And whether that display is powered at all. SurfaceFlinger says so in the same
        // header — measured: the front display read "(inactive)" with mScreenState=OFF
        // while the rear read "(active)" with mScreenState=ON, in one dump.
        var rearActive = false
        for ((i, m) in matches.withIndex()) {
            val start = m.range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else out.length
            val section = out.substring(start, end)
            val isRear = m.groupValues[1].endsWith(REAR_PHYSICAL_ID_SUFFIX)
            if (isRear) {
                if (m.groupValues[2] == "active") rearActive = true
                if (layerName.containsMatchIn(section)) rearBusy = true
            }
            if (!section.contains("RearActivity")) continue
            if (isRear) onRear = true else onMain = true
        }
        // ON MAIN WINS, even when it is also on the rear. Observed on device: the panel
        // drawing as a 904x572 box over the user's foreground app while
        // Activity.getDisplay() and `dumpsys window` both reported display 1 and the
        // layer appeared under BOTH display sections. Whatever the bookkeeping says, a
        // layer on the main display is pixels the user is looking at, and that is the
        // condition worth acting on.
        //
        // This reverses the earlier rule, which preferred "present on rear" to avoid
        // acting on a stale leftover. That caution was wrong for THIS layer: it made
        // the one genuinely visible failure undetectable. The cost of being wrong is a
        // redundant move, which is cheap and invisible; the cost of missing it is the
        // user staring at the panel on the wrong screen.
        return when {
            onMain -> Where.MAIN
            onRear -> Where.REAR
            // Our layer is nowhere. If the rear display is composing someone else's, it
            // is on and showing something that is not us, which is a failure that used to
            // be invisible. If it is composing nothing, the panel is simply asleep.
            //
            // "IS ON" IS NOT AN ASSUMPTION ANY MORE, IT IS CHECKED. A powered-down display
            // is composing nothing of ours and often something of MIUI's, which read as
            // "taken" — so the moment the panel deliberately let the display doze, the
            // watchdog re-placed it, and re-placing calls wake(). The panel woke itself
            // up about ten seconds after asking to sleep, every time, and the sleep looked
            // like it simply did not work.
            //
            // Nothing is composing on a dark display in any sense worth acting on. Falling
            // through to NOWHERE is the safe answer: that branch relaunches only when the
            // task is genuinely gone, so a panel that really did die while the display was
            // out is still recovered, and one that is merely dark is left alone.
            rearBusy && rearActive -> Where.DISPLACED
            else -> Where.NOWHERE
        }
    }

    /**
     * [panelWhere]'s fallback for a device whose SurfaceFlinger dump has no HWC layers
     * section — see [ActivityDump]'s KDoc for why this exists and what it closes.
     *
     * `dumpsys activity activities` names the same MIUI-style device-neutral marker
     * `panelWhere()` already matches ("RearActivity"), so the two stay consistent
     * without a second constant to keep in sync.
     */
    private fun taskDisplayWhere(): Where =
        when (
            ActivityDump.taskDisplay(
                exec("dumpsys activity activities"),
                activityMarker = "RearActivity",
                mainDisplayId = android.view.Display.DEFAULT_DISPLAY,
                rearDisplayId = Panel.DISPLAY_ID,
            )
        ) {
            TaskDisplay.REAR -> Where.REAR
            TaskDisplay.MAIN -> Where.MAIN
            TaskDisplay.DISPLACED -> Where.DISPLACED
            TaskDisplay.NOWHERE -> Where.NOWHERE
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
        /**
         * Tail of the rear panel's physical display id, per device — see
         * [dev.kb896.aiusage.DeviceProfile]. Matched as a suffix so the value does not
         * have to track the full 19-digit id, which appears in two different formats
         * across dumpsys outputs. (Xiaomi "332", Flip 6 cover "900".)
         *
         * This service runs in Shizuku's process, but it is the same APK, so `Build.MODEL`
         * and the profile resolve to the same device here as in the app.
         */
        val REAR_PHYSICAL_ID_SUFFIX: String get() = Panel.PHYSICAL_ID_SUFFIX

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

        /**
         * Standard still-camera action, resolved by the system to the default camera app.
         * Used off MIUI — see [openCamera]'s KDoc.
         */
        private const val CAMERA_ACTION = "android.media.action.STILL_IMAGE_CAMERA"

        /** The same app, without the component, for releasing it again. */
        private const val CAMERA_PACKAGE = "com.android.camera"

        /** NEW_TASK | SINGLE_TOP | EXCLUDE_FROM_RECENTS | RESET_TASK_IF_NEEDED | CLEAR_TASK */
        private const val CAMERA_FLAGS = "0x30a08000"
        const val PKG = "dev.kb896.aiusage"
        const val ACTIVITY = ".rear.RearActivity"
        const val ACTION_STATE = "dev.kb896.aiusage.STATE"
        const val ACTION_FINISH = "dev.kb896.aiusage.FINISH"
    }
}
