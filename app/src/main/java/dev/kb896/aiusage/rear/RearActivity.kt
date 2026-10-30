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
import android.view.ViewGroup
import android.view.View
import android.view.MotionEvent
import android.view.GestureDetector
import android.graphics.Color
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
         * From the shell service while its portal sheet is up over this panel: the sheet's
         * blocker keeps every touch from the panel, so the sleep countdown is re-armed here
         * instead. Same string as RearUserService.ACTION_PORTAL_POKE.
         */
        const val ACTION_PORTAL_POKE = "dev.kb896.aiusage.PORTAL_POKE"
        /**
         * From the shell (`am broadcast -p`), for the portal sheet's Resume: this task goes
         * behind the guest on the rear, which is then on top without leaving the display.
         * Same string as RearUserService.ACTION_PORTAL_STEP_BACK.
         */
        const val ACTION_PORTAL_STEP_BACK = "dev.kb896.aiusage.PORTAL_STEP_BACK"

        /**
         * The panel's camera button was pressed.
         *
         * Sent from `:rear` to the main process, which is where the Shizuku binding
         * lives — this process has none of its own and should not grow one. An ordinary
         * broadcast is enough: same package, same uid, no privilege crosses here. The
         * privileged part happens on the other side.
         */
        const val ACTION_OPEN_CAMERA = "dev.kb896.aiusage.OPEN_CAMERA"

        /** The camera tip has been shown on the back: its switch goes off. */
        const val ACTION_CAMERA_TIP_SHOWN = "dev.kb896.aiusage.CAMERA_TIP_SHOWN"

        /** Another app's notification, on its way to the panel. */
        const val ACTION_NOTIFICATION = "dev.kb896.aiusage.NOTIFICATION"
        const val EXTRA_NOTIFICATION_APP = "app"
        const val EXTRA_NOTIFICATION_PKG = "pkg"
        const val EXTRA_NOTIFICATION_TITLE = "title"
        const val EXTRA_NOTIFICATION_TEXT = "text"
        const val EXTRA_NOTIFICATION_MORE_TITLE = "moreTitle"
        const val EXTRA_NOTIFICATION_MORE_TEXT = "moreText"
        /** The front's notification key (StatusBarNotification.key), for matching the front. */
        const val EXTRA_NOTIFICATION_KEY = "key"
        /**
         * "Match the front notifications" (PanelConfig.matchFrontNotifications). To the panel:
         * one left the front ([ACTION_NOTIFICATION_REMOVED], with its key), or what is on the
         * front now ([ACTION_NOTIFICATIONS_ACTIVE], every key). To the relay: a card swiped away
         * on the back ([ACTION_DISMISS_NOTIFICATION], with its key), and a request to say what is
         * on the front ([ACTION_NOTIFICATIONS_SYNC]) — the panel's process starting, the switch
         * turned on.
         */
        const val ACTION_NOTIFICATION_REMOVED = "dev.kb896.aiusage.NOTIFICATION_REMOVED"
        const val ACTION_NOTIFICATIONS_ACTIVE = "dev.kb896.aiusage.NOTIFICATIONS_ACTIVE"
        const val EXTRA_NOTIFICATION_KEYS = "keys"
        const val ACTION_DISMISS_NOTIFICATION = "dev.kb896.aiusage.DISMISS_NOTIFICATION"
        const val ACTION_NOTIFICATIONS_SYNC = "dev.kb896.aiusage.NOTIFICATIONS_SYNC"

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
        /**
         * The panel's lock slider (pocket lock) or always-on came up, or went: [EXTRA_GUARDED].
         * The shell's portal sheet opens no sheet over a guarded panel. Same route as
         * [ACTION_DOZING]: the app process forwards it (IRearService.setPanelGuarded).
         */
        const val ACTION_GUARDED = "dev.kb896.aiusage.GUARDED"
        const val EXTRA_GUARDED = "guarded"
        /**
         * The control centre's brightness (ControlCenter): the panel asks for it
         * ([ACTION_GET_REAR_BRIGHTNESS]) and sets it ([ACTION_SET_REAR_BRIGHTNESS], with
         * [EXTRA_BRIGHTNESS] 1 to 255); the app process, which holds Shizuku, answers with
         * [ACTION_REAR_BRIGHTNESS] — -1 when it cannot be read.
         */
        const val ACTION_GET_REAR_BRIGHTNESS = "dev.kb896.aiusage.GET_REAR_BRIGHTNESS"
        const val ACTION_SET_REAR_BRIGHTNESS = "dev.kb896.aiusage.SET_REAR_BRIGHTNESS"
        const val ACTION_REAR_BRIGHTNESS = "dev.kb896.aiusage.REAR_BRIGHTNESS"
        const val EXTRA_BRIGHTNESS = "brightness"
        /** Automatic rear brightness: its state in [ACTION_REAR_BRIGHTNESS], and switching it. */
        const val EXTRA_AUTO_BRIGHTNESS = "autoBrightness"
        const val ACTION_SET_REAR_AUTO_BRIGHTNESS = "dev.kb896.aiusage.SET_REAR_AUTO_BRIGHTNESS"
        /**
         * The control centre's switches — silent, Bluetooth, mobile data, Wi-Fi. Asked for
         * ([ACTION_GET_QUICK]), set ([ACTION_SET_QUICK] with [EXTRA_QUICK] and [EXTRA_QUICK_ON]),
         * and told ([ACTION_QUICK_STATES]: [EXTRA_QUICK_STATES] in that order, and
         * [EXTRA_QUICK_REACHABLE], false when Shizuku cannot be reached for the last three).
         */
        const val ACTION_GET_QUICK = "dev.kb896.aiusage.GET_QUICK"
        const val ACTION_SET_QUICK = "dev.kb896.aiusage.SET_QUICK"
        const val ACTION_QUICK_STATES = "dev.kb896.aiusage.QUICK_STATES"
        const val EXTRA_QUICK = "quick"
        const val EXTRA_QUICK_ON = "quickOn"
        const val EXTRA_QUICK_STATES = "quickStates"
        const val EXTRA_QUICK_REACHABLE = "quickReachable"
        /** What the front screen's preview mirrors — see RearDialView.Mirror. */
        const val ACTION_MIRROR = "dev.kb896.aiusage.MIRROR"
        const val ACTION_MIRROR_SYNC = "dev.kb896.aiusage.MIRROR_SYNC"
        const val EXTRA_MIRROR_PAGE = "page"
        const val EXTRA_MIRROR_AOD = "aod"
        const val EXTRA_MIRROR_LOCKED = "locked"
        const val EXTRA_MIRROR_RINGS = "rings"
        const val EXTRA_MIRROR_TABLE = "table"
        const val EXTRA_MIRROR_CORNER = "corner"
        const val EXTRA_MIRROR_DIM = "dim"

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
         * How long the panel stays lit after the last thing worth looking at.
         *
         * MIUI's own rear-screen timeout is ten seconds — settings system
         * subscreen_display_time — which is right for a screen you glance at and wrong
         * for one you read. Thirty is long enough to take in three accounts and a track
         * name without reaching for it again, and short enough that a panel left face-up
         * on a desk is not costing a core.
         */
        const val AWAKE_MS = 30_000L
        /** how long the rear proximity sensor has to stay covered before always-on lets the display go */
        const val POCKET_MS = 1_500L
        /** table mode: gravity along -z beyond this, x and y within this, for this long */
        const val TABLE_Z = 8.6f
        const val TABLE_XY = 2.2f
        const val TABLE_SETTLE_MS = 1_000L
        /** how long a notification keeps an always-on panel out of its dim, from the last card */
        const val NOTICE_GRACE_MS = 6_000L
        /** how often an always-on peek past its grace looks again for the strip to be done */
        const val PEEK_RECHECK_MS = 250L

        /**
         * The shortest gap between two requests to be moved back to the panel.
         *
         * A second is long against a looper and short against a person: a move that was
         * going to work has finished well inside it, and one that was not is no more
         * likely to work for being asked again immediately. See escapeAndRequestMove for
         * what the unbounded version does to system_server.
         */
        const val ESCAPE_FLOOR_MS = 1_000L

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

    /** Between onStart and onStop — stopped behind a guest, the sleep timer stands down. */
    private var started = false

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
                // Also the guard said again: the app process asking may be a new one, which
                // starts unguarded (ShizukuBridge) and would otherwise never hear it — a
                // pocket-locked panel would then open the shell's sheet. Said at once from the
                // last mirror, since a dozing panel may draw no frame to publish a new one.
                ACTION_MIRROR_SYNC -> {
                    guardSent = null
                    lastMirror?.let { announceGuard(it.aod || it.locked) }
                    dial.republishMirror()
                }
                // Somebody is using the portal sheet over the panel: not a panel to doze.
                ACTION_PORTAL_POKE -> if (!inAod) armSleep()
                // Never from a guarded panel (lock slider, pocket lock, always-on): stepping
                // back would hand the rear to the guest past the guard. No sheet opens over a
                // guarded panel, so this is only a guard come up between Resume and here.
                ACTION_PORTAL_STEP_BACK -> if (lastMirror?.let { it.locked || it.aod } == true || inAod) {
                    Log.i(TAG, "portal: step back refused — the panel is guarded")
                } else {
                    moveTaskToBack(true)
                }
                ACTION_LOCK -> lockPanel()
                ACTION_NOTIFICATION -> { peekForNotice(); dial.showNotification(
                    intent.getStringExtra(EXTRA_NOTIFICATION_APP).orEmpty(),
                    intent.getStringExtra(EXTRA_NOTIFICATION_PKG).orEmpty(),
                    intent.getStringExtra(EXTRA_NOTIFICATION_TITLE).orEmpty(),
                    intent.getStringExtra(EXTRA_NOTIFICATION_TEXT).orEmpty(),
                    intent.getStringExtra(EXTRA_NOTIFICATION_MORE_TITLE).orEmpty(),
                    intent.getStringExtra(EXTRA_NOTIFICATION_MORE_TEXT).orEmpty(),
                    intent.getStringExtra(EXTRA_NOTIFICATION_KEY).orEmpty(),
                ) }
                ACTION_REAR_BRIGHTNESS -> dial.rearBrightness(
                    intent.getIntExtra(EXTRA_BRIGHTNESS, -1),
                    intent.getBooleanExtra(EXTRA_AUTO_BRIGHTNESS, false),
                )
                ACTION_QUICK_STATES -> dial.quickStates(
                    intent.getBooleanArrayExtra(EXTRA_QUICK_STATES) ?: BooleanArray(4),
                    intent.getBooleanExtra(EXTRA_QUICK_REACHABLE, false),
                )
                ACTION_NOTIFICATION_REMOVED -> dial.removeNotice(intent.getStringExtra(EXTRA_NOTIFICATION_KEY).orEmpty())
                ACTION_NOTIFICATIONS_ACTIVE -> dial.keepNotices(
                    intent.getStringArrayExtra(EXTRA_NOTIFICATION_KEYS)?.toSet() ?: emptySet(),
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
        takeOverDoubleTap()

        calibrationView = CalibrationView(this)
        dial = RearDialView(this)
        dial.onMirror = { m -> lastMirror = m; sendMirror(); announceGuard(m.aod || m.locked) }

        // Fire-and-forget across the process boundary. This process cannot open the
        // camera itself — the Shizuku binding is in the main one — and it deliberately
        // learns nothing about whether the launch worked: there is no screen here to
        // report a failure on, and the camera either appears or it does not.
        dial.onCameraTap = {
            if (dial.config.cameraTip) {
                // The first camera: the tip first, and the camera once it is swiped through.
                // Shown is seen: the switch goes off now, in the main process that keeps
                // the settings and in this panel's own copy, so a second press cannot
                // repeat it — whether or not it is swiped through.
                dial.config = dial.config.copy(cameraTip = false)
                sendBroadcast(Intent(ACTION_CAMERA_TIP_SHOWN).setPackage(packageName))
                dial.showCameraTip {
                    sendBroadcast(Intent(ACTION_OPEN_CAMERA).setPackage(packageName))
                }
            } else {
                sendBroadcast(Intent(ACTION_OPEN_CAMERA).setPackage(packageName))
            }
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
        // The control centre: opening asks for the brightness, the cell sets it.
        dial.onControlCenterOpening = {
            sendBroadcast(Intent(ACTION_GET_REAR_BRIGHTNESS).setPackage(packageName))
            sendBroadcast(Intent(ACTION_GET_QUICK).setPackage(packageName))
        }
        dial.onQuick = { which, on ->
            sendBroadcast(Intent(ACTION_SET_QUICK).setPackage(packageName).putExtra(EXTRA_QUICK, which).putExtra(EXTRA_QUICK_ON, on))
        }
        dial.onBrightness = { value ->
            sendBroadcast(Intent(ACTION_SET_REAR_BRIGHTNESS).setPackage(packageName).putExtra(EXTRA_BRIGHTNESS, value))
        }
        dial.onAutoBrightness = { on ->
            sendBroadcast(Intent(ACTION_SET_REAR_AUTO_BRIGHTNESS).setPackage(packageName).putExtra(EXTRA_AUTO_BRIGHTNESS, on))
        }
        // A card swiped away here: the relay clears it on the front if matching is on.
        dial.onNoticeDismissed = { key ->
            sendBroadcast(Intent(ACTION_DISMISS_NOTIFICATION).setPackage(packageName).putExtra(EXTRA_NOTIFICATION_KEY, key))
        }
        dial.onUnlocked = {
            guardedAfterWake = false
            // A swipe ends the double tap's guard on later wakes; a spent lock's hand-back does not.
            sleepStage.unlocked(bySwipe = !unlockingBySpent)
            if (inAod && !unlockingBySpent) exitAod()
            // Swiped while the spent lock was letting the display go (the hand-back clears
            // awaitingDoze before it unlocks, so this is a finger): somebody is looking at
            // it — keep it, and call the hand-back off.
            if (awaitingDoze) {
                window.decorView.removeCallbacks(restorePanel)
                awaitingDoze = false
                holdScreen(true)
                armSleep()
            }
        }
        // Something worth seeing arrived. Take the display back — it may be part-way
        // through being let go after an earlier lock ran out — and call off the hand-back
        // that release queued, because the lock has just been given a fresh reason.
        dial.onNeedsScreen = {
            window.decorView.removeCallbacks(restorePanel)
            awaitingDoze = false
            holdScreen(true)
            armSleep()
        }
        // Somebody is looking at it. Same effect, different reason — see onInteraction. And
        // a touch after the idle let go takes the hold back (TouchHold): re-arming alone left
        // the display to go dark ten seconds after the touch, on HyperOS's own timeout.
        dial.onInteraction = {
            if (TouchHold.retake(
                    holding = holding,
                    lit = display?.state == android.view.Display.STATE_ON,
                    dimming = sleepStage.dimming,
                    lettingGo = awaitingDoze,
                    sleptByDoubleTap = sleptByDoubleTap,
                    inAod = inAod,
                )
            ) holdScreen(true)
            armSleep()
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
                addAction(ACTION_MIRROR_SYNC); addAction(ACTION_PORTAL_POKE); addAction(ACTION_PORTAL_STEP_BACK)
                addAction(ACTION_NOTIFICATION_REMOVED); addAction(ACTION_NOTIFICATIONS_ACTIVE)
                addAction(ACTION_REAR_BRIGHTNESS)
                addAction(ACTION_QUICK_STATES)

            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        render(intent?.getStringExtra(EXTRA_STATE).orEmpty())
        // This process may be new, and the front may have changed while it was gone: ask
        // the relay what is there now (it answers only when matching is on).
        sendBroadcast(Intent(ACTION_NOTIFICATIONS_SYNC).setPackage(packageName))
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
            dial.weather = decoded.weather
            // The switch may have just been thrown either way: on has to take the hold
            // back immediately, off has to start a countdown that was never running.
            // The table lights' switch, thrown either way: stop watching, or start again if the
            // panel is up (tableWatch checks the switch itself).
            if (!decoded.config.showTableLights) tableWatch(false)
            else if (!tableWatching && panelResumed) tableWatch(true)
            if (keepAwake != decoded.config.keepPanelAwake) {
                keepAwake = decoded.config.keepPanelAwake
                armSleep()
            }
            if (aodOn != decoded.config.alwaysOn) {
                aodOn = decoded.config.alwaysOn
                // Switched off under a panel in always-on: the slider is always-on's, and goes
                // with it. Left up, it ran out a second and a half later as a spent lock, let
                // go of the display and the back went dark — keep awake or not.
                if (!aodOn && inAod) { exitAod(); dial.unlock() }
                armSleep()
            }
            if (!decoded.config.aodPocket) pocketWatch(false) else if (inAod) pocketWatch(true)
            calibrationView.geometry = PanelGeometry.of(decoded.config.effectiveRingLayout)
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
    /**
     * THE DOUBLE TAP, done the way Xiaomi's own rear screen does it.
     *
     * By default HyperOS itself handles a double tap on the rear display and switches it
     * off on the spot — before the panel hears anything, which is why it went straight to
     * black. The official app opts its window out of that gesture with a MIUI window flag
     * (read by name at runtime, as the official app does — it is not in the public SDK)
     * and handles the double tap itself: dim first, off after a few seconds, a touch in
     * between calling it off. So does the panel now.
     *
     * If the flag cannot be set — another ROM, a renamed field — the system keeps the
     * gesture and the dim simply never gets a chance; nothing else changes.
     */
    private val sleepStage = DoubleTapSleep()
    private var veil: View? = null
    /**
     * Put to sleep by the double tap, and not yet woken by anything real. While this is
     * set, requests to hold the screen are refused: the display group is still awake after
     * our power-off, so a hold alone would light the display straight back up — which a
     * media update did within 50 ms of the first successful dim. A real wake (the
     * system's double-tap-to-wake, a notification's wake, the charger) lights the display,
     * onDisplayState(true) clears this, and holds work again.
     */
    private var sleptByDoubleTap = false
    private var systemDoubleTapTakenOver = false

    private val doubleTaps by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Two quick presses of a media button, or anywhere in the open control
                // centre, are the controls', not a sleep.
                if (dial.onMediaControl(e.x, e.y) || dial.controlCenterUp) return false
                if (aodOn) {
                    // always-on: the full panel drops into it; a dimmed page raises the slider
                    if (!inAod) enterAod() else if (!dial.isLocked) dial.lock()
                    return false
                }
                // a second double tap during the dim calls it off; a single touch does not,
                // so a dimming panel is not brought back by a brush of the finger
                if (sleepStage.dimming) endDim(off = false) else beginDim()
                return false // observed, never consumed: the panel's own controls still get the tap
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        dial.throughCrt(ev) // first: everything below reads the flat picture's coordinates
        dial.toPanel(ev) // then real pixels into panel units — the Pro Max's display is larger
        // The camera tip takes nothing but its slider — not even the double tap — and
        // nothing at all before the slider has come.
        val tip = dial.cameraTipUp
        if (tip) dial.onInteraction?.invoke()
        if (systemDoubleTapTakenOver && !tip) doubleTaps.onTouchEvent(ev)
        // A dimmed page — the double tap's dim, or always-on — takes no touch but the
        // double tap: no paging, no buttons, no drag. The one exception is the slider,
        // which has to be swiped: a touch that starts on its band goes through, and so
        // does the rest of that same gesture wherever the finger wanders.
        if (sleepStage.dimming) return true
        if (inAod || guardedAfterWake || tip) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> sliderTouch = dial.isLocked && dial.lockBand().contains(ev.x, ev.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { val pass = sliderTouch; sliderTouch = false; return if (pass) super.dispatchTouchEvent(ev) else true }
            }
            if (!sliderTouch) return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun takeOverDoubleTap() {
        // The flag and MIUI's extraFlags are hidden members: filtered from ordinary
        // reflection since Android 9, visible to the official app only because it is a
        // system app. HiddenApiBypass lifts the filter for this process. (Its 4.3 crashed
        // natively on this Android release; 6.x is the one built for it.)
        runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("") }
            .onFailure { Log.w(TAG, "hidden-api exemption failed: ${it.message}") }
        val lp = WindowManager.LayoutParams::class.java
        val statics = runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.getStaticFields(lp).filterIsInstance<java.lang.reflect.Field>() }.getOrDefault(emptyList())
        val related = statics.filter { it.name.contains("SUBSCREEN") || it.name.contains("DOUBLE_TAP") }.map { it.name }
        Log.i(TAG, "double tap: hidden statics on LayoutParams about it: $related")
        val flag = statics.firstOrNull { it.name == "MIUI_FLAG_DISABLE_SUBSCREEN_DOUBLE_TAP" }
            ?.let { f -> runCatching { f.isAccessible = true; f.getInt(null) }.getOrNull() }
        if (flag == null) {
            Log.i(TAG, "no MIUI_FLAG_DISABLE_SUBSCREEN_DOUBLE_TAP on this ROM — the system keeps the double tap")
            return
        }
        // What the gesture actually consults is the window's MIUI *input* flags
        // (WindowState.getInputMiuiFlags() & 0x100 in MiuiSubscreenDoubleTapGesture), and
        // the official app reads and writes those through a hidden `miuiFlags` field on
        // the layout params. setExtraFlags, the other MIUI channel, did not reach it.
        val attrs = window.attributes
        val fields = runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.getInstanceFields(lp).filterIsInstance<java.lang.reflect.Field>() }.getOrDefault(emptyList())
        Log.i(TAG, "double tap: hidden flag fields on LayoutParams: ${fields.map { it.name }.filter { it.contains("lag") }}")
        val viaMiuiFlags = fields.firstOrNull { it.name == "miuiFlags" }?.let { f ->
            runCatching {
                f.isAccessible = true
                f.setInt(attrs, f.getInt(attrs) or flag)
                window.attributes = attrs
                true
            }.onFailure { Log.w(TAG, "miuiFlags failed: ${it.message}") }.getOrDefault(false)
        } ?: false
        val viaMethod = runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(android.view.Window::class.java, window, "setExtraFlags", flag, flag)
            true
        }.getOrDefault(false)
        systemDoubleTapTakenOver = viaMiuiFlags || viaMethod
        Log.i(TAG, "double tap: flag=0x${flag.toString(16)} miuiFlags=${viaMiuiFlags} setExtraFlags=${viaMethod}")
    }

    private fun beginDim() { beginDimInner(); sendMirror() }
    private fun beginDimInner() {
        if (!sleepStage.doubleTap(SystemClock.uptimeMillis())) return
        window.decorView.removeCallbacks(sleepPanel)
        // Let go of the display NOW, not when the dim ends. Releasing the hold makes the
        // window manager drop a wakelock flagged ON_AFTER_RELEASE, which the power manager
        // counts as fresh user activity; released 40 ms before the power-off, that poke
        // landed after it and lit the display straight back up (seen 2026-09-17). Released
        // here, it is spent during the dim, and the off 7.5 s later has nothing to fight.
        // The dim itself is a view and needs no hold to keep applying.
        if (holding) holdScreen(false)
        dial.ringsClosed = true
        dial.videoPaused = true // the video stops on its frame while the panel dims
        // A veil only, never the backlight: a fixed brightness override is BRIGHTER than a
        // panel auto-brightness has turned right down (0.045 over 0.019, seen 2026-10-30).
        val v = veil ?: View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = DoubleTapSleep.VEIL_ALPHA
            isClickable = false
        }.also { veil = it }
        if (v.parent == null) addContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Log.i(TAG, "double tap: dimmed and let go — HyperOS powers the display down on its rear timeout")
    }

    /**
     * End the dim: either the display has gone off under it ([off]), and the panel tidies
     * up behind a dark screen, or a second double tap called it off and the panel is back
     * at full brightness with its ordinary sleep timer running.
     */
    private fun endDim(off: Boolean) { endDimInner(off); sendMirror() }
    private fun endDimInner(off: Boolean) {
        sleepStage.reset()
        veil?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        if (off) {
            Log.i(TAG, "double tap: the display went off — dim over")
            sleptByDoubleTap = true
            sleepStage.wentOff()
            // Full brightness again only once the display is dark, so the next wake is
            // not a frame of the dim.
            window.decorView.postDelayed(restoreBrightness, 1_500)
        } else {
            restoreBrightness.run()
            dial.ringsClosed = false
            dial.videoPaused = false // called off: it starts over from that frame
            holdScreen(true)
            armSleep()
        }
    }

    private val restoreBrightness = Runnable {
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    private fun holdScreen(on: Boolean) {
        // No holds while the double tap's dim runs either: the double tap's own second
        // touch reaches the view after the dim begins, and the view's touch handling
        // re-took the hold a millisecond after it was released — so the flag never
        // stayed cleared and the display never let go (seen in the wakelock history).
        if (on && (sleptByDoubleTap || sleepStage.dimming)) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        holding = on
        // Tell the supervisors. They will otherwise recover a panel that is not lost, and
        // recovery lights the display — see IRearService.setDozing.
        announceHold()
    }

    /** Tells the app process whether the panel holds the display. */
    private fun announceHold() {
        sendBroadcast(
            Intent(ACTION_DOZING).setPackage(packageName)
                .putExtra(EXTRA_DOZING, !holding),
        )
    }

    private var holding = true

    /** Last guard sent, null before the first: each instance says it once, then on changes. */
    private var guardSent: Boolean? = null

    /**
     * Tells the app process (and through it the shell) whether the panel is guarded — the lock
     * slider up, which is also the pocket lock and always-on's resting state. Rides on the
     * mirror, which the view publishes whenever either changes.
     */
    private fun announceGuard(guarded: Boolean) {
        if (guardSent == guarded) return
        guardSent = guarded
        sendBroadcast(Intent(ACTION_GUARDED).setPackage(packageName).putExtra(EXTRA_GUARDED, guarded))
    }

    private var lastMirror: RearDialView.Mirror? = null
    /** The back's state for the front screen's preview; the dim rides along, it being the window's, not the view's. */
    private fun sendMirror() {
        val m = lastMirror ?: return
        sendBroadcast(
            Intent(ACTION_MIRROR).setPackage(packageName)
                .putExtra(EXTRA_MIRROR_PAGE, m.page).putExtra(EXTRA_MIRROR_AOD, m.aod)
                .putExtra(EXTRA_MIRROR_LOCKED, m.locked).putExtra(EXTRA_MIRROR_RINGS, m.ringsClosed)
                .putExtra(EXTRA_MIRROR_TABLE, m.tableGlow).putExtra(EXTRA_MIRROR_CORNER, m.corner)
                .putExtra(EXTRA_MIRROR_DIM, sleepStage.dimming),
        )
    }


    /** The user's answer to whether the panel should ever sleep. See PanelConfig. */
    private var keepAwake = false

    /**
     * ALWAYS-ON (config.alwaysOn). The resting state is not dark but a dim, frozen page:
     * the display stays held, the window dims to the double tap's level, the view stops
     * animating and fades its backdrop away. A double tap on it raises the unlock slider
     * at once; the swipe brings the full panel back and the idle timer starts again. It
     * sleeps only in a pocket: the rear proximity sensor covered for a moment lets the
     * display go, and HyperOS powers it down on its own timeout. Any wake lands back in
     * always-on, with a notification's card and slider on top of it as usual.
     */
    private var aodOn = false
    private var inAod = false
    /** The spent lock's hand-back (restorePanel) is unlocking, not a finger on the slider. */
    private var unlockingBySpent = false
    private var sliderTouch = false

    /** [sleepPanel] re-arming itself needs a name for itself. */
    private val sleepPanelRef: Runnable get() = sleepPanel

    private fun enterAod() {
        if (inAod) return
        // The camera tip is never dimmed into always-on, whatever asks.
        if (dial.cameraTipUp) return
        inAod = true
        window.decorView.removeCallbacks(sleepPanel)
        if (!holding) holdScreen(true)
        dial.aod = true
        dial.ringsClosed = true
        // the slider is up from the first moment, and stays: the page dims around it
        dial.lock()
        pocketWatch(true)
        Log.i(TAG, "always-on: dimmed and frozen, the slider up")
    }

    private fun exitAod() {
        if (!inAod) return
        inAod = false
        pocketWatch(false)
        window.decorView.removeCallbacks(endPeek)
        dial.aodPeek = false
        dial.aod = false
        dial.ringsClosed = false
        restoreBrightness.run()
        if (!holding) holdScreen(true)
        armSleep()
        Log.i(TAG, "always-on: unlocked — the full panel is back")
    }

    // ---- the table: gravity says the phone lies flat on its face, rear display up ----
    private val gravitySensor: android.hardware.Sensor? by lazy {
        sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
            ?: sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
    }
    private var tableWatching = false
    private var flatSince = -1L
    private val tableListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
            // face down and level: gravity along -z, nothing much along x or y
            val flat = z < -TABLE_Z && kotlin.math.abs(x) < TABLE_XY && kotlin.math.abs(y) < TABLE_XY
            val now = SystemClock.uptimeMillis()
            if (flat) {
                if (flatSince < 0) flatSince = now
                if (!dial.tableGlow && now - flatSince >= TABLE_SETTLE_MS) { dial.tableGlow = true; Log.i(TAG, "table mode: flat on its face — the margins glow") }
            } else {
                flatSince = -1L
                if (dial.tableGlow) dial.tableGlow = false
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
    }
    private fun tableWatch(asked: Boolean) {
        // Only while the table lights are wanted at all — their switch is off, nothing watches.
        val on = asked && dial.config.showTableLights
        if (on == tableWatching) return
        val sensor = gravitySensor ?: return
        tableWatching = on
        if (on) sensors.registerListener(tableListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        else { sensors.unregisterListener(tableListener); flatSince = -1L; dial.tableGlow = false }
    }

    // ---- the pocket: the rear proximity sensor, or the best sensor the phone has ----
    private val sensors by lazy { getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager }
    /**
     * The proximity sensor on the BACK of the phone, and only that one. Xiaomi names it
     * "xiaomi.sensor.proxmity_back" (sic) on the 17 Pro; matched loosely in case another
     * model spells it differently. Never the front sensor: falling back to it made the
     * panel read "in a pocket" whenever the phone was held or lay on its face — and let
     * the display go, which is what a panel going black ten seconds after a wake looked
     * like on phones where the name did not match.
     */
    private val pocketSensor: android.hardware.Sensor? by lazy {
        val all = sensors.getSensorList(android.hardware.Sensor.TYPE_ALL)
        val rear = Regex("(?i)prox.*(back|rear|sub)|(back|rear|sub).*prox")
        all.firstOrNull { it.stringType == "xiaomi.sensor.proxmity_back" }
            ?: all.firstOrNull { rear.containsMatchIn(it.stringType) || rear.containsMatchIn(it.name) }
    }
    private var pocketWatching = false
    private val pocketSleep: Runnable = Runnable {
        if (!inAod) return@Runnable
        Log.i(TAG, "always-on: in a pocket — letting the display go")
        pocketWatch(false)
        holdScreen(false)
    }

    /**
     * A notification in always-on lifts the dim: the panel lights up fully with the card,
     * the slider still up and the page still guarded, and dims again once the last card
     * has had its time — each new card starts that clock over, the lock's own renewal.
     *
     * "Had its time" is the strip's, not a clock's: the grace is only the least of it. A
     * burst plays its queued cards one after another for longer than that, and ending the
     * peek on the grace froze the page with the strip mid-card until the minute tick. So it
     * ends once the strip has retracted with nothing queued (RearDialView.noticesIdle),
     * looked at again every [PEEK_RECHECK_MS] — or at once on a display that is not lit,
     * where the strip draws no frames to get anywhere and nobody would see it.
     */
    private val endPeek: Runnable = Runnable {
        if (!dial.noticesIdle && display?.state == android.view.Display.STATE_ON) {
            window.decorView.postDelayed(endPeekRef, PEEK_RECHECK_MS)
            return@Runnable
        }
        dial.aodPeek = false
    }

    /** [endPeek] re-arming itself needs a name for itself. */
    private val endPeekRef: Runnable get() = endPeek
    private fun peekForNotice() {
        if (!inAod) return
        dial.aodPeek = true
        window.decorView.removeCallbacks(endPeek)
        window.decorView.postDelayed(endPeek, NOTICE_GRACE_MS)
    }

    /**
     * Woken from the double tap's sleep — by the double tap, a notification, the charger.
     * The slider is up and the page takes no touch but the slider's until it is swiped;
     * the lock running out puts the panel back to sleep, as it always did.
     */
    private var guardedAfterWake = false
    private val pocketListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val covered = e.values[0] < e.sensor.maximumRange.coerceAtLeast(1f) * 0.5f
            window.decorView.removeCallbacks(pocketSleep)
            if (covered) window.decorView.postDelayed(pocketSleep, POCKET_MS)
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
    }
    private fun pocketWatch(on: Boolean) {
        // A switch, off by default: a sensor that reads covered in the hand makes this
        // the panel going black for no reason anyone can see.
        if (on && !dial.config.aodPocket) { pocketWatch(false); return }
        if (on == pocketWatching) return
        val sensor = pocketSensor
        if (sensor == null) { Log.w(TAG, "always-on: no rear proximity sensor — no pocket mode"); return }
        if (on) Log.i(TAG, "pocket mode: watching ${sensor.name} (${sensor.stringType}), range ${sensor.maximumRange}")
        pocketWatching = on
        if (on) sensors.registerListener(pocketListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        else { sensors.unregisterListener(pocketListener); window.decorView.removeCallbacks(pocketSleep) }
    }

    /**
     * Let the display go, thirty seconds after the last reason to hold it.
     *
     * THE HOLD IS THE EXPENSIVE THING, not the drawing — or rather, the hold is what
     * makes the drawing happen at all. Lit, the panel costs about 110% of one core with
     * media playing, measured over twelve minutes. Released, MIUI dozes the rear screen
     * and the panel stops drawing into it entirely (see RearDialView.displayAwake), which
     * takes that to nothing while leaving the last frame on the glass.
     *
     * Re-armed rather than one-shot: every reason to be awake — a touch, a notification,
     * the display being lit by anything at all — pushes it back another thirty seconds,
     * so the panel sleeps thirty seconds after you stop caring about it rather than
     * thirty seconds after it started.
     */
    private val sleepPanel = Runnable {
        if (keepAwake) return@Runnable
        // Stopped behind a guest app: letting go would tell the app process the panel is
        // dozing, which pauses the shell service's guest checks while the guest is in plain
        // view. Stopped with the rear still lit is that — something is in front of the panel
        // — and only then: stopped because the rear went dark, the panel must still say so.
        // onStart arms the countdown again.
        if (!started && display?.state == android.view.Display.STATE_ON) return@Runnable
        // Never under the camera tip: it waits for a swipe, and gives up on its own. The
        // countdown starts over instead, so the panel sleeps once the tip has gone.
        if (dial.cameraTipUp) { window.decorView.postDelayed(sleepPanelRef, AWAKE_MS); return@Runnable }
        if (aodOn) enterAod() else holdScreen(false)
    }

    /**
     * Start the countdown again, or cancel it outright if the panel is meant to stay up.
     *
     * Safe to call as often as anything likes; it removes its own callback first.
     */
    private fun armSleep() {
        window.decorView.removeCallbacks(sleepPanel)
        if (keepAwake) {
            // Not while a spent lock is letting the display go — see KeepAwakeHold.
            if (KeepAwakeHold.retake(keepAwake, holding, awaitingDoze)) holdScreen(true)
            return
        }
        window.decorView.postDelayed(sleepPanel, AWAKE_MS)
    }

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
    private val restorePanel = Runnable {
        awaitingDoze = false
        // Not a swipe: a panel put to sleep by the double tap stays guarded on its next wake.
        unlockingBySpent = true
        try { dial.unlock() } finally { unlockingBySpent = false }
    }

    private fun onLockSpent() {
        guardedAfterWake = false
        if (inAod) {
            // nobody swiped: in always-on the slider never leaves — re-arm it
            dial.lock()
            return
        }
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
    /**
     * Whether a display in this state will actually apply what is drawn to it.
     *
     * The suspended states are the point of this. MIUI parks the rear panel in
     * DOZE_SUSPEND, which keeps the last frame lit and drops everything after it — so a
     * panel that carries on animating into one is doing a hundred and twenty frames a
     * second of work that ends nowhere. Measured at about seventy per cent of a core.
     *
     * DOZE itself is not in the list. A dozing display still takes updates, and it is
     * what a genuine always-on mode looks like; refusing to draw to it would freeze the
     * clock on a panel somebody is reading.
     *
     * An unknown state counts as awake. Being wrong that way costs power; being wrong
     * the other way costs a panel that never draws again.
     */
    private fun takesUpdates(state: Int?): Boolean = when (state) {
        android.view.Display.STATE_OFF,
        android.view.Display.STATE_DOZE_SUSPEND,
        android.view.Display.STATE_ON_SUSPEND,
        -> false
        else -> true
    }

    private fun onDisplayState(on: Boolean) {
        if (!on) {
            pocketWatch(false)
            tableWatch(false)
            if (sleepStage.dimming) endDim(off = true)
            if (!awaitingDoze) return
            awaitingDoze = false
            window.decorView.removeCallbacks(restorePanel)
            window.decorView.post(restorePanel)
            return
        }
        if (sleepStage.dimming) endDim(off = false)
        // Lit again after the dim's sleep: no longer paused. The display coming on is what
        // starts the video over (RearDialView.displayAwake), with its frame faded from.
        dial.videoPaused = false
        tableWatch(true)
        // Every wake after the double tap's sleep, not only the first: a lock that ran out
        // unswiped hands back behind the dark screen, and the tap-to-wake in a pocket after
        // it lit the full panel (DoubleTapSleep.guardsWake). Only a swipe ends it.
        val wokeFromSleep = sleptByDoubleTap || sleepStage.guardsWake
        sleptByDoubleTap = false // lit by something real: holds mean something again
        if (!aodOn) dial.ringsClosed = false // awake after the double tap's sleep: the rings open
        if (!aodOn && wokeFromSleep) {
            // the slider up, the page guarded, until the swipe — see guardedAfterWake
            guardedAfterWake = true
            dial.lock()
        }
        if (!holding && !awaitingDoze) holdScreen(true)
        if (aodOn) { if (inAod) { pocketWatch(true); if (!holding) holdScreen(true) } else enterAod() }
        // Lit again, for whatever reason. Start the clock over rather than letting a
        // stale countdown put it straight back to sleep.
        armSleep()
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
    private val litEdge = LitEdge()

    /**
     * Tell [litEdge] what the rear display already is, the first time the panel finds
     * itself on it (onResume, or the move's configuration change). Before this a freshly
     * created panel took its first display change for a wake — the refresh rate dropping
     * three seconds after a touch called off the double tap's dim, or put a panel in use
     * into always-on. Not on display 0: that is the placement's stop on the way, and its
     * state says nothing about the rear. LitEdge takes only the first seed, so the resumes
     * after it cannot swallow an edge.
     */
    private fun seedLitEdge() {
        val d = display ?: return
        if (d.displayId != Panel.DISPLAY_ID) return
        litEdge.seed(d.state == android.view.Display.STATE_ON)
    }

    private val displayListener = object :
        android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != display?.displayId) return
            val state = display?.state
            // Only on an edge: a lit display changing its refresh rate is not one (LitEdge).
            // And only the rear's: display 0's changes during the placement would be taken
            // for the first report, and the seed after the move would then count for nothing.
            if (displayId == Panel.DISPLAY_ID) {
                litEdge.next(state == android.view.Display.STATE_ON)?.let(::onDisplayState)
            }
            // Separate from the question above, which is about the LOCK handing back.
            // This one is about whether drawing reaches anything at all.
            dial.displayAwake = takesUpdates(state)
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
    /** When the last move was actually asked for. See the floor in [escapeAndRequestMove]. */
    private var lastEscapeMs = 0L

    /**
     * Show the way out, and ask to be moved back — but not without limit.
     *
     * THE BROADCAST COSTS A WINDOW TRANSITION. It ends in a request to place this
     * activity on the rear display, and system_server builds a Transition for every one
     * of those. This is armed from onResume AND onConfigurationChanged, and a display
     * move is itself a configuration change — so a move that does not take can arm the
     * next request that does not take either, as fast as the looper will carry it.
     *
     * That is not a hypothetical. system_server on this phone aborts with
     *
     *     JNI ERROR (app bug): global reference table overflow (max=51200)
     *
     * and the table is 39,814 instances of com.android.server.wm.Transition$Token — it
     * never releases them, so anything that asks for transitions in a loop eventually
     * takes the whole system down with it, and MIUI restarts. Roughly 25,000 tokens
     * accumulated in the two minutes between two of those crashes.
     *
     * Not releasing its own tokens is HyperOS's bug and cannot be fixed from here. Asking
     * for them faster than any display can answer is ours. Showing the system bars stays
     * unthrottled — it is local, idempotent, and it is the user's way out.
     */
    private fun escapeAndRequestMove() {
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        val now = SystemClock.elapsedRealtime()
        if (now - lastEscapeMs < ESCAPE_FLOOR_MS) return
        lastEscapeMs = now
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

    /** Between onResume and onPause — when the table lights may be watched for. */
    private var panelResumed = false

    override fun onResume() {
        super.onResume()
        panelResumed = true
        tableWatch(true)
        armEvictionTimer()
        seedLitEdge()
        // Asked rather than assumed. The listener only fires on CHANGES, so a panel that
        // resumed into an already-suspended display would never be told.
        dial.displayAwake = takesUpdates(display?.state)
        if (!dial.displayAwake) Log.i(TAG, "resumed onto a display in state ${display?.state} — the panel stops drawing until it changes")
        armSleep()
    }

    // The display move surfaces here, not necessarily as a fresh onResume — the
    // manifest's configChanges list exists precisely to stop it recreating the
    // activity. Re-arm from both, or a move that lands late leaves the timer
    // running against stale information.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideBars()
        armEvictionTimer()
        seedLitEdge()
    }

    override fun onPause() {
        super.onPause()
        panelResumed = false
        window.decorView.removeCallbacks(evictIfStranded)
    }

    // Stopped behind a guest app, the sleep timer stands down (see sleepPanel); it starts
    // over on return.
    override fun onStart() {
        super.onStart()
        started = true
        armSleep()
    }

    override fun onStop() {
        super.onStop()
        started = false
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        window.decorView.removeCallbacks(restorePanel)
        window.decorView.removeCallbacks(restoreBrightness)
        window.decorView.removeCallbacks(sleepPanel)
        window.decorView.removeCallbacks(endPeek)
        pocketWatch(false)
        tableWatch(false)
        runCatching {
            (getSystemService(Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager)
                .unregisterDisplayListener(displayListener)
        }
        super.onDestroy()
    }
}
