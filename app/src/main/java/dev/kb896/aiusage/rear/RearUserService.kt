package dev.kb896.aiusage.rear

import android.content.Context
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import dev.kb896.aiusage.BuildConfig
import dev.kb896.aiusage.IRearEvents
import dev.kb896.aiusage.IRearService
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.StatePayload
import dev.kb896.aiusage.rear.SurfaceDump.Where
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

class RearUserService : IRearService.Stub {

    @Suppress("unused") constructor()
    /**
     * Shizuku 13+ hands a Context of our package here: the shell sheet reads the panel's
     * font through its Resources (see ShellFont).
     */
    @Suppress("unused") constructor(context: Context) { this.context = context }

    private var context: Context? = null

    /** Volatile: the App Portal's binder calls and the keep-alive read it off the lock. */
    @Volatile private var placedDisplayId = -1

    /** Last payload shown, so the keep-alive can re-place without the app's help. */
    @Volatile private var lastPayload: String = "{}"
    private var keepAlive: Thread? = null

    /** Consecutive failed attempts to move the panel off the main display. */
    private var strandStrikes = 0

    /** When the panel was last rebuilt from scratch, for the cooldown below. */
    private var lastReplaceAt = 0L

    override fun destroy() {
        // The sheet's blocker must not outlive us: an untrusted layer left up takes every
        // touch on the rear. On this thread, since the process exits right after.
        closeSheetNow("the service is going", sync = true)
        stopTouchWatch()
        // The guest's density must not outlive the service that set it. Closed first, so
        // nothing re-applies one between this restore and the exit; and nothing read means
        // nothing was ever set, so no first read just to find that out.
        synchronized(densityLock) {
            densityClosed = true
            if (densityRead) {
                rereadNotBefore = 0L
                applyDensity(null, "the service is going")
                // One more go if it did not take. Past that there is nothing more to do here:
                // the marker setting is still written, so the next start finds the override
                // listed as ours and restores it (see readDensity).
                if (currentDensity != baselineDensity || !densityKnown) applyDensity(null, "the service is going (retry)")
            }
        }
        // DPI changes not yet handed on go now, or they are lost with us.
        flushDpi(all = true)
        exitProcess(0)
    }

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
        // A guest app in front of the panel is the panel kept behind ON PURPOSE. Read as
        // DISPLACED it is "dead" to the app's own watchdog, which then re-places the panel
        // over the guest within twenty seconds. Whether the guest is still really there is
        // the keep-alive's to check, every tick (GuestGate), and it re-places the panel
        // itself when the guest goes.
        if (guest != null) return true
        // NOWHERE counts as alive: an asleep panel is not a broken one, and treating it
        // as dead is what once turned every screen-off into a relaunch. DISPLACED does
        // not — the task existing is exactly what makes that failure invisible.
        return when (panelWhere()) {
            // There, with its task: this service may not be the one that placed it (see
            // [adoptPlaced]).
            Where.REAR, Where.NOWHERE -> { adoptPlaced(Panel.DISPLAY_ID, "isAlive"); true }
            Where.MAIN, Where.DISPLACED -> false
        }
    }

    /**
     * The panel found on the rear by a service that did not put it there — the app was
     * reinstalled, or this process restarted, with the panel left up. The app then only
     * update()s it (it reads alive), so [show] never runs, [placedDisplayId] stays -1,
     * and with it [touchWanted]: no touch watch, no pull, until the app restarts.
     * Measured on device after a reinstall. So whatever finds the panel placed adopts it
     * here: the display recorded, the touch watch started, and the keep-alive [show]
     * would have started. Once only — a panel already recorded is left alone, and
     * [hide] still clears it.
     */
    private fun adoptPlaced(displayId: Int, why: String) {
        if (displayId < 0 || placedDisplayId >= 0) return
        synchronized(placing) {
            if (placedDisplayId >= 0) return
            placedDisplayId = displayId
            Log.i(TAG, "the panel is already on display $displayId ($why) — adopting it")
            startKeepAlive(displayId)
        }
        syncTouchWatch()
    }

    override fun hide() {
        // -p $PKG scopes the broadcast to our own app. The receiver stays exported
        // (the sender here is shell uid 2000, which an unexported receiver can't
        // reach), so without this any app declaring a matching intent-filter could
        // receive — or spoof — panel state. Do not drop this as "redundant."
        exec("am broadcast -p $PKG -a $ACTION_FINISH")
        placedDisplayId = -1
        // Nothing of ours on the rear any more: no touches read, no sheet over it.
        syncTouchWatch()
        closeSheetNow("the panel was hidden")
        // A hidden panel is a panel nobody wants back: the keep-alive would otherwise read
        // the empty rear display as "taken" and re-place it within a tick.
        keepAlive?.interrupt()
        keepAlive = null
        // And with the keep-alive gone nothing would ever see a guest leave, so its frame
        // and its watcher would run on for good. The guest app itself is left where it is.
        // Always under the lock, so a launch in flight finishes first and is then undone,
        // rather than placing its guest after this looked and found none.
        synchronized(placing) { if (guest != null) dropGuest("the panel was hidden") }
        // dropGuest resets it when there was a guest; this covers there being none.
        syncDensity("the panel was hidden")
    }

    override fun update(payload: String) {
        // Kept like show()'s, so a later show(lastPayload) — endGuest's — does not bring
        // back an older config (its portalDpi with it).
        lastPayload = payload
        styleFrame(payload)
        // See hide() — same exported-receiver-plus-shell-sender reasoning.
        exec("am broadcast -p $PKG -a $ACTION_STATE --es state ${shellQuote(payload)}")
        // Only update()d, a panel this service did not place would never be recorded
        // ([adoptPlaced]). One `am stack list`, and only until it is.
        if (placedDisplayId < 0) {
            val list = exec("am stack list")
            val task = StackList.parseTaskId(list, "RearActivity")
            if (task > 0 && GuestTask.byId(list, task)?.display == Panel.DISPLAY_ID) adoptPlaced(Panel.DISPLAY_ID, "update")
        }
    }

    /**
     * Whether the panel is asleep on purpose.
     *
     * Volatile because the keep-alive thread reads it and a binder thread writes it.
     */
    @Volatile private var dozing = false

    override fun setDozing(dozing: Boolean) {
        this.dozing = dozing
        // The panel's own sleep timer can run out while it sits behind a guest; that is
        // not the rear going dark, and the guest on top keeps its frame. A rear that really
        // is dark is seen by the keep-alive (nothing on top) and hides it there.
        val guestOnTop = guest?.onTop == true
        if (dozing && !guestOnTop) frame.hide() else if (!dozing && guestOnTop) frame.show()
        // A sheet is never left up over a rear going to sleep: its blocker would outlive it.
        if (dozing) closeSheetNow("the panel is asleep")
        syncTouchWatch()
        Log.i(TAG, if (dozing) "standing down — the panel is asleep on purpose" else "resuming")
    }

    override fun wake(displayId: Int) {
        // An explicit wake is somebody deciding the panel should be lit, which ends the
        // sleep by definition. Not gated on `dozing` — gating it here would make the
        // notification that wakes a dozing panel the one thing that cannot.
        dozing = false
        syncTouchWatch()
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
        styleFrame(payload)
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
            syncTouchWatch()
            return
        }
        // About to come up over the guest, if there is one: it is no longer on top.
        val g = guest
        if (g != null) {
            if (markOnTop(g.task, false)) syncSheetGuest()
            frame.hide()
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
                if (task <= 0) { Log.e(TAG, "RearActivity task never appeared"); syncDensity("the panel never came up"); return }
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
        syncDensity("the panel is placed")
        placedDisplayId = displayId
        wake(displayId) // and the touch watch with it (syncTouchWatch)
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
        // The back is the camera's from here: a sheet up would sit over it with its blocker.
        closeSheetNow("the camera is opening")
        val out = exec("am start --display $displayId -f $CAMERA_FLAGS -n $CAMERA_COMPONENT")
        val ok = !out.contains("Error", ignoreCase = true)
        if (!ok) Log.w(TAG, "camera launch refused: ${out.take(200)}")
        if (ok) {
            cameraLaunched = true
            cameraWasOnTop = false
            cameraLaunchedAt = android.os.SystemClock.elapsedRealtime()
            watchBandTaps(displayId)
        }
        return ok
    }

    /**
     * Closing the camera from the back: a tap in its empty grey band.
     *
     * The camera's rear-screen layout has no way out of its own, and nothing can be drawn
     * over it — HyperOS refuses windows on the rear display to apps outside a short
     * whitelist. So the tap is read from the rear touchscreen itself, which shell may read,
     * for as long as the camera we opened is up (see [BandTap]); the camera ignores taps
     * there. The answer is Back to the rear display, the camera's own exit, which takes its
     * front-screen half with it and finishes a recording properly — sent only while the
     * camera has focus there, so it never reaches anything else.
     */
    @Volatile private var bandTaps: Process? = null

    private fun watchBandTaps(displayId: Int) {
        stopBandTaps()
        val device = BandTap.rearDevice(exec("getevent -pl"))
        if (device == null) {
            Log.w(TAG, "camera close: no rear touchscreen in getevent — the camera closes from the front only")
            return
        }
        val (path, maxX, maxY) = device
        // The zone is the display's own left inset, the lens column, as the system reports
        // it for this model; the 17 Pro's, as a fraction, when it does not say.
        val band = rearPhysicalId()
            ?.let { DisplayDump.leftInsetFraction(exec("dumpsys display | grep 'DisplayDeviceInfo'"), it) }
            ?.let { it + BandTap.EDGE_ALLOW / Panel.WIDTH }
            ?: BandTap.BAND
        val proc = try {
            ProcessBuilder("sh", "-c", "exec getevent $path").redirectErrorStream(true).start()
        } catch (e: Exception) {
            Log.w(TAG, "camera close: getevent failed: ${e.message}")
            return
        }
        bandTaps = proc
        val since = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "camera close: watching $path for a tap in the left ${"%.1f".format(band * 100)}%")
        Thread {
            val tap = BandTap(maxX, maxY, band)
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - since > BAND_WATCH_MS) break
                        // NOT OVER AFTER ONE TAP. A Back may only take down something inside
                        // the camera — its pocket warning, a recording it stops first — and
                        // the camera stays; the watch used to end there, and every tap after
                        // it went nowhere. It ends when the panel is back in front.
                        if (tap.feed(line, now) && !sendCameraBack(displayId)) break
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (bandTaps === proc) stopBandTaps()
            }
        }.apply { isDaemon = true; name = "camera-band-taps"; start() }
        // getevent blocks while nobody touches the screen, so the time limit needs its own clock.
        Thread {
            try { Thread.sleep(BAND_WATCH_MS) } catch (_: InterruptedException) { return@Thread }
            if (bandTaps === proc) stopBandTaps()
        }.apply { isDaemon = true; name = "camera-band-taps-limit"; start() }
    }

    private fun stopBandTaps() {
        bandTaps?.let { runCatching { it.destroy() } }
        bandTaps = null
    }

    /**
     * A tap in the zone: Back to the camera if it has the rear display's focus. False when
     * the watch is over — the panel has the focus again, so the camera is gone. Anything
     * else in focus, a system warning say, gets no Back and the watch goes on.
     */
    private fun sendCameraBack(displayId: Int): Boolean {
        val focused = FocusDump.focusedPackage(exec("dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp='"), displayId)
        if (focused != CAMERA_PACKAGE) {
            val over = focused == PKG
            Log.i(TAG, "camera close: display $displayId has $focused in focus, not the camera — no Back" +
                if (over) "; the panel is back, the watch ends" else "; still watching")
            // The camera is over: released if it was seen up, else only forgotten — either
            // way the back is no longer the camera's (cameraUp).
            if (over) { if (cameraWasOnTop) closeCamera() else cameraLaunched = false }
            return !over
        }
        Log.i(TAG, "camera close: a tap in the zone — Back to display $displayId")
        exec("input -d $displayId keyevent KEYCODE_BACK")
        // Seen in front, so the panel coming back next releases its process — see below.
        if (cameraLaunched) cameraWasOnTop = true
        // AND IF BACK WAS NOT ENOUGH, THE TASK GOES. The camera decides what Back does: its
        // pocket warning takes it, so does a recording it stops first, and something it
        // shows that we cannot see might take every one. A tap here means "close the
        // camera", so if it still has the display a moment later its task is removed —
        // the same as swiping it away in recents: the activity is paused, stopped and
        // destroyed, a recording finishes the way it does when Home is pressed, and the
        // front-screen half goes with it (measured: both tasks gone, the panel in focus).
        Thread {
            try { Thread.sleep(BACK_SETTLE_MS) } catch (_: InterruptedException) { return@Thread }
            val dump = exec("dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp='")
            if (FocusDump.focusedPackage(dump, displayId) != CAMERA_PACKAGE) return@Thread
            val task = FocusDump.focusedTask(dump, displayId) ?: return@Thread
            Log.i(TAG, "camera close: still up after Back — removing its task $task")
            exec("am stack remove $task")
        }.apply { isDaemon = true; name = "camera-close-settle"; start() }
        return true
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
    @Volatile private var cameraLaunched = false
    @Volatile private var cameraWasOnTop = false
    /**
     * When [cameraLaunched] was set (elapsedRealtime ms: the camera's clocks run on through
     * deep sleep, which uptime does not): a launch never seen in front is forgotten after
     * [CAMERA_UNSEEN_MS].
     */
    @Volatile private var cameraLaunchedAt = 0L

    /**
     * The panel in front, and the camera we launched never seen ahead of it for
     * [CAMERA_UNSEEN_MS]: opened and closed between two ticks, or closed by the strip's own
     * Back. Nothing to release, but the flags would hold [cameraUp] — no sheet — forever.
     */
    private fun forgetUnseenCamera() {
        if (!cameraLaunched || cameraWasOnTop) return
        if (android.os.SystemClock.elapsedRealtime() - cameraLaunchedAt < CAMERA_UNSEEN_MS) return
        Log.i(TAG, "camera: launched but never seen in front, and the panel is — forgotten")
        cameraLaunched = false
        stopBandTaps()
    }

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
        stopBandTaps()
        cameraLaunched = false
        cameraWasOnTop = false
        Log.i(TAG, "camera dismissed and the panel is back — releasing it")
        exec("am kill $CAMERA_PACKAGE")
    }

    override fun ensureOnRear(displayId: Int): Boolean = synchronized(placing) {
        if (displayId < 0) return@synchronized false
        // Run on USER_PRESENT too: a guest hidden behind the keyguard is on the front now.
        unhideIfExposed("the front was unlocked")
        val where = panelWhere()
        if (where != Where.MAIN) {
            strandStrikes = 0
            if (where == Where.REAR) adoptPlaced(displayId, "ensureOnRear")
            return false
        }
        val task = taskId()
        if (task <= 0) return false
        strandStrikes++
        Log.w(TAG, "task $task compositing on the MAIN display (strike $strandStrikes) — moving it back")
        moveToDisplay(task, displayId)
        wake(displayId)
        adoptPlaced(displayId, "ensureOnRear")

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

    override fun rearBrightness(): Int =
        exec("settings get system sub_display_screen_brightness").trim().toIntOrNull() ?: -1

    override fun rearAutoBrightness(): Boolean =
        exec("settings get secure sub_display_screen_brightness_mode").trim() == "1"

    override fun setRearAutoBrightness(on: Boolean) {
        exec("settings put secure sub_display_screen_brightness_mode ${if (on) 1 else 0}")
    }

    override fun quickStates(): String {
        val bt = exec("settings get global bluetooth_on").trim().let { it != "0" && it.isNotEmpty() && it != "null" }
        val wifi = exec("settings get global wifi_on").trim().let { it != "0" && it.isNotEmpty() && it != "null" }
        // Per SIM (mobile_data0, mobile_data1 …): on if any is.
        val data = exec("settings list global | grep -E '^mobile_data[0-9]+=1' | head -1").isNotBlank()
        fun b(v: Boolean) = if (v) "1" else "0"
        return "${b(bt)},${b(data)},${b(wifi)}"
    }

    /**
     * The App Portal's guest: the app we put on the rear, its task, whether it was last on
     * top. Null when there is none. Replaced whole under [placing]; only [Guest.onTop] is
     * changed off the lock, and only by compare-and-set against the guest it was read
     * from, so a reading that predates a launch can never write the old guest back.
     * [Guest.placed] is false until [guestPlaced]: while a launch waits to see whether the
     * task survives its move, the keep-alive leaves it and its frame alone.
     * [Guest.landed]: a launch has moved it onto the rear — from then on it is the sheet's
     * guest, buttons and slider ([Guest.sheetOnTop], PortalAction.sheetGuest), without waiting
     * the two seconds for [Guest.placed].
     * [Guest.hiddenOnFront]: the sheet's Panel moved it to display 0 behind the keyguard
     * (see [onSheetButton]); it is still the guest, behind, and Resume brings it back.
     */
    private data class Guest(
        val pkg: String, val task: Int, val onTop: Boolean, val placed: Boolean = false,
        val hiddenOnFront: Boolean = false, val landed: Boolean = false,
    ) {
        /** As the sheet shows it: null none, true on top (Panel), false behind (Resume). */
        val sheetOnTop: Boolean? get() = PortalAction.sheetGuest(landed, placed, onTop)
    }
    private val guestRef = AtomicReference<Guest?>(null)
    private var guest: Guest?
        get() = guestRef.get()
        set(value) = guestRef.set(value)
    private val frame by lazy { PortalFrame(rearLayerStack()) }

    /** [onTop] on whatever guest has [task] now; true if this call changed it. */
    private fun markOnTop(task: Int, onTop: Boolean): Boolean {
        while (true) {
            val g = guestRef.get() ?: return false
            if (g.task != task || g.onTop == onTop) return false
            if (guestRef.compareAndSet(g, g.copy(onTop = onTop))) return true
        }
    }

    /**
     * The pinned apps' names and icons, as the main app last sent them. Stored as received
     * (WebP bytes); ShellSheet.apps decodes them all at once on the binder thread that hands
     * them over, so drawing never decodes, and the snapshot keeps each decoded icon.
     * Replaced whole, never edited, so a reader holding one sees a consistent list.
     */
    @Volatile var portalApps: PortalAppsSnapshot = PortalAppsSnapshot.EMPTY
        private set

    override fun setPortalApps(apps: android.os.Bundle?) {
        portalApps = PortalAppsSnapshot.fromBundle(apps)
        shellSheet?.apps(portalApps)
        repage()
    }

    /** The portal grid's size (PanelConfig.portalGrid), from the last payload. */
    @Volatile private var portalGrid = dev.kb896.aiusage.data.PortalGrid.R3C4

    /** The apps' names under their icons on the back (PanelConfig.portalAppNames), from the last payload. */
    @Volatile private var portalNames = true

    /**
     * The pinned apps or the grid changed: the pages follow, an open sheet's with them. On
     * [sheetClock]: this comes from show() and update() under [placing], and the reader may
     * hold the sheet's lock for a while (the sheet's first making loads the font).
     */
    private fun repage() {
        runCatching {
            sheetClock.execute {
                synchronized(sheetLock) {
                    pager.pages = sheetLayout().pages
                    if (sheetUp) shellSheet?.page(pager.position(android.os.SystemClock.uptimeMillis()))
                }
            }
        }
    }

    /** The rear display's layer stack, from dumpsys display; 1 on the 17 Pro, as measured. */
    private fun rearLayerStack(): Int {
        val dump = exec("dumpsys display | grep -iE 'mDisplayId=${Panel.DISPLAY_ID}|layerStack' | head -40")
        return Regex("""mDisplayId=${Panel.DISPLAY_ID}\b[\s\S]*?[lL]ayerStack[ =](\d+)""").find(dump)
            ?.groupValues?.get(1)?.toIntOrNull() ?: Panel.DISPLAY_ID
    }

    /**
     * The frame follows the config's colour, and the guest's density the config's DPI: a
     * new payload may carry a new value for the app that is the guest right now.
     */
    private fun styleFrame(payload: String) {
        if (CalibrateMode.isCalibrate(payload)) return
        try {
            val config = StatePayload.decode(payload).config
            frame.style(config.portalFrameColor)
            frame.allow(config.portalBorder)
            setPortalDpi { dpiEdits.overlay(config.portalDpi, android.os.SystemClock.uptimeMillis()) }
            sheetLook = config.theme to config.glassStyle
            shellSheet?.theme(config.theme, config.glassStyle)
            // Handed on with every payload (the sheet ignores what it has already): a sheet
            // being made between this write and its publication picks it up there.
            val regrid = portalGrid != config.portalGrid
            portalGrid = config.portalGrid
            shellSheet?.grid(config.portalGrid)
            if (regrid) repage()
            portalNames = config.portalAppNames
            shellSheet?.names(config.portalAppNames)
            restyleSheetFace(config.theme to config.font)
        } catch (e: Exception) {
            Log.w(TAG, "portal frame: no style from the payload: ${e.message}")
            return
        }
        syncDensity("a new payload")
    }

    /**
     * DPI PER APP: display 1's density follows the guest. A guest has its own
     * (config.portalDpi, see [RearDensity]) for as long as it IS the guest — on top, behind
     * the panel, still being placed — so Panel / Resume never relayout it twice, and it
     * lands laid out once at its own DPI because that is set before its move. The display's
     * own density (the [baselineDensity]) comes back only when the guest ends (dropGuest, so
     * endGuest; hide(); destroy()) or when a third app has the rear (the keep-alive's USUAL
     * branch; the panel re-placed over it is a live guest's again, so that takes the guest's
     * back). Each change relayouts everything on display 1 — the panel declares
     * configChanges density, so it is untouched — so `wm density` runs only when the wanted
     * value differs from [currentDensity], and the keep-alive's per-tick call costs a
     * comparison, never a dump.
     *
     * The baseline is what the FIRST read found, not "no override": display 1 may carry an
     * override that is not ours (the panel was measured at 450, 380 after one), and that
     * one is restored with `wm density <baseline>`, never wiped by `reset`.
     *
     * Its own lock, [densityLock], not [placing]: the keep-alive's guest branches run off
     * [placing] on purpose — it is held through a launch's settle and survive waits, seconds
     * at a time — and taking it here would stall them behind any launch. [densityLock] is a
     * leaf: taken inside [placing] (launch, show, end), never the other way round. Each call
     * reads the guest afresh under it, so whichever call runs last applies the latest
     * state; two threads can never leave an older value set after a newer one.
     *
     * Shizuku killing the service outright is out of our hands: the override then survives
     * on display 1. For that, a marker `<applied>,<baseline or ->` goes into the global
     * setting [DENSITY_MARKER] BEFORE each override of ours and is deleted once the
     * baseline is back; the next service's first read calls the override it finds ours
     * only if the marker lists it ([DensityDump.start]), and then restores the marker's
     * baseline instead of keeping ours as the display's own. A change from one of ours to
     * another lists both (`300+310,380`) until it has taken.
     *
     * A command that was refused (non-zero exit, an error printed) is known not to have
     * happened, and the marker is put back. One that was interrupted or never started may
     * or may not have happened: the marker is left as written — it covers both outcomes —
     * and [densityKnown] goes false, so the next call reads `wm density` again before it
     * trusts [currentDensity].
     */
    private val densityLock = Any()
    /** Whether display 1's density has been read yet (once per service). Under [densityLock]. */
    private var densityRead = false
    /** False after a command whose outcome is unknown: [currentDensity] is re-read first. Under [densityLock]. */
    private var densityKnown = true
    /** Display 1's own override as first found (null = none): what comes back. Under [densityLock]. */
    private var baselineDensity: Int? = null
    /** The override on display 1 now, as far as this service knows. Under [densityLock]. */
    private var currentDensity: Int? = null
    /** The last change refused, so a refusal is logged once, not every tick. Under [densityLock]. */
    private var refusedDensity: String? = null
    /** Set by destroy(): nothing may apply a density after its restore. Under [densityLock]. */
    private var densityClosed = false
    /** No re-read after an unknown outcome before this uptime (ms), so a failing exec is not retried every tick. Under [densityLock]. */
    private var rereadNotBefore = 0L
    /** Display 1's physical density, for the log. */
    private var physicalDensity = DensityDump.FALLBACK
    /**
     * The config's per-app DPI, from the latest payload, with the changes made on the back
     * laid over it until a payload carries them ([dpiEdits]). Written under [dpiLock].
     */
    @Volatile private var portalDpi: Map<String, Int> = emptyMap()
    /** Display 1's own density as the first read found it (its override, else its physical); null before. */
    @Volatile private var densityDefault: Int? = null

    /**
     * Display 1 at the density the guest calls for: its own for any live guest, else the
     * baseline. [thirdOnTop]: another app has the rear, which is shown at the baseline.
     */
    private fun syncDensity(why: String, thirdOnTop: Boolean = false) = synchronized(densityLock) {
        if (densityClosed) return@synchronized
        val g = guest
        applyDensity(if (thirdOnTop) null else RearDensity.target(g?.pkg, g != null, portalDpi), why)
    }

    /**
     * Under [densityLock]. [guestDpi] null = the baseline. Nothing is changed until the
     * first read has worked: without it the baseline is unknown, and guessing it is how an
     * override that is not ours gets wiped. A command that did not take leaves
     * [currentDensity] as it was, so the next call retries.
     */
    private fun applyDensity(guestDpi: Int?, why: String) {
        if (!readDensity()) return
        if (!densityKnown) {
            if (android.os.SystemClock.elapsedRealtime() < rereadNotBefore) return
            val d = (execChecked("wm density -d ${Panel.DISPLAY_ID}") as? Run.Ok)?.let { DensityDump.parse(it.out) }
            if (d?.physical == null) {
                rereadNotBefore = android.os.SystemClock.elapsedRealtime() + REREAD_BACKOFF_MS
                return
            }
            currentDensity = d.override
            densityKnown = true
            Log.i(TAG, "density: display ${Panel.DISPLAY_ID} re-read after an unfinished change — ${d.override ?: "no override"}")
        }
        val wanted = guestDpi ?: baselineDensity
        val cur = currentDensity
        if (wanted == cur) return
        val ours = wanted != null && wanted != baselineDensity
        val curOurs = cur != null && cur != baselineDensity
        // The marker first: a service killed between the two finds an override the marker
        // lists either way. From one of ours to another it lists both, until this takes.
        if (ours) {
            val put = execChecked("settings put global $DENSITY_MARKER ${DensityDump.marker(wanted!!, baselineDensity, from = if (curOurs) cur else null)}")
            if (put !is Run.Ok) { densityFailed(put, wanted, why, "the marker"); return }
        }
        val run = execChecked(
            if (wanted != null) "wm density $wanted -d ${Panel.DISPLAY_ID}"
            else "wm density reset -d ${Panel.DISPLAY_ID}"
        )
        if (run !is Run.Ok) {
            densityFailed(run, wanted, why, "the change")
            // Refused, so known not to have happened: the marker back to what is really
            // there. Unknown, it stays as written — it covers both outcomes.
            if (run is Run.Refused && ours) execChecked(
                if (curOurs) "settings put global $DENSITY_MARKER ${DensityDump.marker(cur!!, baselineDensity)}"
                else "settings delete global $DENSITY_MARKER"
            )
            return
        }
        currentDensity = wanted
        refusedDensity = null
        // After the change, never before: killed in between, the marker still covers it.
        if (!ours) execChecked("settings delete global $DENSITY_MARKER")
        else if (curOurs) execChecked("settings put global $DENSITY_MARKER ${DensityDump.marker(wanted!!, baselineDensity)}")
        Log.i(TAG, "density: display ${Panel.DISPLAY_ID} at " +
            (if (ours) "$wanted dpi" else "its own ${wanted ?: physicalDensity} dpi") + " — $why")
    }

    /** A step that did not take: logged once per target; an unknown outcome is re-read next time. */
    private fun densityFailed(run: Run, wanted: Int?, why: String, step: String) {
        if (run is Run.Unknown) densityKnown = false
        val key = wanted?.toString() ?: "reset"
        if (refusedDensity == key) return
        refusedDensity = key
        val detail = when (run) {
            is Run.Refused -> "refused: ${run.out.take(200)}"
            else -> "did not finish (interrupted or never started) — re-read next time"
        }
        Log.w(TAG, "density: $key — $step $detail — $why")
    }

    /** The first read: display 1's override and our marker, once. Under [densityLock]. */
    private fun readDensity(): Boolean {
        if (densityRead) return true
        val dump = execChecked("wm density -d ${Panel.DISPLAY_ID}")
        val d = (dump as? Run.Ok)?.let { DensityDump.parse(it.out) }
        val marker = if (d?.physical != null) execChecked("settings get global $DENSITY_MARKER") as? Run.Ok else null
        if (d?.physical == null || marker == null) {
            if (refusedDensity != "read") {
                refusedDensity = "read"
                Log.w(TAG, "density: display ${Panel.DISPLAY_ID} unreadable — leaving it alone: $dump")
            }
            return false
        }
        val start = DensityDump.start(d.override, marker.out)
        densityRead = true
        densityKnown = true
        refusedDensity = null
        physicalDensity = d.physical
        baselineDensity = start.baseline
        densityDefault = start.baseline ?: d.physical
        shellSheet?.dpiDefault(densityDefault)
        currentDensity = d.override
        if (!start.ours && DensityDump.parseMarker(marker.out) != null) execChecked("settings delete global $DENSITY_MARKER")
        Log.i(TAG, "density: display ${Panel.DISPLAY_ID} is ${d.physical} dpi" +
            (d.override?.let { if (start.ours) ", still at our $it from a service that died — restoring" else ", its own override $it kept" } ?: ""))
        return true
    }

    override fun launchGuest(pkg: String?, displayId: Int): Boolean = synchronized(placing) {
        if (displayId < 0 || pkg == null || !PACKAGE_NAME.matches(pkg) || !GuestTask.sendable(pkg, PKG)) {
            return@synchronized false
        }
        // No panel placed means no keep-alive running, and nothing would ever see the
        // guest leave: its frame and watcher would outlive it.
        if (placedDisplayId < 0) { Log.w(TAG, "portal: no panel placed — not launching $pkg"); return@synchronized false }
        // Resolved before anything moves: an app with no launcher activity changes nothing.
        val component = exec("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg")
            .lines().lastOrNull()?.trim()
        if (component.isNullOrEmpty() || !COMPONENT.matches(component)) {
            Log.w(TAG, "portal: no launcher activity for $pkg"); return@synchronized false
        }
        // One guest at a time: the old one goes to the front, alive — never killed. Not yet:
        // it stays on the rear, live, while the new one starts and settles on the front, and
        // leaves under the cover, as the new one comes in.
        val old = guest?.takeIf { it.pkg != pkg }
        // A new app coming in — over the panel, or in place of another: covered and revealed.
        // The same app again moves nothing on the rear.
        val arriving = guest?.pkg != pkg
        repeat(2) { attempt ->
            val first = attempt == 0
            // Asked for on the front by name: with the old guest still on the rear, where an
            // unspecified start lands is not ours to guess. The same app again is left to
            // land where its task is.
            val task = startSettled(component, pkg, front = arriving)
            if (task == null) {
                // Nothing on the rear has changed yet: an old guest is still the guest.
                if (first && old != null && guest == old) {
                    Log.w(TAG, "portal: $pkg never started — ${old.pkg} stays")
                    return@synchronized false
                }
                endGuest("never started"); return@synchronized false
            }
            // The rear as it is, over everything (GuestCover), before anything on it moves:
            // the old guest's leaving, the density's change and the new app's first frames
            // happen under it. Not on a rear asleep, nor for the same app again. The frame
            // (one guest to the next) is left out of the picture, and the live one stays up
            // over it the whole way; where it could not be left out, it is in the picture,
            // and the live one fades out as it goes and back in after the reveal.
            val cover = if (first && arriving && !dozing) coverRear(displayId) else null
            if (cover != null && !cover.frameLeftOut) frame.hide()
            if (first && old != null) moveToDisplay(old.task, 0)
            guest = Guest(pkg, task, onTop = false)
            // Before the move, so it lands laid out once at its own DPI (see syncDensity).
            syncDensity("$pkg is landing")
            moveToDisplay(task, displayId)
            wake(displayId)
            val movedAt = android.os.SystemClock.uptimeMillis()
            // Uncovered, the frame comes up as the app lands, not after the survive wait;
            // show() is idempotent, so guestPlaced() below does not start a second one.
            if (cover == null) frame.show()
            // Landed: the sheet's guest from now, so a pull during the survive wait has its
            // buttons from the first frame (they used to come in when it was placed).
            guest = Guest(pkg, task, onTop = true, landed = true)
            syncSheetGuest()
            // Covered: revealed once it has drawn, and the frame faded in after it (still up
            // from the guest before, it stays) — never before the app.
            if (cover != null) {
                reveal(cover, pkg, task, displayId)
                if (guest?.let { it.task == task && it.onTop } == true) frame.show()
            }
            // A task gone within two seconds of the move was a trampoline the move
            // relaunched (Shazam, measured); once more from the start, then give up.
            Thread.sleep(maxOf(0L, GUEST_SURVIVE_MS - (android.os.SystemClock.uptimeMillis() - movedAt)))
            val entry = GuestTask.byId(exec("am stack list"), task)
            if (entry != null && entry.display == displayId) {
                guest = Guest(pkg, task, onTop = true, landed = true)
                Log.i(TAG, "portal: $pkg is the guest, task $task")
                guestPlaced(displayId)
                return@synchronized true
            }
            frame.hide()
            // Not landed after all: no buttons for a task that is gone.
            guest = Guest(pkg, task, onTop = false)
            syncSheetGuest()
            Log.w(TAG, "portal: $pkg's task $task did not survive the move (try ${attempt + 1})")
        }
        endGuest("did not stay on the rear")
        false
    }

    /** The rear's layer stack for the guests' covers, read once. */
    private val coverStack by lazy { rearLayerStack() }

    /**
     * The rear (display [displayId]) as it shows now, on a cover over it (GuestCover); null
     * when there can be none, and the guest arrives uncovered, as it always did.
     */
    private fun coverRear(displayId: Int): GuestCover? {
        val at = android.os.SystemClock.uptimeMillis()
        return GuestCover.up(coverStack, displayId, frame.currentLayer) { screencapRear() }
            ?.also { Log.i(TAG, "portal: the rear covered in ${android.os.SystemClock.uptimeMillis() - at} ms" +
                if (it.frameLeftOut) ", the frame left out" else "") }
    }

    /** The rear by the `screencap` command, for a cover where the in-process capture is missing; null if it cannot be had. */
    private fun screencapRear(): android.graphics.Bitmap? {
        val id = rearPhysicalId() ?: return null // without -d it would be the FRONT screen
        val p = ProcessBuilder("screencap", "-p", "-d", id).start()
        return try {
            p.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } finally {
            runCatching { p.waitFor() }
            p.destroy()
        }
    }

    /**
     * [cover] off the rear once [pkg]'s [task] has drawn on [displayId] (GuestDrawn, asked of
     * `dumpsys window <pkg>` as fast as it answers): [reveal] with that for its sign.
     */
    private fun reveal(cover: GuestCover, pkg: String, task: Int, displayId: Int) =
        reveal(cover, pkg, displayId) { drawnOn(pkg, task, displayId) }

    /** [pkg]'s [task] has an app window drawn on [displayId] at the rear's size (GuestDrawn). */
    private fun drawnOn(pkg: String, task: Int, displayId: Int) =
        task > 0 && GuestDrawn.drawn(exec("dumpsys window $pkg"), task, displayId, Panel.WIDTH, Panel.HEIGHT)

    /**
     * [cover] off the rear once [drawn] says [what] — the app now on top — has drawn there,
     * asked as fast as it answers, or GuestReveal.MAX_WAIT_MS has gone by without a sign of
     * it: slid left and faded on GuestReveal's curve, a transaction a frame at [displayId]'s
     * rate, then removed. On the caller's thread, under [placing].
     */
    private fun reveal(cover: GuestCover, what: String, displayId: Int, drawn: () -> Boolean) {
        val coveredAt = android.os.SystemClock.uptimeMillis()
        val r = GuestReveal(coveredAt)
        try {
            while (r.waiting(android.os.SystemClock.uptimeMillis())) {
                if (drawn()) {
                    val now = android.os.SystemClock.uptimeMillis()
                    r.drawn(now)
                    Log.i(TAG, "portal: $what drew on the rear ${now - coveredAt} ms after the change — revealing")
                    break
                }
                Thread.sleep(DRAWN_POLL_MS)
            }
            if (r.phase == GuestReveal.Phase.COVERED) {
                Log.w(TAG, "portal: no sign of $what drawing on the rear in ${GuestReveal.MAX_WAIT_MS} ms — revealing anyway")
            }
            val frameMs = frameMsOf(displayId)
            while (true) {
                val f = r.at(android.os.SystemClock.uptimeMillis())
                cover.place(f.dx, f.alpha)
                if (r.phase == GuestReveal.Phase.DONE) break
                Thread.sleep(frameMs)
            }
        } finally {
            // Interrupted or not, a cover is never left over the rear.
            cover.remove()
        }
    }

    /**
     * A change of what is on top of the rear, made under a cover (GuestCover) of the rear as
     * it shows now and revealed as a launch is: [change] runs underneath, and if it changed
     * something (true), the cover slides away once [drawn] says [what] — the new top — has
     * drawn; if it did not, the cover simply goes, since the rear is as it was. No cover on
     * a rear asleep, or where there can be none: [change] alone. The sheet is down already
     * (a choice runs once it has ended), so it is not in the picture; the frame is left out
     * where it can be, and fades as the change has it. Under [placing].
     */
    private fun covered(what: String, drawn: () -> Boolean, change: () -> Boolean): Boolean {
        val display = rearDisplay()
        val cover = if (!dozing) coverRear(display) else null
        val changed = try { change() } catch (e: Throwable) { cover?.remove(); throw e }
        if (cover != null) { if (changed) reveal(cover, what, display, drawn) else cover.remove() }
        return changed
    }

    /** Our panel is what the rear composes on top (over [g]), and has drawn there at its size. */
    private fun panelShown(g: Guest): Boolean =
        rearTop(g) == PKG && drawnOn(PKG, taskId(), rearDisplay())

    /** One frame of [displayId] in ms, from its refresh rate; 60 Hz's when it does not say. */
    private fun frameMsOf(displayId: Int): Long = runCatching {
        val hz = context?.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(displayId)?.refreshRate
        if (hz != null && hz >= 24f) Math.round(1000f / hz).toLong().coerceIn(4L, 42L) else 16L
    }.getOrDefault(16L)

    /**
     * Started on the front — by name when [front], else wherever `am start` puts it — and its
     * task once the top activity has held still. Null if none.
     */
    private fun startSettled(component: String, pkg: String, front: Boolean): Int? {
        exec(if (front) "am start --display 0 -n $component" else "am start -n $component")
        val settle = GuestTask.Settle()
        val until = android.os.SystemClock.uptimeMillis() + GUEST_SETTLE_LIMIT_MS
        while (android.os.SystemClock.uptimeMillis() < until) {
            val entry = GuestTask.taskOf(exec("am stack list"), pkg)
            if (entry != null && settle.feed(entry.top, android.os.SystemClock.uptimeMillis())) return entry.task
            Thread.sleep(150)
        }
        return GuestTask.taskOf(exec("am stack list"), pkg)?.task
    }

    override fun sendFocusedToBack(displayId: Int): String = synchronized(placing) {
        if (displayId < 0) return@synchronized ""
        // As in launchGuest: without a placed panel no keep-alive would watch the guest.
        if (placedDisplayId < 0) { Log.w(TAG, "portal: no panel placed — not sending to back"); return@synchronized "" }
        val dump = exec("dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp='")
        val pkg = FocusDump.focusedPackage(dump, 0) ?: return@synchronized ""
        val task = FocusDump.focusedTask(dump, 0) ?: return@synchronized ""
        if (!PACKAGE_NAME.matches(pkg) || !GuestTask.sendable(pkg, PKG)) return@synchronized ""
        guest?.let { old -> if (old.task != task) moveToDisplay(old.task, 0) }
        guest = Guest(pkg, task, onTop = true)
        syncDensity("$pkg is landing")
        if (!moveToDisplay(task, displayId)) { endGuest("move refused"); return@synchronized "" }
        wake(displayId)
        guestPlaced(displayId)
        pkg
    }

    /** A guest is on top: the frame, the watcher, and the sheet told. Under [placing]. */
    private fun guestPlaced(displayId: Int) {
        guest = guest?.copy(placed = true)
        syncDensity("${guest?.pkg} is the guest") // already set as it landed; a no-op then
        frame.show() // already up from the launch's move; a no-op while running
        syncTouchWatch()
        syncSheetGuest()
    }

    /** No guest any more: frame, watcher and the sheet's buttons off. Under [placing]. */
    private fun dropGuest(why: String) {
        Log.i(TAG, "portal: guest over — $why")
        guest = null
        frame.hide()
        syncDensity("the guest is over")
        // Over the panel the watch goes on (it is for the panel too); dozing, it stops.
        syncTouchWatch()
        syncSheetGuest()
    }

    /** [dropGuest], and the panel back in front. Under [placing]. */
    private fun endGuest(why: String) {
        dropGuest(why)
        if (placedDisplayId >= 0) show(lastPayload, placedDisplayId)
    }

    override fun guestAction(which: String?) {
        when (which) {
            // The panel stays over a live guest; the sheet then offers Resume.
            "panel" -> {
                val g = guest ?: return
                markOnTop(g.task, false)
                frame.hide()
                syncSheetGuest()
            }
            // RearActivity has gone behind (moveTaskToBack); the guest is on top again. One
            // the shell sheet hid on the front has to be brought back for that to be true.
            "resume" -> {
                val g = guest ?: return
                if (g.hiddenOnFront) { synchronized(placing) { unhide("the panel's Resume") }; return }
                markOnTop(g.task, true)
                frame.show()
                syncSheetGuest()
                wake(if (placedDisplayId >= 0) placedDisplayId else Panel.DISPLAY_ID)
            }
            // Read under the lock: a launch may have replaced the guest since the panel asked.
            "front" -> synchronized(placing) {
                val g = guest ?: return
                moveToDisplay(g.task, 0)
                endGuest("sent to the front")
            }
            "close" -> synchronized(placing) {
                val g = guest ?: return
                exec("am force-stop ${g.pkg}")
                endGuest("closed")
            }
            else -> Log.w(TAG, "portal: unknown guest action '$which'")
        }
    }

    // -- the portal sheet, in the shell ---------------------------------------------------
    //
    // See specs/2026-10-28-app-portal-shell-sheet-design.md. The sheet is a layer of ours
    // (ShellSheet) and its input is the raw rear touchscreen (getevent → RearTouches →
    // EdgeGestures), so the first swipe counts over the panel and over a guest, locked or
    // not, and nothing is ever started to show it.
    //
    // THE TOUCH WATCH runs while the rear is lit and ours: the panel placed and not asleep
    // on purpose, or a guest on top (the panel's own sleep timer runs out behind a guest;
    // the guest is still lit). One getevent at a time, started and stopped by
    // [syncTouchWatch] — from show/wake/setDozing/hide, guest changes, and every keep-alive
    // tick as the safety net for a getevent that died.
    //
    // THE SHEET: [sheetLock] guards the gestures, [motion] and the flags below. The reader
    // thread feeds touches; [sheetClock], one thread, runs begin()/end() (end() may block
    // for its join, never on the reader), the settle's frames and the pages' — so
    // begin and end are applied in the order they were asked for.

    private val touchWatch = AtomicReference<Process?>(null)
    private val touchLock = Any()
    @Volatile private var rearTouch: Triple<String, Int, Int>? = null
    /** No rear touchscreen found: not asked again before this uptime. */
    @Volatile private var rearTouchRetryAt = 0L
    private var warnedNoRearTouch = false

    private fun touchWanted() = placedDisplayId >= 0 && (!dozing || guest?.onTop == true)

    /** The watch running if and only if [touchWanted]. Any thread. */
    private fun syncTouchWatch() = synchronized(touchLock) {
        val running = touchWatch.get()?.isAlive == true
        when {
            touchWanted() && !running -> startTouchWatch()
            !touchWanted() && running -> stopTouchWatch()
        }
    }

    /** Under [touchLock]. */
    private fun startTouchWatch() {
        stopTouchWatch()
        val device = rearTouch ?: run {
            if (android.os.SystemClock.uptimeMillis() < rearTouchRetryAt) return
            BandTap.rearDevice(exec("getevent -pl")).also { d ->
                if (d != null) rearTouch = d
                else {
                    rearTouchRetryAt = android.os.SystemClock.uptimeMillis() + TOUCH_RETRY_MS
                    if (!warnedNoRearTouch) {
                        warnedNoRearTouch = true
                        Log.w(TAG, "portal: no rear touchscreen in getevent — no sheet, no Back")
                    }
                }
            }
        } ?: return
        val (path, maxX, maxY) = device
        val proc = try {
            ProcessBuilder("sh", "-c", "exec getevent $path").redirectErrorStream(true).start()
        } catch (e: Exception) { Log.w(TAG, "portal: getevent failed: ${e.message}"); return }
        touchWatch.set(proc)
        val gestures = EdgeGestures()
        synchronized(sheetLock) {
            gestures.sheet = sheetUp
            this.gestures = gestures
        }
        Thread {
            // The font and the layer stack, off every binder thread and before the first pull.
            sheetOrNull()
            val touches = RearTouches(maxX, maxY)
            val startedAt = android.os.SystemClock.uptimeMillis()
            var events = 0
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        for (e in touches.feed(line, android.os.SystemClock.uptimeMillis())) {
                            events++
                            onRearTouch(e, gestures)
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // The stream closing under us is how stopTouchWatch() ends this.
            } catch (e: Exception) {
                Log.w(TAG, "portal: touch watch stopped", e)
            } finally {
                runCatching { proc.destroy() }
                val lived = android.os.SystemClock.uptimeMillis() - startedAt
                // A watch that ended at once, or read nothing, may have had a stale device
                // path (input devices renumber): the next start asks getevent again.
                if (lived < TOUCH_SHORT_LIFE_MS || events == 0) rearTouch = null
                // Died on its own (stopTouchWatch had not taken it): a sheet up has no reader
                // left to close it — its blocker would hold every touch — so it goes now,
                // and the watch comes back. Not at once after a quick death, so a getevent
                // that cannot run is not respawned in a loop: the keep-alive tick does it.
                if (touchWatch.compareAndSet(proc, null)) {
                    Log.w(TAG, "portal: the touch watch died after ${lived} ms, $events events")
                    closeSheetNow("the touch watch died")
                    if (lived >= TOUCH_SHORT_LIFE_MS) syncTouchWatch()
                }
            }
        }.apply { isDaemon = true; name = "portal-touches"; start() }
    }

    /** No touches read; a sheet up goes with it, since nothing could close it any more. */
    private fun stopTouchWatch() {
        val proc = touchWatch.getAndSet(null) ?: return
        runCatching { proc.destroy() }
        closeSheetNow("the touch watch stopped")
    }

    private val sheetLock = Any()
    /** The watch's classifier; one per getevent. Under [sheetLock]. */
    private var gestures = EdgeGestures()
    private val motion = SheetMotion()
    /** From an opening's begin until its end is asked for. Under [sheetLock]. */
    private var sheetUp = false
    /** begin() asked for and not yet run on [sheetClock]. Under [sheetLock]. */
    private var beginPending = false
    /** This finger is moving the sheet (a pull or a drag on it). Under [sheetLock]. */
    private var driving = false
    /** Where the current finger landed: a drag is measured from there. Under [sheetLock]. */
    private var touchDownY = 0f
    /** And across: a sideways drag is measured from there. Under [sheetLock]. */
    private var touchDownX = 0f
    /** The grid's pages: which shows, and their slide. Under [sheetLock]. */
    private val pager = SheetPager()
    /** What this finger's sideways drag moves (PortalSheet.swipe), decided as it starts. Under [sheetLock]. */
    private var slide = PortalSheet.Swipe.NONE
    /** A slider drag's app, the DPI its thumb started at, and where it is now. Under [sheetLock]. */
    private var sliderPkg: String? = null
    private var sliderFrom = 0
    private var sliderAt = 0
    /**
     * And when: a drag is timed from there too. Grabbed at the pull's own time, its first step
     * took "1 ms" and every pull read as a flick. Under [sheetLock].
     */
    private var touchDownAt = 0L
    /** Run once the sheet is down: the tapped app's launch, a button's choice. Under [sheetLock]. */
    private var afterShut: (() -> Unit)? = null
    /**
     * [afterShut] is a button's choice that holds the frame's veil (PortalFrame.holdVeil):
     * the choice lets it go when it has run, and a choice dropped unrun lets it go here
     * ([dropChoice], [sheetDown]) — never left holding. Under [sheetLock].
     */
    private var veilHeld = false

    /** No choice to run after all; a veil it held let go. Under [sheetLock]. */
    private fun dropChoice() {
        afterShut = null
        if (veilHeld) { veilHeld = false; frame.releaseVeil() }
    }
    private var anim: java.util.concurrent.ScheduledFuture<*>? = null
    /** The button (or the slider's part, PortalSheetPainter.PRESS_*) shown pressed, or -1. Under [sheetLock]. */
    private var pressed = -1
    /** The guest as the sheet shows it: null none (no buttons), true on top (Panel), false behind (Resume); with either, the DPI slider. */
    @Volatile private var sheetGuestOnTop: Boolean? = null
    @Volatile private var sheetLook: Pair<dev.kb896.aiusage.data.PanelTheme, dev.kb896.aiusage.data.GlassStyle?>? = null

    private val sheetClock: java.util.concurrent.ScheduledExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "portal-sheet-clock").apply { isDaemon = true }
        }
    }

    @Volatile private var shellSheet: ShellSheet? = null
    /** The panel's theme and chosen font as the sheet's face was last built for (under [sheetMaking]). */
    @Volatile private var sheetType: Pair<dev.kb896.aiusage.data.PanelTheme, dev.kb896.aiusage.data.PanelFont?> =
        dev.kb896.aiusage.data.PanelTheme.GLASS to null
    /** The font did not load: no new try before this uptime. */
    @Volatile private var sheetFontRetryAt = 0L
    private val sheetMaking = Any()

    /** The sheet, made once the panel's font is in hand; null if it cannot be (tried again after a minute). */
    private fun sheetOrNull(): ShellSheet? {
        shellSheet?.let { return it }
        if (android.os.SystemClock.uptimeMillis() < sheetFontRetryAt) return null
        synchronized(sheetMaking) {
            shellSheet?.let { return it }
            if (android.os.SystemClock.uptimeMillis() < sheetFontRetryAt) return null
            val face = sheetFace(sheetType)
            if (face == null) { sheetFontRetryAt = android.os.SystemClock.uptimeMillis() + SHEET_FONT_RETRY_MS; return null }
            val s = ShellSheet(rearLayerStack(), face)
            // The portal frame fades under the sheet, as far as it is open.
            s.onProgress = { frame.sheetOpen(it) }
            sheetLook?.let { (theme, style) -> s.theme(theme, style) }
            s.apps(portalApps)
            s.dpiValues(portalDpi)
            s.dpiDefault(densityDefault)
            s.grid(portalGrid)
            s.names(portalNames)
            shellSheet = s
            // Again, now that it is published: a setPortalDpi between the first and here told
            // no sheet. The lock makes this one see that write.
            synchronized(dpiLock) { s.dpiValues(portalDpi) }
            // And the grid and the names: a payload between the first and here told no sheet.
            s.grid(portalGrid)
            s.names(portalNames)
            s.dpiDefault(densityDefault)
            syncSheetGuest()
            return s
        }
    }

    /** The panel's face for [type] (theme, chosen font) — see ShellFont.face. */
    private fun sheetFace(type: Pair<dev.kb896.aiusage.data.PanelTheme, dev.kb896.aiusage.data.PanelFont?>) =
        ShellFont.face(context, type.first, type.second) { exec("pm path $PKG") }

    /**
     * The panel's theme or font changed: the sheet's face follows. A sheet not made yet
     * takes it when it is; one whose new face does not build keeps its old one, and the
     * next payload tries again.
     */
    private fun restyleSheetFace(type: Pair<dev.kb896.aiusage.data.PanelTheme, dev.kb896.aiusage.data.PanelFont?>) {
        if (type == sheetType) return
        synchronized(sheetMaking) {
            if (type == sheetType) return
            val s = shellSheet
            if (s == null) { sheetType = type; return }
            val face = sheetFace(type) ?: return
            sheetType = type
            s.face(face)
        }
    }

    private fun syncSheetGuest() {
        val g = guest?.takeIf { it.sheetOnTop != null }
        val was = sheetGuestOnTop
        sheetGuestOnTop = g?.sheetOnTop
        shellSheet?.guest(sheetGuestOnTop, g?.pkg)
        shellSheet?.guestIcon(g?.pkg?.let { guestIconOf(it) })
        // An open sheet's stops follow: the slider's scroll comes with a guest and goes with it.
        // On the clock, with the settle frames it may start, not on whichever thread this is.
        if (was != sheetGuestOnTop) runCatching { sheetClock.execute { restop() } }
    }

    /** An open sheet's stops, its pages and its buttons as the guest now has them. On [sheetClock]. */
    private fun restop() = runCatching {
        synchronized(sheetLock) {
            if (!sheetUp) return@synchronized
            val now = android.os.SystemClock.uptimeMillis()
            motion.setDetents(PortalSheet.detents(sheetGuestOnTop), now)
            pager.pages = sheetLayout().pages
            afterMotion(now)
        }
    }.onFailure { Log.w(TAG, "portal sheet: restop failed: ${it.message}") }

    /** The last guest whose icon was looked up off the pinned apps, and what came of it (null while loading or none). */
    @Volatile private var guestIconCache: Pair<String, android.graphics.Bitmap?>? = null

    /**
     * [pkg]'s icon for the sheet's Resume: a pinned app's as the main app sent it; else
     * PackageManager's (it answers uid 2000 through our package's Context), loaded once
     * per guest on a thread of its own — null until it lands, then handed to the sheet.
     */
    private fun guestIconOf(pkg: String): android.graphics.Bitmap? {
        val apps = portalApps
        apps.packages.indexOf(pkg).takeIf { it >= 0 }?.let { i -> apps.icon(i)?.let { return it } }
        guestIconCache?.let { (p, icon) -> if (p == pkg) return icon }
        guestIconCache = pkg to null
        Thread {
            val icon = runCatching { context?.packageManager?.getApplicationIcon(pkg)?.toBitmap(GUEST_ICON_PX, GUEST_ICON_PX) }
                .onFailure { Log.w(TAG, "portal sheet: no icon for $pkg: ${it.message}") }
                .getOrNull()
            if (guestIconCache?.first != pkg) return@Thread
            guestIconCache = pkg to icon
            if (icon != null && guest?.takeIf { it.sheetOnTop != null }?.pkg == pkg) shellSheet?.guestIcon(icon)
        }.apply { isDaemon = true; name = "portal-guest-icon"; start() }
        return null
    }

    /** One touch, on the reader thread. */
    private fun onRearTouch(e: RearTouches.Event, from: EdgeGestures) {
        var back = false
        synchronized(sheetLock) {
            if (from !== gestures) return
            val actions = from.feed(e)
            for (a in actions) if (sheetAction(a, e.atMs)) back = true
            if (e is RearTouches.Down) {
                touchDownX = e.x
                touchDownY = e.y
                touchDownAt = e.atMs
            }
        }
        if (back) sendBackOnce()
    }

    /**
     * A right-edge swipe over a guest on top (the sheet down): Back to it ONCE. Xiaomi's strip
     * sends its own Back to whichever display has input focus, so ours goes only when that
     * is not the rear ([PortalAction.sendBack], spec "Settled on the device" 2). Focus is
     * read every time — it moves with touches on either screen — and off the reader thread,
     * since `dumpsys window` takes a moment.
     */
    private fun sendBackOnce() {
        Thread {
            val rear = rearDisplay()
            // One read for both: which display has focus, and which app has the rear's.
            val dump = exec("dumpsys window | grep -E 'mTopFocusedDisplayId|Display: mDisplayId=|mFocusedApp='")
            val focused = PortalAction.topFocusedDisplay(dump)
            val g = guest
            val onTop = g?.onTop == true
            val up = synchronized(sheetLock) { sheetUp }
            if (PortalAction.sendBack(onTop, up, focused, rear)) {
                // Only to the guest: whatever else holds the rear's focus (a system dialog,
                // the panel come back in between) gets no Back of ours.
                // The guest's task counts whoever's window it is: a permission dialog, a chooser
                // started into it, is the guest's to take the Back.
                val app = FocusDump.focusedPackage(dump, rear)
                val task = FocusDump.focusedTask(dump, rear)
                if (g == null || (app != g.pkg && task != g.task)) {
                    if (app == null && task == null) {
                        Log.i(TAG, "portal: Back — the rear's focus is unknown (nothing in the dump); none of ours")
                    } else {
                        Log.i(TAG, "portal: Back — the rear's focused app is $app (task $task), not the guest ${g?.pkg} (task ${g?.task}); none of ours")
                    }
                } else {
                    Log.i(TAG, "portal: Back — display ${focused ?: "?"} has focus, ours to display $rear")
                    exec("input -d $rear keyevent KEYCODE_BACK")
                }
            } else if (onTop && !up) {
                Log.i(TAG, "portal: Back — the rear has focus, Xiaomi's reaches the guest; none of ours")
            }
        }.apply { isDaemon = true; name = "portal-back"; start() }
    }

    private fun rearDisplay() = placedDisplayId.takeIf { it >= 0 } ?: Panel.DISPLAY_ID

    /** Under [sheetLock]. True: send Back to the guest. */
    private fun sheetAction(a: EdgeGestures.Action, atMs: Long): Boolean {
        when (a) {
            // Nothing to fetch as the finger lands: the sheet blurs the live rear under it.
            is EdgeGestures.Action.SheetDown -> {
                if (!sheetAlive()) return false
                // Not the end of a pending launch or button: a touch that lifts again lets the
                // shut carry on (tap → resume) and the choice runs. Only a drag drops it.
                pokePanel()
                stopAnim()
                motion.hold(atMs)
                pushPosition(atMs)
                // The pages keep sliding under a finger that has not moved yet.
                if (pager.settling) startAnim()
                setPressed(motion.settled(atMs)?.let { at -> pressOf(sheetLayout(), a.x, a.y, at) } ?: -1)
            }
            is EdgeGestures.Action.PullStart -> if (sheetUp) {
                if (!sheetAlive()) return false
                // Caught and dragged: a pending launch or button waits for no shut that is coming.
                dropChoice()
                setPressed(-1)
                motion.grab(touchDownY, touchDownAt)
                driving = true
                shellSheet?.position(motion.drag(a.y, atMs))
            // Only a pull from the bottom band opens the sheet: a drag that began on a sheet
            // since gone (the rear dozed, the layers died) opens nothing.
            } else if (!a.onSheet) openSheet(a.y, atMs)
            is EdgeGestures.Action.PullMove -> if (driving && sheetAlive()) shellSheet?.position(motion.drag(a.y, atMs))
            is EdgeGestures.Action.PullEnd -> if (driving) {
                driving = false
                if (sheetAlive()) { motion.release(a.y, atMs); afterMotion(atMs) }
            }
            is EdgeGestures.Action.SlideStart -> if (sheetAlive()) slideStart(a.x, atMs)
            is EdgeGestures.Action.SlideMove -> if (sheetAlive()) slideMove(a.x, atMs)
            is EdgeGestures.Action.SlideEnd -> if (sheetAlive()) slideEnd(a.x, atMs)
            is EdgeGestures.Action.Tap -> {
                setPressed(-1)
                if (sheetAlive()) tap(a.x, a.y, atMs)
            }
            EdgeGestures.Action.Cancel -> {
                setPressed(-1)
                if (sheetAlive()) steady(atMs)
            }
            EdgeGestures.Action.Back -> return PortalAction.sendBack(guest?.onTop == true, sheetUp, null, rearDisplay())
        }
        return false
    }

    /**
     * The pull that opens the sheet: no keyguard check, nothing started — it is our own
     * layer. Not over the camera we opened ([cameraUp]: the back is the camera's then). Under
     * [sheetLock].
     */
    private fun openSheet(y: Float, atMs: Long) {
        if (cameraUp()) return
        if (!pullOpens()) { Log.i(TAG, "portal sheet: the panel is guarded (lock slider / always-on) — no sheet over it"); return }
        val s = sheetOrNull() ?: return
        syncSheetGuest()
        motion.reset()
        motion.setDetents(PortalSheet.detents(sheetGuestOnTop), atMs)
        motion.grab(touchDownY, touchDownAt)
        motion.drag(y, atMs)
        pager.reset()
        pager.pages = sheetLayout().pages
        slide = PortalSheet.Swipe.NONE
        sheetUp = true
        beginPending = true
        driving = true
        gestures.sheet = true
        dropChoice()
        pressed = -1
        s.page(0f)
        s.sliderDrag(null)
        s.position(motion.position(atMs))
        pokePanel()
        // The slider's thumb stands at the display's own density for an app without one:
        // read, if it has not been yet (a panel this service adopted, say), off this thread.
        if (sheetGuestOnTop != null && densityDefault == null) {
            runCatching { densityWorker.execute { syncDensity("the DPI slider wants the display's own") } }
        }
        sheetClock.execute {
            val p = synchronized(sheetLock) { if (!sheetUp || !beginPending) return@execute; motion.position(android.os.SystemClock.uptimeMillis()) }
            val ok = runCatching { s.begin(p) }.getOrElse { Log.w(TAG, "portal sheet: begin threw: ${it.message}"); false }
            synchronized(sheetLock) {
                beginPending = false
                if (!ok && sheetUp) {
                    Log.i(TAG, "portal sheet: could not come up — the pull does nothing")
                    sheetDown()
                } else if (sheetUp) {
                    // Let go while it was coming up: the settle already began.
                    afterMotion(android.os.SystemClock.uptimeMillis())
                }
            }
        }
    }

    /**
     * The panel's lock slider, pocket lock or always-on is up (RearActivity says so through
     * the app process: [setPanelGuarded]). Over the panel a pull then opens nothing.
     */
    @Volatile private var panelGuarded = false

    override fun setPanelGuarded(guarded: Boolean) {
        if (panelGuarded == guarded) return
        panelGuarded = guarded
        Log.i(TAG, if (guarded) "portal: the panel is guarded — no sheet over it" else "portal: the panel is open again")
    }

    /**
     * The camera we opened is (as far as we know) the rear's top app: launched and not yet
     * seen closed, or its band-tap watch running. No sheet over it — the back is the camera's.
     * Bound in time either way: never longer than a band-tap watch may run ([BAND_WATCH_MS]),
     * so a close nobody saw cannot keep the sheet away for good.
     */
    private fun cameraUp() = (bandTaps != null || cameraLaunched) &&
        android.os.SystemClock.elapsedRealtime() - cameraLaunchedAt < BAND_WATCH_MS

    private val pokeWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "portal-poke").apply { isDaemon = true } }
    }
    @Volatile private var lastPokeAt = 0L

    /**
     * The sheet up over our PANEL: its blocker keeps every touch from the panel, whose sleep
     * countdown (RearActivity.AWAKE_MS) would then run out under it — the rear dozing, the
     * sheet shut. So each opening and each touch on the sheet re-arms it, by a broadcast
     * scoped to our package, at most once a second, off the touch thread. Over a guest on
     * top nothing: the panel is behind, and its sleep there is already not the rear's.
     */
    private fun pokePanel() {
        if (sheetGuestOnTop == true) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPokeAt < POKE_EVERY_MS) return
        lastPokeAt = now
        runCatching { pokeWorker.execute { exec("am broadcast -p $PKG -a $ACTION_PORTAL_POKE") } }
    }

    /** Whether a bottom pull may open the sheet now: [PortalAction.sheetOpens]. */
    private fun pullOpens() = PortalAction.sheetOpens(panelGuarded, guest?.sheetOnTop == true)

    /** Up, or on its way up; a sheet whose layers went is put down here. Under [sheetLock]. */
    private fun sheetAlive(): Boolean {
        if (!sheetUp) return false
        if (beginPending || shellSheet?.up == true) return true
        Log.w(TAG, "portal sheet: its layers are gone — shut")
        sheetDown()
        return false
    }

    /** The sheet's boxes as they are now: the grid, the pinned apps, the guest. */
    private fun sheetLayout() = PortalSheet.layout(portalGrid, portalApps.size, sheetGuestOnTop)

    /**
     * What a finger landing at [x], [y] on the sheet at rest at [at] shows pressed: a
     * button, the slider's value, its thumb (only a finger on the thumb itself), or nothing.
     */
    private fun pressOf(layout: PortalSheet.Layout, x: Float, y: Float, at: Float) =
        when (val hit = PortalSheet.hit(layout, x, y, PortalSheet.scroll(at), pager.page)) {
            is PortalSheet.Hit.Button -> hit.index
            PortalSheet.Hit.SliderValue -> PortalSheetPainter.PRESS_VALUE
            PortalSheet.Hit.Slider -> {
                val track = layout.slider?.track
                val pkg = sliderGuest()
                if (track != null && pkg != null && PortalSheet.onThumb(track, thumbOf(pkg), x)) PortalSheetPainter.PRESS_THUMB else -1
            }
            else -> -1
        }

    private fun setPressed(i: Int) {
        if (pressed == i) return
        pressed = i
        shellSheet?.press(i)
    }

    /** A sheet a finger held but did not move, let go: on to where it was going, or still where it stands. Under [sheetLock]. */
    private fun steady(atMs: Long) {
        if (motion.phase == SheetMotion.Phase.HELD) { motion.resume(atMs); afterMotion(atMs) }
    }

    /**
     * A tap: on the settled sheet a choice — the app (its launch once the sheet is down),
     * a button ([onSheetButton]), a page's dot, the slider's track off its thumb (the DPI
     * there, applied; on the thumb nothing — one step is a few pixels) or its value
     * (Default), and off the sheet's boxes a shut; on a sheet still moving, it carries on
     * where it was going. Under [sheetLock].
     */
    private fun tap(x: Float, y: Float, atMs: Long) {
        val at = motion.settled(atMs)
        if (at == null || !motion.heading) { motion.resume(atMs); afterMotion(atMs); return }
        val apps = portalApps
        val onTop = sheetGuestOnTop
        val layout = sheetLayout()
        when (val hit = PortalSheet.hit(layout, x, y, PortalSheet.scroll(at), pager.page)) {
            is PortalSheet.Hit.App -> {
                // Pages still sliding: the cell under the finger is not yet where it will be.
                if (pager.settling) { steady(atMs); return }
                val pkg = apps.packages.getOrNull(hit.index)
                // The guest's own icon opens nothing new: see PortalAction.appTap.
                val tapped = pkg?.let { PortalAction.appTap(it, sliderGuest(), onTop) }
                when (tapped) {
                    PortalAction.AppTap.SHUT -> { Log.i(TAG, "portal sheet: $pkg is the guest already — only shut"); shutSheet(atMs, null) }
                    PortalAction.AppTap.RESUME -> { Log.i(TAG, "portal sheet: $pkg is the guest, behind — Resume"); shutSheet(atMs) { onSheetButton(0, false) } }
                    else -> shutSheet(atMs, pkg?.let { p -> { launchGuest(p, rearDisplay()); Unit } })
                }
            }
            is PortalSheet.Hit.Button -> {
                // The frame stays as hidden as the open sheet has it while the sheet shuts:
                // the choice may fade it out, and it must not come back up in between.
                frame.holdVeil()
                // Before the shut, which may hand the choice over at once (sheetDown).
                veilHeld = true
                shutSheet(atMs) { try { onSheetButton(hit.index, onTop ?: false) } finally { frame.releaseVeil() } }
            }
            is PortalSheet.Hit.Page -> {
                steady(atMs)
                pager.show(hit.index, atMs)
                startAnim()
            }
            PortalSheet.Hit.Slider -> {
                steady(atMs)
                val pkg = sliderGuest() ?: return
                val track = layout.slider?.track ?: return
                val now = RearDensity.clamp(thumbOf(pkg))
                if (PortalSheet.onThumb(track, now, x)) return
                val dpi = PortalSheet.sliderDpi(track, x)
                if (dpi != now) setAppDpi(pkg, dpi)
            }
            PortalSheet.Hit.SliderValue -> {
                steady(atMs)
                val pkg = sliderGuest() ?: return
                if (portalDpi.containsKey(pkg)) setAppDpi(pkg, null)
            }
            PortalSheet.Hit.Blank -> steady(atMs)
            PortalSheet.Hit.Outside -> shutSheet(atMs, null)
        }
    }

    /** The guest — on top or behind the panel — whose DPI the slider is: null without one. */
    private fun sliderGuest(): String? = guest?.takeIf { it.sheetOnTop != null }?.pkg

    /** The thumb's DPI for [pkg] as it stands: its own, else the display's. */
    private fun thumbOf(pkg: String) = portalDpi[pkg] ?: densityDefault ?: DensityDump.FALLBACK

    /**
     * A sideways drag begins: on the settled sheet's slider row it drags the slider's thumb,
     * above it it turns the pages (PortalSheet.swipe), else nothing.
     * The sheet itself stays where it is either way. Under [sheetLock].
     */
    private fun slideStart(x: Float, atMs: Long) {
        setPressed(-1)
        val at = motion.settled(atMs)
        steady(atMs)
        val layout = sheetLayout()
        slide = if (at == null || !motion.heading) PortalSheet.Swipe.NONE else PortalSheet.swipe(layout, touchDownY, PortalSheet.scroll(at))
        when (slide) {
            PortalSheet.Swipe.SLIDER -> {
                val pkg = sliderGuest()
                val track = layout.slider?.track
                if (pkg == null || track == null) { slide = PortalSheet.Swipe.NONE; return }
                sliderPkg = pkg
                sliderFrom = thumbOf(pkg)
                sliderAt = PortalSheet.sliderDrag(track, sliderFrom, x - touchDownX)
                setPressed(PortalSheetPainter.PRESS_THUMB)
                shellSheet?.sliderDrag(sliderAt)
            }
            PortalSheet.Swipe.PAGES -> {
                stopAnim()
                pager.grab(touchDownX, touchDownAt)
                shellSheet?.page(pager.drag(x, atMs))
            }
            PortalSheet.Swipe.NONE -> Unit
        }
    }

    /** The slide's finger at [x]: the thumb or the pages follow. Under [sheetLock]. */
    private fun slideMove(x: Float, atMs: Long) {
        when (slide) {
            PortalSheet.Swipe.SLIDER -> {
                val track = sheetLayout().slider?.track ?: return
                val dpi = PortalSheet.sliderDrag(track, sliderFrom, x - touchDownX)
                // Drawn only: each DPI change relayouts the whole display, so none is applied mid-drag.
                if (dpi != sliderAt) { sliderAt = dpi; shellSheet?.sliderDrag(dpi) }
            }
            PortalSheet.Swipe.PAGES -> shellSheet?.page(pager.drag(x, atMs))
            PortalSheet.Swipe.NONE -> Unit
        }
    }

    /** The slide let go at [x]: the slider's value applied, or the pages settling. Under [sheetLock]. */
    private fun slideEnd(x: Float, atMs: Long) {
        when (slide) {
            PortalSheet.Swipe.SLIDER -> {
                slideMove(x, atMs)
                setPressed(-1)
                val pkg = sliderPkg
                // Still the guest it was dragged for, and the thumb moved from where the drag
                // took it up: applied, once, now. Measured from there, not from the value as
                // it stands — a display default read mid-drag, or one off the slider's steps,
                // must not apply a value nobody chose.
                if (pkg != null && pkg == sliderGuest() && sliderAt != RearDensity.clamp(sliderFrom)) setAppDpi(pkg, sliderAt)
                shellSheet?.sliderDrag(null)
                sliderPkg = null
            }
            PortalSheet.Swipe.PAGES -> {
                pager.release(x, atMs)
                startAnim()
            }
            PortalSheet.Swipe.NONE -> Unit
        }
        slide = PortalSheet.Swipe.NONE
    }

    /** Settles it shut, then [then] on a thread of its own. Under [sheetLock]. */
    private fun shutSheet(atMs: Long, then: (() -> Unit)?) {
        motion.settle(0f, atMs)
        afterShut = then
        afterMotion(atMs)
    }

    /**
     * A button's choice, run once the sheet is down, on a thread of its own. [index] 0 is
     * Panel when the guest was on top ([guestOnTop], as the sheet showed it), else Resume;
     * 1 Front; 2 Close. Each is a [PortalAction.Step] (spec "Settled on the device" 1):
     *
     * - Panel, front locked (`keyguardShowing()`): `am display move-stack <guest> 0`. The
     *   guest goes behind the keyguard, unseen and not relaunched (35 ms measured), which
     *   leaves our panel on top of the rear; the rear is woken. HyperOS refuses `am start` of
     *   the panel while the front is locked, so this is the only way. The guest is then
     *   [Guest.hiddenOnFront]: still the guest, behind (see [GuestGate]).
     * - Panel, front unlocked: `am start --display <rear>` of the panel, over the guest.
     * - Resume of a hidden guest: `move-stack <guest> <rear>` and a wake ([unhide]).
     * - Resume of a guest behind the panel on the rear: the panel moves its own task to the
     *   back (a scoped broadcast, [ACTION_PORTAL_STEP_BACK]), locked or not. Only if the
     *   panel is still what the rear shows on top after [STEP_BACK_LIMIT_MS] (looked at
     *   every [STEP_BACK_POLL_MS]; nothing read is trusted as done): `move-stack <guest> 0` and
     *   back to the rear (a move to the display a task is already on does not raise it) —
     *   seen on the front for a moment when it is unlocked.
     *
     * Which of Panel / Resume runs is decided from the guest as it is when the button runs,
     * not as the sheet showed it — and when the guest is already where the button would
     * have put it, neither does; a refused `am start` of the panel changes nothing.
     * - Front: `move-stack <guest> 0`, Close: `am force-stop` — either way the guest ends and
     *   the panel comes back ([guestAction]).
     *
     * The guest's DPI stays through Panel and Resume (kept while the guest lives); the frame
     * follows whether it is on top.
     */
    private fun onSheetButton(index: Int, guestOnTop: Boolean) {
        // Decided from the guest as it is now, not as the sheet showed it when it opened.
        val step = synchronized(placing) {
            val g = guest ?: return
            // Panel (shown over a guest on top) or Resume (shown over one behind), and the
            // guest has since gone where the button would have put it: nothing left to do.
            // Running the other one instead would undo what was asked for.
            if (index == 0 && g.onTop != guestOnTop) {
                Log.i(TAG, "portal sheet: ${if (guestOnTop) "Panel" else "Resume"} — the guest is ${if (g.onTop) "on top" else "behind"} already; nothing to do")
                return
            }
            PortalAction.strip(index, g.onTop, g.hiddenOnFront) { keyguardShowing() }
        } ?: return
        Log.i(TAG, "portal sheet: $step")
        when (step) {
            // Each change of what is on top of the rear but Close happens under a cover of
            // the rear as it was, slid away once the new top has drawn (covered). Close keeps
            // the system's own close animation, which already plays for it.
            PortalAction.Step.HIDE_ON_FRONT -> synchronized(placing) {
                val g = guest ?: return
                covered("the panel", { panelShown(g) }) {
                    // Refused, it is still on top, and the keep-alive goes on seeing it there.
                    if (!moveToDisplay(g.task, 0)) return@covered false
                    guest = g.copy(onTop = false, hiddenOnFront = true)
                    guestBehind(g.pkg)
                    wake(rearDisplay())
                    true
                }
            }
            PortalAction.Step.START_PANEL -> synchronized(placing) {
                val g = guest ?: return
                covered("the panel", { panelShown(g) }) {
                    // Refused (the front locked in between, say): the guest is still on top, and
                    // its state and frame stay as they are.
                    if (!startPanel("Panel")) return@covered false
                    markOnTop(g.task, false)
                    guestBehind(g.pkg)
                    true
                }
            }
            PortalAction.Step.UNHIDE -> synchronized(placing) {
                val g = guest ?: return
                // The frame after the reveal, not before the guest is seen.
                val back = covered(g.pkg, { drawnOn(g.pkg, g.task, rearDisplay()) }) { unhide("Resume", showFrame = false) }
                if (back) {
                    if (guest?.let { it.task == g.task && it.onTop } == true) frame.show()
                    wake(rearDisplay())
                }
            }
            PortalAction.Step.PANEL_STEPS_BACK -> synchronized(placing) {
                val g = guest ?: return
                val back = covered(g.pkg, { drawnOn(g.pkg, g.task, rearDisplay()) }) changed@{
                    // The panel moves its own task to the back (RearActivity, ACTION_PORTAL_STEP_BACK):
                    // the guest is on top without leaving the rear, locked or not.
                    exec("am broadcast -p $PKG -a $ACTION_PORTAL_STEP_BACK")
                    // Watched until the guest is on top, up to STEP_BACK_LIMIT_MS: the broadcast
                    // reaches the panel when it does, and one look after a fixed wait took a slow
                    // step back for none.
                    val until = android.os.SystemClock.elapsedRealtime() + STEP_BACK_LIMIT_MS
                    var top: String?
                    do {
                        try { Thread.sleep(STEP_BACK_POLL_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@changed false }
                        top = rearTop(g)
                    } while (top != g.pkg && android.os.SystemClock.elapsedRealtime() < until)
                    val now = guest?.takeIf { it.task == g.task } ?: return@changed false
                    // A guard come up meanwhile is why the panel stayed (RearActivity refuses the
                    // step back then); the fallback would take the guest past it.
                    if (top == PKG && panelGuarded) {
                        Log.i(TAG, "portal: the panel is guarded — ${g.pkg} stays behind it")
                        return@changed false
                    }
                    // Only the panel itself still on top is a step back that did not happen. Nothing
                    // read (a dark rear composes nothing) or something else is no proof against
                    // it, and the fallback is seen on the front.
                    if (top == PKG) {
                        // The fallback: out to display 0 and back, since a move to the display a
                        // task is already on does not raise it. Seen on the front for a moment
                        // when it is unlocked.
                        Log.i(TAG, "portal: the panel did not step back — ${g.pkg} through the front")
                        moveToDisplay(g.task, 0)
                        if (!moveToDisplay(g.task, rearDisplay())) return@changed false
                    } else if (top != g.pkg) {
                        Log.i(TAG, "portal: after the step back the rear's top is ${top ?: "unknown"} — trusting it")
                    }
                    // The frame after the reveal, not before the guest is seen.
                    guestOnTopAgain(now, "Resume", showFrame = false)
                    true
                }
                if (back) {
                    if (guest?.let { it.task == g.task && it.onTop } == true) frame.show()
                    wake(rearDisplay())
                }
            }
            // Moving a task between displays plays no animation: the guest used to vanish from
            // the rear at once. Covered, and the panel revealed as Panel reveals it.
            PortalAction.Step.FRONT -> synchronized(placing) {
                val g = guest ?: return
                covered("the panel", { panelShown(g) }) {
                    guestAction("front")
                    guest == null
                }
            }
            PortalAction.Step.CLOSE -> guestAction("close")
        }
    }

    /** The guest no longer on top: no frame, and the sheet told. Its DPI stays. Under [placing]. */
    private fun guestBehind(pkg: String) {
        frame.hide()
        syncDensity("$pkg is behind")
        syncSheetGuest()
    }

    /**
     * [g] on top of the rear again, not hidden: frame, density, touches, the sheet's buttons.
     * The frame not yet with [showFrame] false: a covered Resume shows it after its reveal.
     * Under [placing].
     */
    private fun guestOnTopAgain(g: Guest, why: String, showFrame: Boolean = true) {
        Log.i(TAG, "portal: ${g.pkg} on top again — $why")
        guest = g.copy(onTop = true, hiddenOnFront = false)
        syncDensity("${g.pkg} is on top")
        if (showFrame) frame.show()
        syncTouchWatch()
        syncSheetGuest()
    }

    /**
     * A guest Panel hid on display 0 moved back onto the rear, on top. False when there is
     * no hidden guest or the move was refused. Under [placing].
     */
    private fun unhide(why: String, showFrame: Boolean = true): Boolean {
        val g = guest?.takeIf { it.hiddenOnFront } ?: return false
        if (!moveToDisplay(g.task, rearDisplay())) return false
        guestOnTopAgain(g, "back from the front: $why", showFrame)
        return true
    }

    /**
     * Our panel started over whatever is on the rear, with the last payload as show() starts
     * it; false (logged) when `am start` says it was refused, or no panel task is there after
     * it — HyperOS aborts a start under the keyguard without a word (see [show]). Under [placing].
     */
    private fun startPanel(why: String): Boolean {
        val out = exec("am start --display ${rearDisplay()} -n $PKG/$ACTIVITY --es state ${shellQuote(lastPayload)}")
        if (out.contains("Error", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
            Log.w(TAG, "portal: $why — the panel's start was refused: ${out.take(200)}")
            return false
        }
        if (pollTaskId() <= 0) {
            Log.w(TAG, "portal: $why — no panel task after its start; taken as refused")
            return false
        }
        return true
    }

    /**
     * The package the rear composes on top (SurfaceFlinger, as the keep-alive reads it), with
     * a window of [g]'s task read as [g]'s own package; null when nothing is read.
     */
    private fun rearTop(g: Guest): String? {
        val entry = GuestTask.byId(exec("am stack list"), g.task)
        val taskTop = entry?.top?.substringBefore('/')?.takeIf { it.isNotEmpty() }
        val rear = rearPhysicalId()
        return SurfaceDump.topPackage(
            exec("dumpsys SurfaceFlinger"),
            isRear = { id -> if (rear != null) id == rear else id.endsWith(REAR_PHYSICAL_ID_SUFFIX) },
            known = listOfNotNull(g.pkg, PKG, taskTop),
        ).let { if (it != null && it == taskTop) g.pkg else it }
    }

    /**
     * The front unlocked with a guest hidden on it: the guest is showing there now. It goes
     * back to the rear, and — since Panel is what put it on the front — behind the panel:
     * moved to the rear, then the panel started over it, finishing as Panel does unlocked
     * (START_PANEL). The rear is left as it is (lit or not). The user unlocked the front, so
     * the guest no longer has a keyguard to hide behind; ending it instead would lose the
     * app's place. KNOWN, AND INHERENT: between the unlock and USER_PRESENT reaching us
     * (or the next keep-alive tick) the guest is seen on the front, and for the moment
     * between its move and the panel's start it is on top of the rear.
     * From the keep-alive (GUEST_EXPOSED) and from [ensureOnRear], which the app process runs
     * on USER_PRESENT, so it is back within a moment of the unlock. Under [placing].
     */
    private fun unhideIfExposed(why: String) {
        if (guest?.hiddenOnFront != true || keyguardShowing()) return
        val g = guest ?: return
        // Already back on the rear (somebody moved it): only the flag was stale.
        if (GuestTask.byId(exec("am stack list"), g.task)?.display != 0) {
            guestRef.compareAndSet(g, g.copy(hiddenOnFront = false))
            return
        }
        if (!moveToDisplay(g.task, rearDisplay())) return
        if (!startPanel("back from the front: $why")) {
            // The panel refused: the guest is on top of the rear, and is shown as such.
            guestOnTopAgain(g, "back from the front: $why")
            return
        }
        Log.i(TAG, "portal: ${g.pkg} back from the front ($why), behind the panel")
        guest = g.copy(onTop = false, hiddenOnFront = false)
        guestBehind(g.pkg)
    }

    // -- DPI from the back ----------------------------------------------------------------
    //
    // While there is a guest, the open sheet scrolls on to its DPI slider (PortalSheet.layout):
    // dragged, it only shows the value; let go, tapped on its track, or its value tapped
    // (Default), the value is applied at once — the guest relayouts — and saved: the config
    // is the app process's to write, so the value goes there through IRearEvents once
    // changes pause ([DpiEdits]), and comes back with the next payload.

    /** Guards [dpiEdits] and the writes to [portalDpi]. A leaf, taken under [sheetLock] or alone. */
    private val dpiLock = Any()
    private val dpiEdits = DpiEdits()
    /** The app process's ear (setEvents); null until it registers, or after it died. */
    @Volatile private var events: IRearEvents? = null

    override fun setEvents(events: IRearEvents?) {
        this.events = events
        // Changes made while nobody listened go now.
        if (events != null) scheduleDpiFlush()
    }

    /** [portalDpi] set to what [make] returns (under [dpiLock]), and the sheet told. Any thread. */
    private fun setPortalDpi(make: () -> Map<String, Int>) {
        synchronized(dpiLock) {
            portalDpi = make()
            shellSheet?.dpiValues(portalDpi)
        }
    }

    /** [pkg]'s DPI set to [dpi] (null: Default) from the back. Under [sheetLock], on the reader thread: nothing here waits. */
    private fun setAppDpi(pkg: String, dpi: Int?) {
        setPortalDpi {
            dpiEdits.put(pkg, dpi, android.os.SystemClock.uptimeMillis())
            if (dpi == null) portalDpi - pkg else portalDpi + (pkg to dpi)
        }
        Log.i(TAG, "portal sheet: $pkg's DPI ${dpi ?: "back to default"} from the back")
        // Live if it is the guest. `wm density` takes a moment: off this thread, one at a
        // time; each run reads the latest map, so a quick run of changes ends at the last one.
        if (guest?.pkg == pkg) runCatching { densityWorker.execute { syncDensity("$pkg set on the back") } }
        scheduleDpiFlush()
    }

    private val densityWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "portal-density").apply { isDaemon = true } }
    }

    /** A [flushDpi] when the next edit falls due. Any thread. */
    private fun scheduleDpiFlush() {
        val at = synchronized(dpiLock) { dpiEdits.nextDueAt() } ?: return
        val wait = (at - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
        runCatching { sheetClock.schedule({ flushDpi() }, wait, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    /**
     * The edits that have fallen due ([all]: every one, as the service goes) handed to the
     * app process. With nobody listening they wait — [setEvents] sends them — and on the
     * way out they are lost: the overlay then lets the config win after its hold. One the
     * listener died under (or was dropped before) is due again ([DpiEdits.unsend]), for the
     * next [setEvents].
     */
    private fun flushDpi(all: Boolean = false) {
        if (events == null) return
        val due = synchronized(dpiLock) { dpiEdits.due(if (all) Long.MAX_VALUE else android.os.SystemClock.uptimeMillis()) }
        for ((pkg, dpi) in due) {
            val ev = events
            if (ev == null) { synchronized(dpiLock) { dpiEdits.unsend(pkg, dpi) }; continue }
            runCatching { ev.onPortalDpi(pkg, dpi ?: 0) }.onFailure {
                val dead = it is android.os.DeadObjectException
                Log.w(TAG, "portal: DPI for $pkg not handed on: ${it.message}" + if (dead) " — kept for the next listener" else "")
                // Only a dead listener: it is dropped, so this cannot spin; anything else
                // would be re-sent at once, and fail the same way.
                if (dead) {
                    synchronized(dpiLock) { dpiEdits.unsend(pkg, dpi) }
                    if (events === ev) events = null
                }
            }
        }
        if (!all) scheduleDpiFlush()
    }

    /** Whatever [motion] now says, carried out. Under [sheetLock]. */
    private fun afterMotion(nowMs: Long) {
        when (motion.phase) {
            SheetMotion.Phase.SETTLING -> { pushPosition(nowMs); if (!beginPending) startAnim() }
            SheetMotion.Phase.SHUT -> sheetDown()
            // Still: no frames for the sheet, but pages still sliding keep theirs.
            else -> { if (!pager.settling) stopAnim(); pushPosition(nowMs) }
        }
    }

    private fun pushPosition(nowMs: Long) { shellSheet?.position(motion.position(nowMs)) }

    private fun startAnim() {
        if (anim != null) return
        anim = runCatching {
            sheetClock.scheduleAtFixedRate({ animStep() }, SHEET_FRAME_MS, SHEET_FRAME_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun stopAnim() {
        anim?.cancel(false)
        anim = null
    }

    /** One frame of a settle — the sheet's, the pages', or both — on [sheetClock]. */
    private fun animStep() {
        try {
            synchronized(sheetLock) {
                if (!sheetUp || (motion.phase != SheetMotion.Phase.SETTLING && !pager.settling)) { stopAnim(); return }
                if (!sheetAlive()) return
                val now = android.os.SystemClock.uptimeMillis()
                if (pager.settling) shellSheet?.page(pager.at(now))
                if (motion.phase == SheetMotion.Phase.SETTLING) {
                    shellSheet?.position(motion.at(now))
                    if (motion.phase != SheetMotion.Phase.SETTLING) afterMotion(now)
                } else if (!pager.settling) stopAnim()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "portal sheet: settle frame failed: ${e.message}")
        }
    }

    /**
     * The sheet down: shut, its layers ended on [sheetClock] (never the reader — end()
     * may wait for its render thread), fingers back to the edges, and [afterShut] run.
     * Under [sheetLock].
     */
    private fun sheetDown() {
        stopAnim()
        val wasUp = sheetUp
        sheetUp = false
        beginPending = false
        driving = false
        pressed = -1
        slide = PortalSheet.Swipe.NONE
        sliderPkg = null
        gestures.sheet = false
        motion.reset()
        pager.reset()
        val then = afterShut
        afterShut = null
        // From here a held veil is the choice's to let go, if it runs; else it goes now.
        val held = veilHeld
        veilHeld = false
        val unrun = { if (held) frame.releaseVeil() }
        val s = shellSheet ?: return unrun()
        if (!wasUp) return unrun()
        if (then == null) unrun()
        runCatching {
            sheetClock.execute {
                runCatching { s.end() }.onFailure { Log.w(TAG, "portal sheet: end failed: ${it.message}") }
                then?.let { Thread(it).apply { isDaemon = true; name = "portal-sheet-choice"; start() } }
            }
        }.onFailure {
            runCatching { s.end() }
            if (then != null) unrun()
        }
    }

    /**
     * Shut at once, no settle, nothing chosen run: the rear is going dark, the panel is
     * hidden, the watch stopped. [sync]: end() on this thread (destroy, which exits next).
     */
    private fun closeSheetNow(why: String, sync: Boolean = false) {
        val s = synchronized(sheetLock) {
            if (!sheetUp) return
            Log.i(TAG, "portal sheet: shut — $why")
            dropChoice()
            sheetDown()
            shellSheet
        }
        if (sync) runCatching { s?.end() }
    }

    override fun setQuick(which: String, on: Boolean) {
        val cmd = when (which) {
            "bluetooth" -> "cmd bluetooth_manager ${if (on) "enable" else "disable"}"
            "wifi" -> "cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}"
            "data" -> "svc data ${if (on) "enable" else "disable"}"
            else -> return
        }
        Log.i(TAG, "quick $which -> $on: ${exec(cmd)}")
    }

    override fun setRearBrightness(value: Int) {
        val v = value.coerceIn(1, 255)
        // Manual first, or an automatic mode would take the value straight back.
        exec("[ \"$(settings get secure sub_display_screen_brightness_mode)\" = 0 ] || settings put secure sub_display_screen_brightness_mode 0; settings put system sub_display_screen_brightness $v")
    }

    override fun removeXiaomiRearApp(): Boolean {
        val out = exec("pm uninstall -k --user 0 ${Panel.XIAOMI_REAR_APP}")
        Log.i(TAG, "remove ${Panel.XIAOMI_REAR_APP}: $out")
        if (out.contains("Success")) return true
        // HyperOS 4 (Android 17) refuses that from shell: "only root can delete system app
        // for a particular user", and `pm disable-user` is refused too ("Cannot disable
        // system packages"). Suspending is still allowed, and a suspended rear-screen app
        // cannot bring its launcher up — measured: the lit rear display then shows nothing
        // of Xiaomi's but its two invisible gesture strips. Its launcher may already be up,
        // and suspension does not take down what is running, so it is stopped as well.
        // restoreXiaomiRearApp() already lifts a suspension.
        val suspended = exec("pm suspend --user 0 ${Panel.XIAOMI_REAR_APP}")
        Log.i(TAG, "suspend ${Panel.XIAOMI_REAR_APP}: $suspended")
        if (!suspended.contains("suspended state: true")) return false
        exec("am force-stop ${Panel.XIAOMI_REAR_APP}")
        return true
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
        // A keep-alive interrupted by hide() mid-tick must not re-place the hidden panel.
        if (Thread.currentThread().isInterrupted) return
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
                    // Except over a guest on top: the panel's own sleep timer runs out behind
                    // it, and skipping would stop the guest being watched at all. A rear that
                    // is really dark shows here as nothing on top (GUEST_BEHIND).
                    // The touch watch's safety net: a getevent that died is restarted here.
                    syncTouchWatch()
                    if (dozing && guest?.onTop != true) continue

                    // The App Portal's guest first: its own rule (GuestGate), and the usual
                    // recovery below only when that rule says so.
                    val g = guest
                    if (g != null) {
                        val entry = GuestTask.byId(exec("am stack list"), g.task)
                        // The guest's window may belong to another package — a permission
                        // dialog, a chooser — started into its task; on top, that is still
                        // the guest, not something that has taken the rear.
                        val taskTop = entry?.top?.substringBefore('/')?.takeIf { it.isNotEmpty() }
                        val rear = rearPhysicalId()
                        // known: SurfaceFlinger shortens long layer names with [...], and a
                        // shortened one is matched back to whichever of these it starts.
                        val known = listOfNotNull(g.pkg, PKG, taskTop)
                        val top = SurfaceDump.topPackage(
                            exec("dumpsys SurfaceFlinger"),
                            isRear = { id -> if (rear != null) id == rear else id.endsWith(REAR_PHYSICAL_ID_SUFFIX) },
                            known = known,
                        ).let { if (it != null && it == taskTop) g.pkg else it }
                        val tick = GuestGate.tick(
                            g.pkg, entry != null, entry?.display == displayId, top, PKG,
                            hiddenOnFront = g.hiddenOnFront, onFront = entry?.display == 0,
                            frontLocked = { keyguardShowing() },
                        )
                        // Mid-launch, the guest is the launch's: only a gone one is acted on,
                        // and that is checked again under the lock, after the launch.
                        if (!g.placed && tick != GuestGate.Tick.GUEST_GONE) continue
                        when (tick) {
                            GuestGate.Tick.GUEST_ON_TOP -> {
                                // Compare-and-set against this reading: a launch or a guest
                                // action since it was taken wins.
                                // A hidden flag here is stale (it is on the rear): cleared with it.
                                if ((!g.onTop || g.hiddenOnFront) &&
                                    guestRef.compareAndSet(g, g.copy(onTop = true, hiddenOnFront = false))) {
                                    syncSheetGuest()
                                }
                                // Every tick, but a comparison unless something changed (or a
                                // refused command is due a retry).
                                syncDensity("${g.pkg} is on top")
                                if (guest?.let { it.task == g.task && it.onTop } == true) frame.show()
                                continue
                            }
                            GuestGate.Tick.GUEST_BEHIND -> {
                                // Behind the panel, it is no longer on top. On a dark rear
                                // (top null) only the frame goes: nothing has covered it, and
                                // it is on top again the moment the display lights.
                                // A hidden flag on a guest that is on the rear is stale (it came
                                // back some other way than Resume): cleared in the same swap.
                                val stale = g.hiddenOnFront && entry?.display == displayId
                                val covered = top != null && g.onTop
                                if ((covered || stale) &&
                                    guestRef.compareAndSet(g, g.copy(onTop = g.onTop && !covered, hiddenOnFront = g.hiddenOnFront && !stale)) &&
                                    covered) {
                                    syncSheetGuest()
                                }
                                syncDensity("${g.pkg} is behind")
                                frame.hide()
                                // A dark rear: nobody can see or touch a sheet up there.
                                if (top == null) closeSheetNow("the rear is dark")
                                // The panel back in front after the camera was: somebody closed it.
                                // Never seen in front: forgotten, or no sheet opens over a guest
                                // behind the panel ever again (cameraUp).
                                if (top == PKG) { if (cameraLaunched && cameraWasOnTop) closeCamera() else forgetUnseenCamera() }
                                continue
                            }
                            // The front unlocked over a guest hidden there by Panel.
                            GuestGate.Tick.GUEST_EXPOSED -> {
                                synchronized(placing) { unhideIfExposed("the front was unlocked") }
                                continue
                            }
                            // Checked again under the lock: this reading may predate a launch
                            // that has since placed (or replaced) the guest, and ending that
                            // one would undo it.
                            GuestGate.Tick.GUEST_GONE -> {
                                synchronized(placing) {
                                    val now = guest
                                    val at = now?.let { GuestTask.byId(exec("am stack list"), it.task)?.display }
                                    // Hidden on the front by Panel is not gone.
                                    val still = at == displayId || (at == 0 && now?.hiddenOnFront == true)
                                    // Asleep on purpose, the rear stays dark: the panel's task is
                                    // already behind, and is simply revealed. Gone too, it is
                                    // relaunched on the next tick that is not asleep.
                                    if (now != null && !still) {
                                        if (dozing) dropGuest("its task left the rear") else endGuest("its task left the rear")
                                    }
                                }
                                continue
                            }
                            // Something else has the rear; the usual recovery below puts the
                            // panel over it, and the frame has nothing to frame.
                            GuestGate.Tick.USUAL -> {
                                frame.hide()
                                // And it is shown at the display's own density, not the guest's.
                                syncDensity("another app has the rear", thirdOnTop = true)
                                // The usual recovery re-places, which wakes: not on a panel
                                // asleep on purpose.
                                if (dozing) continue
                            }
                        }
                    }

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
                        Where.REAR -> {
                            adoptPlaced(displayId, "keep-alive")
                            if (cameraLaunched && cameraWasOnTop) closeCamera() else forgetUnseenCamera()
                        }
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

    /** What a checked command came to. */
    private sealed interface Run {
        /** Ran, exit 0, no error printed. */
        data class Ok(val out: String) : Run
        /** Ran and said no: non-zero exit, or an error / exception printed. Known not to have happened. */
        data class Refused(val out: String) : Run
        /** Interrupted, or never started: it may or may not have happened. */
        data object Unknown : Run
    }

    /**
     * [exec] for where a failure must not pass for success, and refused must not pass for unknown.
     *
     * A leftover interrupt flag on the calling thread (the keep-alive's, say) used to make
     * waitFor() throw AFTER the command had completed, which read as Unknown forever. So the
     * flag is taken off before waiting, the command is classified by what it really did, and
     * the flag is put back afterwards for the thread's next sleep to see.
     */
    private fun execChecked(cmd: String): Run {
        var interrupted = false
        try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }.trim()
            if (Thread.interrupted()) interrupted = true
            var code: Int? = null
            while (code == null) {
                try {
                    code = p.waitFor()
                } catch (e: InterruptedException) {
                    interrupted = true
                    // Finished all the same: its outcome is known. Still running: unknown.
                    if (!p.isAlive) code = p.exitValue() else return Run.Unknown
                }
            }
            // Error text only means a refusal from wm; `settings get` may legitimately print anything.
            val errorText = cmd.startsWith("wm ") &&
                (out.contains("Exception", ignoreCase = true) || out.contains("Error", ignoreCase = true))
            return if (code != 0 || errorText) {
                Log.w(TAG, "exit $code: $cmd: ${out.take(200)}")
                Run.Refused(out)
            } else Run.Ok(out)
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $cmd", e); return Run.Unknown
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun exec(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        p.waitFor()
        out.trim()
    } catch (e: InterruptedException) {
        // Kept, so the keep-alive's next sleep sees it and the thread ends as asked.
        Thread.currentThread().interrupt(); ""
    } catch (e: Exception) {
        Log.e(TAG, "exec failed: $cmd", e); ""
    }

    private companion object {
        /** After a failed re-read of display 1's density, how long before the next exec. */
        const val REREAD_BACKOFF_MS = 30_000L
        /** after a direct start on the rear display, before believing it took */
        private const val DIRECT_START_SETTLE_MS = 1_200L
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

        /** Global setting holding the density crash marker; see syncDensity's KDoc. */
        const val DENSITY_MARKER = "aiusage_rear_density"

        /** MIUI's camera, and the exact flags its own sub-screen launcher uses. */
        private const val CAMERA_COMPONENT = "com.android.camera/.Camera"

        /** The same app, without the component, for releasing it again. */
        private const val CAMERA_PACKAGE = "com.android.camera"
        /** The longest a band-tap watch runs, in case the camera's end is never seen. */
        private const val BAND_WATCH_MS = 30 * 60_000L
        /** How long Back gets to close the camera before its task is removed instead. */
        private const val BACK_SETTLE_MS = 900L
        /** A camera launch not seen in front of the panel by then, with the panel in front, is over. */
        private const val CAMERA_UNSEEN_MS = 5_000L
        /** How long Resume gives the panel to step back before the guest goes through the front. */
        private const val STEP_BACK_LIMIT_MS = 1_200L
        /** How often Resume looks at the rear's top while the panel steps back. */
        private const val STEP_BACK_POLL_MS = 100L

        /** How long a moved guest must survive before it counts as placed. */
        private const val GUEST_SURVIVE_MS = 2_000L
        /** The longest a launch waits for an app's startup screens to settle. */
        private const val GUEST_SETTLE_LIMIT_MS = 6_000L
        /** Between looks for an arriving guest's first draw: each look is a dumpsys of its own (~100 ms). */
        private const val DRAWN_POLL_MS = 20L
        /** The sheet's settle, a frame at a time. */
        private const val SHEET_FRAME_MS = 16L
        /** No rear touchscreen found: how long before getevent is asked again. */
        private const val TOUCH_RETRY_MS = 60_000L
        /** A touch watch that ends sooner than this is suspect: its device path is asked again. */
        private const val TOUCH_SHORT_LIFE_MS = 1_000L
        /** The font did not load: how long before it is tried again. */
        private const val SHEET_FONT_RETRY_MS = 60_000L
        /** The guest's icon on the sheet's Resume is rasterised this big (drawn at PortalSheet.APP_MARK). */
        private const val GUEST_ICON_PX = 96

        /** The least time between two pokes keeping the panel awake under the sheet. */
        private const val POKE_EVERY_MS = 1_000L
        /** RearActivity re-arms its sleep countdown on it: the sheet's blocker keeps touches from the panel. */
        const val ACTION_PORTAL_POKE = "dev.kb896.aiusage.PORTAL_POKE"
        /** The panel moves its own task to the back (Resume); RearActivity.ACTION_PORTAL_STEP_BACK. */
        const val ACTION_PORTAL_STEP_BACK = "dev.kb896.aiusage.PORTAL_STEP_BACK"
        /**
         * What may go into a shell command as a package or a component: these come from
         * the app's config and from dumps, and this service runs them at shell uid.
         */
        private val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
        private val COMPONENT = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.]+""")

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
