package dev.kb896.aiusage.rear

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.StatePayload

class RearActivity : Activity() {

    companion object {
        const val ACTION_STATE = "dev.kb896.aiusage.STATE"

        /** Sent when this activity finds itself on the wrong display. Handled by
         *  UsagePollService, which owns the Shizuku bridge and can move it back —
         *  this process cannot, the move needs shell privileges. */
        const val ACTION_STRANDED = "dev.kb896.aiusage.STRANDED"
        const val ACTION_FINISH = "dev.kb896.aiusage.FINISH"

        /**
         * The panel's camera button was pressed.
         *
         * Sent from `:rear` to the main process, which is where the Shizuku binding
         * lives — this process has none of its own and should not grow one. An ordinary
         * broadcast is enough: same package, same uid, no privilege crosses here. The
         * privileged part happens on the other side.
         */
        const val ACTION_OPEN_CAMERA = "dev.kb896.aiusage.OPEN_CAMERA"

        /** Another app's notification, on its way to the panel. */
        const val ACTION_NOTIFICATION = "dev.kb896.aiusage.NOTIFICATION"
        const val EXTRA_NOTIFICATION_APP = "app"
        const val EXTRA_NOTIFICATION_PKG = "pkg"
        const val EXTRA_NOTIFICATION_TITLE = "title"
        const val EXTRA_NOTIFICATION_TEXT = "text"
        const val EXTRA_NOTIFICATION_MORE_TITLE = "moreTitle"
        const val EXTRA_NOTIFICATION_MORE_TEXT = "moreText"

        /**
         * What is playing, on its way to the panel.
         *
         * Text only. The panel is told the track, the artist and whether it is running;
         * it is never given the session, because a MediaController is bound to the
         * process that obtained it and this one has no notification access of its own.
         */
        const val ACTION_MEDIA = "dev.kb896.aiusage.MEDIA"
        const val EXTRA_MEDIA_ACTIVE = "mediaActive"
        const val EXTRA_MEDIA_TITLE = "mediaTitle"
        const val EXTRA_MEDIA_ARTIST = "mediaArtist"
        const val EXTRA_MEDIA_PLAYING = "mediaPlaying"

        /** A PATH to the cover, not the cover. See MediaWatcher.writeArt for why. */
        const val EXTRA_MEDIA_ART = "mediaArt"

        /**
         * Where the track is, and how to keep counting without being told again.
         *
         * A position alone would be stale the moment it arrived — the panel redraws on
         * the frame clock and the player only speaks when something changes, so a bar
         * driven by reports would sit still for a whole track and then jump. What
         * crosses instead is a position, the clock reading it was taken at, and the
         * playback speed. Both processes read the same system elapsed-time clock, so the
         * panel can carry the count forward itself and stay exact.
         *
         * Duration is 0 or negative for anything that has no end — a live stream, a
         * radio session — and the bar is simply not drawn for those.
         */
        const val EXTRA_MEDIA_DURATION = "mediaDuration"
        const val EXTRA_MEDIA_POSITION = "mediaPosition"
        const val EXTRA_MEDIA_POSITION_AT = "mediaPositionAt"
        const val EXTRA_MEDIA_SPEED = "mediaSpeed"

        /**
         * The lyric sheet, in LRC, and the recording it belongs to.
         *
         * Two extras rather than one because they answer different questions, and the
         * second is what stops the artwork's oldest bug from happening again in a much
         * more visible place. Metadata is staged, so a broadcast during a track change can
         * carry the new title beside a sheet resolved for the old song — and words are far
         * harder to mistake for a coincidence than a sleeve is. The panel checks that the
         * sheet names the track it is about to hang it on, and shows nothing if it does not.
         *
         * The whole sheet travels, once per track, rather than a line at a time. The panel
         * already carries the position forward on its own clock, so it can pick the line
         * itself and change it exactly on the beat — where a broadcast per line would put
         * every change at the mercy of when the system felt like delivering it.
         */
        const val EXTRA_MEDIA_LYRICS = "mediaLyrics"
        const val EXTRA_MEDIA_LYRICS_FOR = "mediaLyricsFor"

        /**
         * A transport button was pressed on the panel.
         *
         * Travels the other way for the same reason ACTION_OPEN_CAMERA does: the thing
         * that can act on it lives in the main process. See MediaWatcher.command().
         */
        const val ACTION_MEDIA_COMMAND = "dev.kb896.aiusage.MEDIA_COMMAND"
        const val EXTRA_MEDIA_COMMAND = "command"

        /** The commands, defined once. The panel sends these strings and
         *  MediaWatcher switches on them — two copies would be two chances to typo one. */
        const val MEDIA_PLAY_PAUSE = "playpause"
        const val MEDIA_NEXT = "next"
        const val MEDIA_PREVIOUS = "previous"

        /**
         * Seek, as `seek:<milliseconds>`.
         *
         * The one command that carries a value, and it carries it in the string rather
         * than in a second extra. The whole path — panel callback, broadcast, receiver,
         * MediaWatcher.command — is typed to a single String, and widening all of it so
         * that one command could pass a number would put an unused argument in front of
         * every other caller.
         */
        const val MEDIA_SEEK = "seek"

        /**
         * Not a transport command: the panel asking what is playing.
         *
         * :rear is destroyed and recreated constantly — every recovery, every placement,
         * every time HyperOS decides to. A broadcast is not sticky, so a panel that came
         * up after the last metadata change knew nothing about the session and showed
         * the artwork over a playing track until the next one started. It asks instead
         * of being told, because it is the one that knows when it is new.
         */
        const val MEDIA_SYNC = "sync"

        /** Show the charging cell, optionally at a stated level rather than the real one. */
        const val ACTION_CHARGE = "dev.kb896.aiusage.CHARGE"
        const val EXTRA_CHARGE_PERCENT = "percent"

        /**
         * The panel lit itself, so refuse input until somebody proves they meant it.
         *
         * Sent from the main process, which is the only one that can tell. The wake goes
         * out through the privileged service and the display state is read just before
         * the keyevent lands; by the time this process hears about the notification the
         * screen is on either way and the difference has gone. See ShizukuBridge.wake.
         */
        const val ACTION_LOCK = "dev.kb896.aiusage.LOCK"

        /**
         * The panel has let its display go dark on purpose, or has taken it back.
         *
         * Travels to the main process because that is where the two supervisors live, and
         * neither of them can work this out for itself: an asleep panel and a hijacked one
         * composite identically. See IRearService.setDozing.
         */
        const val ACTION_DOZING = "dev.kb896.aiusage.DOZING"
        const val EXTRA_DOZING = "dozing"

        const val EXTRA_STATE = "state"
        private const val TAG = "AiUsageRear"

        /**
         * How long the activity may sit on a non-rear display before evicting itself.
         * Must comfortably exceed the launch→poll→move sequence (RearUserService.show():
         * up to 20 x 50ms of task-id polling plus the moveRootTaskToDisplay binder call),
         * while staying short enough that a user staring at a hijacked main screen is not
         * stuck for long. Never raise this to paper over a placement bug — fix the bug.
         */
        const val STRAND_GRACE_MS = 4_000L

        /**
         * How long the panel waits after letting go of the display before putting itself
         * back to normal.
         *
         * Past MIUI's own power-down, which onCreate measures at about five seconds, so
         * the restore happens behind a dark screen rather than in front of somebody.
         */
        const val SLEEP_FALLBACK_MS = 30_000L
    }

    private lateinit var dial: RearDialView
    private lateinit var calibrationView: CalibrationView
    private var calibrating = false

    /** -1 while on the rear panel; set once, to the elapsed-time clock, on the first
     *  wrong-display detection. See armEvictionTimer(). */
    private var strandedSinceMs = -1L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Geometry nudges ride the same ACTION_STATE broadcast as normal status
            // updates (`--ei cx1 148 ...`, independent of the `state` payload) so a
            // human can dial the rings in live, without relaunching. Apply before
            // dispatching on action.
            when (intent?.action) {
                ACTION_FINISH -> finish()
                // Firing the charging cell by hand. The real trigger is the charger
                // going in, which is awkward to arrange on demand and impossible to
                // arrange at a chosen battery level — see tools/charge.sh.
                ACTION_CHARGE -> dial.showCharging(
                    intent.getIntExtra(EXTRA_CHARGE_PERCENT, -1)
                        .let { if (it in 0..100) it else -1 },
                )
                ACTION_LOCK -> lockPanel()
                ACTION_NOTIFICATION -> dial.showNotification(
                    intent.getStringExtra(EXTRA_NOTIFICATION_APP).orEmpty(),
                    intent.getStringExtra(EXTRA_NOTIFICATION_PKG).orEmpty(),
                    intent.getStringExtra(EXTRA_NOTIFICATION_TITLE).orEmpty(),
                    intent.getStringExtra(EXTRA_NOTIFICATION_TEXT).orEmpty(),
                    intent.getStringExtra(EXTRA_NOTIFICATION_MORE_TITLE).orEmpty(),
                    intent.getStringExtra(EXTRA_NOTIFICATION_MORE_TEXT).orEmpty(),
                )
                ACTION_MEDIA -> dial.showMedia(
                    intent.getBooleanExtra(EXTRA_MEDIA_ACTIVE, false),
                    intent.getStringExtra(EXTRA_MEDIA_TITLE).orEmpty(),
                    intent.getStringExtra(EXTRA_MEDIA_ARTIST).orEmpty(),
                    intent.getBooleanExtra(EXTRA_MEDIA_PLAYING, false),
                    intent.getStringExtra(EXTRA_MEDIA_ART).orEmpty(),
                    intent.getLongExtra(EXTRA_MEDIA_DURATION, 0L),
                    intent.getLongExtra(EXTRA_MEDIA_POSITION, 0L),
                    intent.getLongExtra(EXTRA_MEDIA_POSITION_AT, 0L),
                    intent.getFloatExtra(EXTRA_MEDIA_SPEED, 1f),
                    intent.getStringExtra(EXTRA_MEDIA_LYRICS).orEmpty(),
                    intent.getStringExtra(EXTRA_MEDIA_LYRICS_FOR).orEmpty(),
                )
                // A geometry-only nudge (bare `--ei cx1 ...`, no `state` extra) must
                // leave the current mode untouched. Re-evaluating unconditionally with
                // `.orEmpty()` treated a missing extra the same as an explicit empty
                // payload, isCalibrate("") is false, and a nudge mid-calibration would
                // silently swap back to the ring view and drop FLAG_KEEP_SCREEN_ON —
                // panel goes dark mid-measurement. Only render on a genuine state update.
                ACTION_STATE -> if (intent.hasExtra(EXTRA_STATE)) {
                    render(intent.getStringExtra(EXTRA_STATE).orEmpty())
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        // FLAG_KEEP_SCREEN_ON is applied unconditionally at the user's explicit
        // request: they want the panel present permanently, not following the
        // system timeout. Without it MIUI powers the rear display down within
        // about five seconds.
        //
        // This reverses the spec's original default and makes Task 16's pixel-drift
        // mitigation load-bearing rather than optional — a permanently-lit OLED
        // showing two fixed rings, four fixed bars and a fixed corner mark is close
        // to worst-case burn-in geometry. Do not remove the drift.
        //
        // NO turn-screen-on, and that is a fix rather than an omission. This activity is
        // necessarily created on the MAIN display before being moved to the rear one —
        // the rear display refuses a direct launch from an ordinary app, tested — so a
        // turn-screen-on flag fires on display 0 and wakes the phone. Measured: with the
        // device dozing, a relaunch took mWakefulness from Dozing to Awake. That is the
        // watchdog lighting up a phone in a pocket every time it recovers the panel, and
        // it is why recovery had to be suppressed whenever the screen was off — which in
        // turn is why the panel stayed dead until someone tapped the notification.
        //
        // The rear display is woken explicitly instead, by the privileged service, with
        // `input -d <display> keyevent WAKEUP` after every placement. That targets one
        // display; the flag could not.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        setShowWhenLocked(true)
        // The display is LIVE inside the reported cutout — render across all 904px.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        calibrationView = CalibrationView(this)
        dial = RearDialView(this)
        // Fire-and-forget across the process boundary. This process cannot open the
        // camera itself — the Shizuku binding is in the main one — and it deliberately
        // learns nothing about whether the launch worked: there is no screen here to
        // report a failure on, and the camera either appears or it does not.
        dial.onCameraTap = {
            sendBroadcast(Intent(ACTION_OPEN_CAMERA).setPackage(packageName))
        }
        // Same shape, same reason: the session lives in the main process, so a press
        // here is a message rather than an action. The panel does NOT optimistically
        // flip its own play/pause glyph on the way out — the player answers with a real
        // state change a moment later, and guessing would show a pause button for
        // something that refused to start.
        dial.onMediaTap = { command ->
            sendBroadcast(
                Intent(ACTION_MEDIA_COMMAND)
                    .setPackage(packageName)
                    .putExtra(EXTRA_MEDIA_COMMAND, command),
            )
        }
        dial.onLockExpired = { onLockSpent() }
        // Something worth seeing arrived. Take the display back — it may be part-way
        // through being let go after an earlier lock ran out — and call off the hand-back
        // that release queued, because the lock has just been given a fresh reason.
        dial.onNeedsScreen = {
            window.decorView.removeCallbacks(restorePanel)
            awaitingDoze = false
            holdScreen(true)
        }
        setContentView(dial)
        // See MEDIA_SYNC: this process may have been created long after the media
        // session was, and there is no other way for it to find out.
        sendBroadcast(
            Intent(ACTION_MEDIA_COMMAND)
                .setPackage(packageName)
                .putExtra(EXTRA_MEDIA_COMMAND, MEDIA_SYNC),
        )


        // No-op until we are actually on the rear panel — see hideBars().
        hideBars()

        (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .registerDisplayListener(displayListener, null)

        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(ACTION_STATE); addAction(ACTION_FINISH); addAction(ACTION_NOTIFICATION)
                addAction(ACTION_MEDIA); addAction(ACTION_CHARGE); addAction(ACTION_LOCK)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        render(intent?.getStringExtra(EXTRA_STATE).orEmpty())
    }

    private fun render(payload: String) {
        val wantCalibrate = CalibrateMode.isCalibrate(payload)
        if (wantCalibrate != calibrating) {
            calibrating = wantCalibrate
            setContentView(if (calibrating) calibrationView else dial)
        }
        if (!calibrating) {
            val decoded = StatePayload.decode(payload)
            dial.config = decoded.config
            dial.state = decoded.state
            calibrationView.geometry = PanelGeometry.of(decoded.config.ringLayout)
        }
    }

    /**
     * Arm lock mode, and hold the display up while it lasts.
     *
     * The hold matters because of what put the panel here: MIUI powers the rear display
     * down about five seconds after nothing is holding it, and the lock is armed at the
     * exact moment the display has just been woken from precisely that. Without
     * re-asserting it a lock raised by a notification could go dark before the
     * notification finished arriving.
     */
    private fun lockPanel() {
        // CALL OFF ANY RESTORE ALREADY IN FLIGHT. A lock that expired a moment ago has a
        // hand-back queued for a few seconds' time, and a notification arriving inside
        // that window would otherwise be shown for a second and then have the panel
        // unlock itself underneath it — the restore firing for a lock that has since been
        // renewed.
        window.decorView.removeCallbacks(restorePanel)
        awaitingDoze = false
        holdScreen(true)
        dial.lock()
    }

    /**
     * PUT THE REAR DISPLAY OUT BY LETTING GO OF IT, not by sending it to sleep.
     *
     * A keyevent was the obvious way and it does not work: the panel holds
     * FLAG_KEEP_SCREEN_ON, so KEYCODE_SLEEP aimed at this display is granted and then
     * immediately undone by our own window. Measured — the display returned to
     * "policy=BRIGHT" before the next reading.
     *
     * Releasing the flag needs no privilege and no service, and the mechanism is already
     * documented in onCreate: without it MIUI powers this display down within about five
     * seconds. The thing that was fighting the sleep is the thing that performs it.
     *
     * The flag is NOT re-asserted on a timer. It goes back on when the display is next
     * genuinely lit — see the display listener — because re-adding it while the display
     * is dark is a request for it to be on, which is the opposite of what was asked for.
     */
    private fun holdScreen(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        holding = on
        // Tell the supervisors. They will otherwise recover a panel that is not lost, and
        // recovery lights the display — see IRearService.setDozing.
        sendBroadcast(
            Intent(ACTION_DOZING).setPackage(packageName).putExtra(EXTRA_DOZING, !on),
        )
    }

    private var holding = true

    /**
     * The lock ran out with nobody swiping: let the display go, then put the panel back.
     *
     * The order is the instruction's — sleep first, and the artwork and the player come
     * back after. The restore is delayed past MIUI's own power-down rather than run
     * immediately, so the disc sliding back in and the media page returning happen behind
     * a dark screen. What matters is not that the movement is seen; it is that the panel
     * is not still showing a lock the next time it is.
     *
     * If someone lights the panel deliberately during those few seconds they will see the
     * restore happen, which is correct — the lock has expired, and watching it hand back
     * is better than being shown a slider that is no longer guarding anything.
     */
    private val restorePanel = Runnable { dial.unlock() }

    private fun onLockSpent() {
        holdScreen(false)
        awaitingDoze = true
        window.decorView.removeCallbacks(restorePanel)
        // A FALLBACK, not the plan. The panel hands back when the display is actually seen
        // to go out — see onDisplayState — because how long that takes is MIUI's business
        // and not ours. Timing it was the first attempt and it was wrong by a mile: the
        // hand-back was set for five and a half seconds and the display went dark after
        // eleven, so the lock vanished in front of somebody while the screen was still lit,
        // which is precisely the bug this pair of changes is about. This only exists so a
        // display that never dozes at all cannot leave the panel locked for good.
        window.decorView.postDelayed(restorePanel, SLEEP_FALLBACK_MS)
    }

    /** Waiting for the display to actually go out after the lock let go of it. */
    private var awaitingDoze = false

    /**
     * The rear display changed power state.
     *
     * TWO things hang off this, and they are opposite ends of the same sleep.
     *
     * Going DARK is the signal to hand the panel back: the lock has run out, the display
     * has gone, and the artwork and the player can return behind it where nobody watches
     * them do it. That is the instruction's order — sleep, and then put things back.
     *
     * Coming back LIT is when the screen hold is taken again, and only after the display
     * has genuinely been dark. Re-taking it merely because the display reports itself on
     * was the first version and it stopped the sleep from ever happening: a display on its
     * way down is still on, and every property change on the way down handed the hold
     * straight back.
     */
    private fun onDisplayState(on: Boolean) {
        if (!on) {
            if (!awaitingDoze) return
            awaitingDoze = false
            window.decorView.removeCallbacks(restorePanel)
            window.decorView.post(restorePanel)
            return
        }
        if (!holding && !awaitingDoze) holdScreen(true)
    }

    /**
     * Take the screen hold back the moment the display is genuinely lit again.
     *
     * This is what stops the sleep above from being permanent. The panel is meant to be
     * always-on — the user asked for that explicitly and onCreate says so — and a flag
     * dropped to allow one doze must not leave the panel following the system timeout
     * forever after. Whatever lights the display next, a notification or a thumb, this
     * puts the hold back.
     */
    private val displayListener = object :
        android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != display?.displayId) return
            onDisplayState(display?.state == android.view.Display.STATE_ON)
        }
    }

    /**
     * Immersive mode, but ONLY once we are actually on the rear panel.
     *
     * Defence in depth behind the eviction guard below: system bars are the user's
     * manual way out. If placement fails and the eviction guard also fails or is slow,
     * an activity that has already hidden the bars leaves no escape but adb — which is
     * exactly how the device became unusable once. Hiding chrome on a display we were
     * never meant to occupy buys nothing and costs the only exit.
     */
    private fun hideBars() {
        if (display?.displayId != Panel.DISPLAY_ID) return
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The status bar can reappear after the display move — re-assert.
        if (hasFocus) hideBars()
    }

    /**
     * Self-eviction guard. This activity is fullscreen, immersive and excluded from
     * recents — deliberately, for a panel nobody touches. On the MAIN display those
     * same properties make it a trap with no user-reachable way out, and it is sized
     * for 904x572 so it squats as a small box in the corner.
     *
     * It is legitimately on display 0 briefly by design: the placement technique is
     * launch-on-0-then-move, because the rear panel rejects direct launches. So this
     * allows a grace period for the move to land, then evicts itself.
     *
     * This is not hypothetical — a failed move left the device unusable until it was
     * force-stopped over adb. Never remove this guard, and never raise the grace
     * period to hide a placement bug.
     *
     * The deadline anchors to `strandedSinceMs`, set once on the FIRST wrong-display
     * detection — not to "now" on every call. armEvictionTimer() runs from both
     * onResume() and onConfigurationChanged(), and if a `removeCallbacks` +
     * `postDelayed(STRAND_GRACE_MS)` pair ran on every arm, lifecycle churn faster
     * than the grace period would restart the clock each time and defer eviction
     * indefinitely. That is not contrived — this project's own incident forensics
     * showed exactly that kind of repeated, system-driven resume/config-change churn
     * around the original stranding failure.
     */
    /**
     * Positive identification that we are on the MAIN display, independent of
     * Activity.getDisplay().
     *
     * Every identity-based signal on this device lies in exactly the failing case:
     * getDisplay(), dumpsys window displays and `am stack list` bounds all report
     * display 1 while the panel renders visibly on the FRONT screen. Panel power is
     * no good either — display 1 can be lit for MIUI's own reasons.
     *
     * Density is different because it is not a claim about identity, it is what the
     * layout is actually being measured with: this device runs the main display at
     * 520dpi and the rear panel at 450 (380 after its override). So if our own
     * configuration says 520, we are being laid out for the main display whatever
     * anything else asserts.
     *
     * Deliberately positive: it returns true only when the densities genuinely differ
     * AND ours matches the main display's. An unknown or identical density yields
     * false, so this can never evict a correctly-placed panel.
     */
    private fun onMainDisplayByDensity(): Boolean {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        fun densityOf(id: Int): Int? = dm.getDisplay(id)?.let { d ->
            android.util.DisplayMetrics().also { d.getRealMetrics(it) }.densityDpi
        }
        val main = densityOf(android.view.Display.DEFAULT_DISPLAY) ?: return false
        val rear = densityOf(Panel.DISPLAY_ID) ?: return false
        if (main == rear) return false
        return resources.configuration.densityDpi == main
    }

    /**
     * Strand handling, in three stages: ESCAPE, then MOVE, then evict.
     *
     * This activity is fullscreen, immersive, excluded from recents and sized for the
     * rear panel. On the MAIN display those properties make it a trap with nothing
     * tappable — which is exactly what a user reported after an earlier version of this
     * removed the eviction guard entirely. Recovery-first was the right instinct;
     * leaving no fallback was not.
     *
     * Stage 1, immediately: show the system bars. That happens in armEvictionTimer()
     * the moment a wrong display is seen, not after any delay, because a trapped user
     * needs a way out before anything else is attempted.
     *
     * Stage 2: ask for a move back. Broadcast only — the corrective
     * moveRootTaskToDisplay needs shell privileges this process does not have, so
     * UsagePollService has to do it.
     *
     * Stage 3, if still stranded after STRAND_GRACE_MS: finish. Being trapped behind a
     * fullscreen window is worse than losing the panel, and the panel comes back on its
     * own. The grace period is what stops this firing during normal placement, which is
     * legitimately on display 0 for a moment by design.
     */
    private val evictIfStranded = Runnable {
        if (display?.displayId == Panel.DISPLAY_ID) return@Runnable
        Log.w(TAG, "still on display ${display?.displayId} after the grace period — finishing")
        finish()
    }

    /** Stage 1 + 2. Cheap, and safe to run more than once. */
    private fun escapeAndRequestMove() {
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        sendBroadcast(Intent(ACTION_STRANDED).setPackage(packageName))
    }

    private fun armEvictionTimer() {
        window.decorView.removeCallbacks(evictIfStranded)
        if (display?.displayId == Panel.DISPLAY_ID) {
            strandedSinceMs = -1L
            return
        }
        // Wrong display. Give the user their way out and ask for a move on EVERY
        // arm, not once — a single failed move should not leave things stuck.
        escapeAndRequestMove()
        val now = SystemClock.elapsedRealtime()
        if (strandedSinceMs < 0) strandedSinceMs = now
        val remaining = STRAND_GRACE_MS - (now - strandedSinceMs)
        if (remaining <= 0) {
            evictIfStranded.run()
        } else {
            window.decorView.postDelayed(evictIfStranded, remaining)
        }
    }

    override fun onResume() {
        super.onResume()
        armEvictionTimer()
    }

    // The display move surfaces here, not necessarily as a fresh onResume — the
    // manifest's configChanges list exists precisely to stop it recreating the
    // activity. Re-arm from both, or a move that lands late leaves the timer
    // running against stale information.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideBars()
        armEvictionTimer()
    }

    override fun onPause() {
        super.onPause()
        window.decorView.removeCallbacks(evictIfStranded)
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        window.decorView.removeCallbacks(restorePanel)
        runCatching {
            (getSystemService(Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager)
                .unregisterDisplayListener(displayListener)
        }
        super.onDestroy()
    }
}
