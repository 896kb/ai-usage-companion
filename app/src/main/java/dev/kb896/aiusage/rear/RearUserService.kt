package dev.kb896.aiusage.rear

import android.content.Context
import android.util.Log
import dev.kb896.aiusage.BuildConfig
import dev.kb896.aiusage.IPortalCallback
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
    @Suppress("unused") constructor(context: Context)

    /** Volatile: the App Portal's binder calls and the keep-alive read it off the lock. */
    @Volatile private var placedDisplayId = -1

    /** Last payload shown, so the keep-alive can re-place without the app's help. */
    private var lastPayload: String = "{}"
    private var keepAlive: Thread? = null

    /** Consecutive failed attempts to move the panel off the main display. */
    private var strandStrikes = 0

    /** When the panel was last rebuilt from scratch, for the cooldown below. */
    private var lastReplaceAt = 0L

    override fun destroy() {
        // The guest's density must not outlive the service that set it. Closed first, so
        // nothing re-applies one between this reset and the exit.
        synchronized(densityLock) {
            densityClosed = true
            applyDensity(null, "the service is going")
        }
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
        // And with the keep-alive gone nothing would ever see a guest leave, so its frame
        // and its watcher would run on for good. The guest app itself is left where it is.
        // Always under the lock, so a launch in flight finishes first and is then undone,
        // rather than placing its guest after this looked and found none.
        synchronized(placing) { if (guest != null) dropGuest("the panel was hidden") }
        // dropGuest resets it when there was a guest; this covers there being none.
        syncDensity("the panel was hidden")
    }

    override fun update(payload: String) {
        styleFrame(payload)
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
        // The panel's own sleep timer can run out while it sits behind a guest; that is
        // not the rear going dark, and the guest on top keeps its frame. A rear that really
        // is dark is seen by the keep-alive (nothing on top) and hides it there.
        val guestOnTop = guest?.onTop == true
        if (dozing && !guestOnTop) frame.hide() else if (!dozing && guestOnTop) frame.show()
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
            return
        }
        // About to come up over the guest, if there is one: it is no longer on top. (Not
        // while a portal is open over it — the panel is already there for that.)
        val g = guest
        if (g != null && !portalOverGuest) {
            if (markOnTop(g.task, false)) tell { it.onGuest(g.pkg, false) }
            frame.hide()
            syncDensity("the panel is coming up over ${g.pkg}")
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
        val out = exec("am start --display $displayId -f $CAMERA_FLAGS -n $CAMERA_COMPONENT")
        val ok = !out.contains("Error", ignoreCase = true)
        if (!ok) Log.w(TAG, "camera launch refused: ${out.take(200)}")
        if (ok) {
            cameraLaunched = true
            cameraWasOnTop = false
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
        val since = android.os.SystemClock.uptimeMillis()
        Log.i(TAG, "camera close: watching $path for a tap in the left ${"%.1f".format(band * 100)}%")
        Thread {
            val tap = BandTap(maxX, maxY, band)
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val now = android.os.SystemClock.uptimeMillis()
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
        stopBandTaps()
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
     * task survives its move, the keep-alive leaves the frame and the panel's strip alone.
     */
    private data class Guest(val pkg: String, val task: Int, val onTop: Boolean, val placed: Boolean = false)
    private val guestRef = AtomicReference<Guest?>(null)
    private var guest: Guest?
        get() = guestRef.get()
        set(value) = guestRef.set(value)
    @Volatile private var portal: IPortalCallback? = null
    /** True from a relayed pull's start until the panel says the portal shut. */
    @Volatile private var portalOverGuest = false
    /** When [portalOverGuest] was last set, so a portal that never reported back is let go. */
    @Volatile private var portalAt = 0L
    private val frame by lazy { PortalFrame(rearLayerStack()) }

    /** [onTop] on whatever guest has [task] now; true if this call changed it. */
    private fun markOnTop(task: Int, onTop: Boolean): Boolean {
        while (true) {
            val g = guestRef.get() ?: return false
            if (g.task != task || g.onTop == onTop) return false
            if (guestRef.compareAndSet(g, g.copy(onTop = onTop))) return true
        }
    }

    override fun setPortalCallback(callback: IPortalCallback?) {
        portal = callback
        val g = guest
        tell { it.onGuest(g?.pkg.orEmpty(), g?.onTop == true) }
    }

    /** A call to the panel; a dead one is forgotten, never thrown. */
    private fun tell(call: (IPortalCallback) -> Unit) {
        val p = portal ?: return
        try { call(p) } catch (e: Exception) {
            Log.w(TAG, "portal callback gone: ${e.message}")
            if (portal === p) portal = null
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
            portalDpi = config.portalDpi
        } catch (e: Exception) {
            Log.w(TAG, "portal frame: no style from the payload: ${e.message}")
            return
        }
        syncDensity("a new payload")
    }

    /**
     * DPI PER APP: display 1's density follows the guest. While a guest is on top it has
     * its own (config.portalDpi, see [RearDensity]); any other time — the panel on top, no
     * guest — the display has its default. Each change relayouts everything on display 1
     * (the panel declares configChanges density, so it is untouched), so `wm density` runs
     * only when the target differs from [appliedDensity], and the keep-alive's per-tick
     * call costs a comparison, never a dump.
     *
     * Its own lock, [densityLock], not [placing]: the keep-alive's on-top/behind branches
     * and guestAction's panel/resume run off [placing] on purpose — it is held through a
     * launch's settle and survive waits, seconds at a time — and taking it here would stall
     * them behind any launch. [densityLock] is a leaf: taken inside [placing] (launch, show,
     * end), never the other way round. Each call reads the guest afresh under it, so
     * whichever call runs last applies the latest state; two threads can never leave an
     * older value set after a newer one.
     *
     * Never left behind: dropGuest (so endGuest), hide() and destroy() all reset it.
     * What this cannot cover is Shizuku killing the service process outright; then the
     * override survives on display 1. The next service instance's first read of
     * `wm density` sees it as [appliedDensity], so its first evaluation — the first
     * payload — puts the display's default back.
     */
    private val densityLock = Any()
    /** The override this service has set on display 1; null = the display's own. Under [densityLock]. */
    private var appliedDensity: Int? = null
    /** Whether `wm density` has been read yet (once per service). Under [densityLock]. */
    private var densityRead = false
    /** Set by destroy(): nothing may apply a density after its reset. Under [densityLock]. */
    private var densityClosed = false
    /** Display 1's own density, for the log. */
    private var physicalDensity = DensityDump.FALLBACK
    /** The config's per-app DPI, from the latest payload. */
    @Volatile private var portalDpi: Map<String, Int> = emptyMap()

    /**
     * Display 1 at the density the guest's state calls for. A guest still being placed
     * counts as on top: its density is set before its move, so it arrives laid out once
     * at its own DPI instead of landing at the default and relayouting when it is confirmed.
     */
    private fun syncDensity(why: String) = synchronized(densityLock) {
        if (densityClosed) return@synchronized
        val g = guest
        val target = RearDensity.target(g?.pkg, g != null && (g.onTop || !g.placed), portalDpi)
        applyDensity(target, why)
    }

    /** Under [densityLock]. A refused command leaves [appliedDensity] as it was, so the next call retries. */
    private fun applyDensity(target: Int?, why: String) {
        if (!densityRead) {
            densityRead = true
            val d = DensityDump.parse(exec("wm density -d ${Panel.DISPLAY_ID}"))
            physicalDensity = d.physical ?: DensityDump.FALLBACK
            appliedDensity = d.override
            Log.i(TAG, "density: display ${Panel.DISPLAY_ID} is $physicalDensity dpi" +
                (d.override?.let { ", overridden to $it — reset unless a guest wants one" } ?: ""))
        }
        if (target == appliedDensity) return
        val out = exec(
            if (target != null) "wm density $target -d ${Panel.DISPLAY_ID}"
            else "wm density reset -d ${Panel.DISPLAY_ID}"
        )
        if (out.contains("Exception", ignoreCase = true) || out.contains("Error", ignoreCase = true)) {
            Log.w(TAG, "density: ${target ?: "reset"} refused — $why: ${out.take(200)}")
            return
        }
        appliedDensity = target
        Log.i(TAG, "density: display ${Panel.DISPLAY_ID} at ${target?.let { "$it dpi" } ?: "its own $physicalDensity dpi"} — $why")
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
        portalOverGuest = false
        // One guest at a time: the old one goes to the front, alive — never killed.
        guest?.let { old -> if (old.pkg != pkg) moveToDisplay(old.task, 0) }
        repeat(2) { attempt ->
            val task = startSettled(component, pkg)
            if (task == null) { endGuest("never started"); return@synchronized false }
            guest = Guest(pkg, task, onTop = false)
            // Before the move, so it lands laid out once at its own DPI (see syncDensity).
            syncDensity("$pkg is landing")
            moveToDisplay(task, displayId)
            wake(displayId)
            // The frame comes up as the app lands, not after the survive wait; show() is
            // idempotent, so guestPlaced() below does not start a second one.
            frame.show()
            // A task gone within two seconds of the move was a trampoline the move
            // relaunched (Shazam, measured); once more from the start, then give up.
            Thread.sleep(GUEST_SURVIVE_MS)
            val entry = GuestTask.byId(exec("am stack list"), task)
            if (entry != null && entry.display == displayId) {
                guest = Guest(pkg, task, onTop = true)
                Log.i(TAG, "portal: $pkg is the guest, task $task")
                guestPlaced(displayId)
                return@synchronized true
            }
            frame.hide()
            Log.w(TAG, "portal: $pkg's task $task did not survive the move (try ${attempt + 1})")
        }
        endGuest("did not stay on the rear")
        false
    }

    /** Started on the front; its task once the top activity has held still. Null if none. */
    private fun startSettled(component: String, pkg: String): Int? {
        exec("am start -n $component")
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
        portalOverGuest = false
        guest?.let { old -> if (old.task != task) moveToDisplay(old.task, 0) }
        guest = Guest(pkg, task, onTop = true)
        syncDensity("$pkg is landing")
        if (!moveToDisplay(task, displayId)) { endGuest("move refused"); return@synchronized "" }
        wake(displayId)
        guestPlaced(displayId)
        pkg
    }

    /** A guest is on top: the frame, the watcher, and the panel told. Under [placing]. */
    private fun guestPlaced(displayId: Int) {
        guest = guest?.copy(placed = true)
        syncDensity("${guest?.pkg} is the guest") // already set as it landed; a no-op then
        frame.show() // already up from the launch's move; a no-op while running
        watchGuest(displayId)
        tell { it.onGuest(guest?.pkg.orEmpty(), true) }
    }

    /** No guest any more: frame, watcher and the panel's strip off. Under [placing]. */
    private fun dropGuest(why: String) {
        Log.i(TAG, "portal: guest over — $why")
        guest = null
        portalOverGuest = false
        frame.hide()
        syncDensity("the guest is over")
        stopGuestWatch()
        tell { it.onGuest("", false) }
    }

    /** [dropGuest], and the panel back in front. Under [placing]. */
    private fun endGuest(why: String) {
        dropGuest(why)
        if (placedDisplayId >= 0) show(lastPayload, placedDisplayId)
    }

    override fun guestAction(which: String?) {
        when (which) {
            // The panel stays over a live guest; the strip then offers Resume.
            "panel" -> {
                val g = guest ?: return
                portalOverGuest = false
                markOnTop(g.task, false)
                frame.hide()
                syncDensity("the panel over ${g.pkg}")
                tell { it.onGuest(g.pkg, false) }
            }
            // RearActivity has gone behind (moveTaskToBack); the guest is on top again.
            "resume" -> {
                val g = guest ?: return
                portalOverGuest = false
                markOnTop(g.task, true)
                syncDensity("${g.pkg} resumed")
                frame.show()
                tell { it.onGuest(g.pkg, true) }
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

    /**
     * Over a guest, the rear touchscreen read as the camera's is: the bottom pull relayed
     * to the panel, and a right-edge Back sent to the guest. See EdgeGestures.
     */
    private val guestWatch = AtomicReference<Process?>(null)

    /** One screenshot, started at once when a finger lands in the pull band. */
    private inner class Shot {
        @Volatile var png: ByteArray? = null
        val thread: Thread = Thread { png = captureRear() }.apply { isDaemon = true; name = "portal-shot"; start() }
    }

    private fun watchGuest(displayId: Int) {
        stopGuestWatch()
        val device = BandTap.rearDevice(exec("getevent -pl"))
        if (device == null) {
            if (!warnedNoRearTouch) {
                warnedNoRearTouch = true
                Log.w(TAG, "portal: no rear touchscreen in getevent — no pull or Back over the guest")
            }
            return
        }
        val (path, maxX, maxY) = device
        val proc = try {
            ProcessBuilder("sh", "-c", "exec getevent $path").redirectErrorStream(true).start()
        } catch (e: Exception) { Log.w(TAG, "portal: getevent failed: ${e.message}"); return }
        guestWatch.set(proc)
        Thread {
            val touches = RearTouches(maxX, maxY)
            val gestures = EdgeGestures()
            var shot: Shot? = null
            // Set only by a pull this thread accepted, so a refused one relays nothing.
            var relaying = false
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val e = touches.feed(line, android.os.SystemClock.uptimeMillis()) ?: continue
                        for (a in gestures.feed(e)) when (a) {
                            EdgeGestures.Action.Arm -> shot = if (guest?.onTop == true) Shot() else null
                            EdgeGestures.Action.Disarm -> { shot = null; relaying = false }
                            is EdgeGestures.Action.PullStart -> when {
                                guest?.onTop != true || portalOverGuest -> shot = null
                                // No callback means the panel's process is gone: no portal to
                                // relay to, but the pull still asks for the panel, so it comes back.
                                portal == null -> {
                                    shot = null
                                    if (keyguardShowing()) {
                                        Log.d(TAG, "portal: pull over the guest refused — no panel callback, keyguard")
                                    } else {
                                        Log.i(TAG, "portal: pull over the guest with no panel callback — bringing the panel back")
                                        exec("am start --display $displayId -n $PKG/$ACTIVITY")
                                    }
                                }
                                keyguardShowing() -> { shot = null; Log.d(TAG, "portal: pull over the guest refused — keyguard") }
                                else -> {
                                    portalOverGuest = true
                                    portalAt = android.os.SystemClock.uptimeMillis()
                                    relaying = true
                                    exec("am start --display $displayId -n $PKG/$ACTIVITY")
                                    tell { it.onPull(0, a.y) }
                                    val waiting = shot
                                    shot = null
                                    if (waiting != null) Thread {
                                        // Never holds up the gesture: the backdrop fades in when it lands.
                                        try { waiting.thread.join(SHOT_WAIT_MS) } catch (_: InterruptedException) { return@Thread }
                                        waiting.png?.let { sendFrame(it) }
                                    }.apply { isDaemon = true; name = "portal-frame-send"; start() }
                                }
                            }
                            is EdgeGestures.Action.PullMove -> if (relaying) tell { it.onPull(1, a.y) }
                            is EdgeGestures.Action.PullEnd -> if (relaying) { relaying = false; tell { it.onPull(2, a.y) } }
                            EdgeGestures.Action.Back -> if (guest?.onTop == true && !portalOverGuest) {
                                exec("input -d $displayId keyevent KEYCODE_BACK")
                            }
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // The stream closing under us is how stopGuestWatch() ends this.
            } catch (e: Exception) {
                Log.w(TAG, "portal: guest watcher stopped", e)
            } finally {
                guestWatch.compareAndSet(proc, null)
                runCatching { proc.destroy() }
            }
        }.apply { isDaemon = true; name = "portal-guest-touches"; start() }
    }

    private var warnedNoRearTouch = false

    private fun stopGuestWatch() {
        guestWatch.getAndSet(null)?.let { runCatching { it.destroy() } }
    }

    /** The rear as a PNG, from shell's screencap — about 240 ms. Null if it fails. */
    private fun captureRear(): ByteArray? {
        val id = rearPhysicalId() ?: return null
        return try {
            val p = ProcessBuilder("sh", "-c", "exec screencap -d $id -p").start()
            p.inputStream.use { it.readBytes() }.takeIf { it.size > 100 }
        } catch (e: Exception) { null }
    }

    /** Handed over as a pipe, so no file needs to be readable by both uids. */
    private fun sendFrame(bytes: ByteArray) {
        if (portal == null) return
        val (read, write) = try { android.os.ParcelFileDescriptor.createPipe() } catch (e: Exception) {
            Log.w(TAG, "portal: no pipe for the frame: ${e.message}"); return
        }
        Thread {
            // A panel that closes its end early is not an error worth a crash: an uncaught
            // exception here would take the whole shell service down.
            try {
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) }
            } catch (e: Exception) {
                Log.w(TAG, "portal: frame not taken: ${e.message}")
            }
        }.apply { isDaemon = true; name = "portal-frame-pipe"; start() }
        tell { it.onFrame(read) }
        runCatching { read.close() }
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
                        val tick = GuestGate.tick(g.pkg, entry != null, entry?.display == displayId, top, PKG)
                        // Mid-launch, the guest is the launch's: only a gone one is acted on,
                        // and that is checked again under the lock, after the launch.
                        if (!g.placed && tick != GuestGate.Tick.GUEST_GONE) continue
                        when (tick) {
                            GuestGate.Tick.GUEST_ON_TOP -> {
                                // The guest composited on top means no portal is over it. A
                                // pull that started one and never heard back (refused start,
                                // no callback, a recreated panel) is let go after a moment.
                                if (portalOverGuest && android.os.SystemClock.uptimeMillis() - portalAt > PORTAL_STALE_MS) {
                                    Log.i(TAG, "portal: the guest is on top — no portal over it any more")
                                    portalOverGuest = false
                                }
                                // Compare-and-set against this reading: a launch or a guest
                                // action since it was taken wins.
                                if (!g.onTop && guestRef.compareAndSet(g, g.copy(onTop = true))) {
                                    tell { it.onGuest(g.pkg, true) }
                                }
                                // Every tick, but a comparison unless the state changed (or a
                                // refused command is due a retry).
                                syncDensity("${g.pkg} is on top")
                                if (guest?.let { it.task == g.task && it.onTop } == true) frame.show()
                                continue
                            }
                            GuestGate.Tick.GUEST_BEHIND -> {
                                // Behind the panel, it is no longer on top. On a dark rear
                                // (top null) only the frame goes: nothing has covered it, and
                                // it is on top again the moment the display lights.
                                if (top != null && g.onTop && !portalOverGuest &&
                                    guestRef.compareAndSet(g, g.copy(onTop = false))) {
                                    tell { it.onGuest(g.pkg, false) }
                                }
                                syncDensity("${g.pkg} is behind")
                                if (top == null || !portalOverGuest) frame.hide()
                                // The panel back in front after the camera was: somebody closed it.
                                if (top == PKG && cameraLaunched && cameraWasOnTop) closeCamera()
                                continue
                            }
                            // Checked again under the lock: this reading may predate a launch
                            // that has since placed (or replaced) the guest, and ending that
                            // one would undo it.
                            GuestGate.Tick.GUEST_GONE -> {
                                synchronized(placing) {
                                    val now = guest
                                    val still = now != null && GuestTask.byId(exec("am stack list"), now.task)?.display == displayId
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
    } catch (e: InterruptedException) {
        // Kept, so the keep-alive's next sleep sees it and the thread ends as asked.
        Thread.currentThread().interrupt(); ""
    } catch (e: Exception) {
        Log.e(TAG, "exec failed: $cmd", e); ""
    }

    private companion object {
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

        /** MIUI's camera, and the exact flags its own sub-screen launcher uses. */
        private const val CAMERA_COMPONENT = "com.android.camera/.Camera"

        /** The same app, without the component, for releasing it again. */
        private const val CAMERA_PACKAGE = "com.android.camera"
        /** The longest a band-tap watch runs, in case the camera's end is never seen. */
        private const val BAND_WATCH_MS = 30 * 60_000L
        /** How long Back gets to close the camera before its task is removed instead. */
        private const val BACK_SETTLE_MS = 900L

        /** How long a moved guest must survive before it counts as placed. */
        private const val GUEST_SURVIVE_MS = 2_000L
        /** The longest a launch waits for an app's startup screens to settle. */
        private const val GUEST_SETTLE_LIMIT_MS = 6_000L
        /** How long a pull's portal may go unreported before the guest on top ends it. */
        private const val PORTAL_STALE_MS = 2_000L
        /** The longest a pull's frame waits for its screenshot before going without. */
        private const val SHOT_WAIT_MS = 1_000L
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
