package dev.rgarjoaba.claudestatus.rear

import android.app.Activity
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
import dev.rgarjoaba.claudestatus.Panel
import dev.rgarjoaba.claudestatus.data.StatePayload

class RearActivity : Activity() {

    companion object {
        const val ACTION_STATE = "dev.rgarjoaba.claudestatus.STATE"
        const val ACTION_FINISH = "dev.rgarjoaba.claudestatus.FINISH"
        const val EXTRA_STATE = "state"
        private const val TAG = "ClaudeStatusRear"

        /**
         * How long the activity may sit on a non-rear display before evicting itself.
         * Must comfortably exceed the launch→poll→move sequence (RearUserService.show():
         * up to 20 x 50ms of task-id polling plus the moveRootTaskToDisplay binder call),
         * while staying short enough that a user staring at a hijacked main screen is not
         * stuck for long. Never raise this to paper over a placement bug — fix the bug.
         */
        const val STRAND_GRACE_MS = 4_000L
    }

    private lateinit var dial: RearDialView
    private lateinit var calibrationView: CalibrationView
    private var calibrating = false
    private var geometry = PanelGeometry.DEFAULT

    /** -1 while on the rear panel; set once, to the elapsed-time clock, on the first
     *  wrong-display detection. See armEvictionTimer(). */
    private var strandedSinceMs = -1L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Geometry nudges ride the same ACTION_STATE broadcast as normal status
            // updates (`--ei cx1 148 ...`, independent of the `state` payload) so a
            // human can dial the rings in live, without relaunching. Apply before
            // dispatching on action.
            geometry = PanelGeometry.fromExtras(intent?.extras, geometry)
            calibrationView.geometry = geometry
            dial.geometry = geometry
            when (intent?.action) {
                ACTION_FINISH -> finish()
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
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // The display is LIVE inside the reported cutout — render across all 904px.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        calibrationView = CalibrationView(this)
        dial = RearDialView(this)
        setContentView(dial)

        // No-op until we are actually on the rear panel — see hideBars().
        hideBars()

        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply { addAction(ACTION_STATE); addAction(ACTION_FINISH) },
            ContextCompat.RECEIVER_EXPORTED,
        )
        render(intent?.getStringExtra(EXTRA_STATE).orEmpty())
    }

    private fun render(payload: String) {
        val wantCalibrate = CalibrateMode.isCalibrate(payload)
        if (wantCalibrate != calibrating) {
            calibrating = wantCalibrate
            setContentView(if (calibrating) calibrationView else dial)
            // FLAG_KEEP_SCREEN_ON only in calibrate mode — the spec forbids it as a
            // default; a permanently-lit panel is exactly the battery/burn-in cost
            // this design exists to avoid. MIUI powers the panel down within seconds
            // otherwise, too fast for a human to read a calibration grid off.
            if (calibrating) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        if (!calibrating) {
            dial.state = StatePayload.decode(payload)
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
    private val evictIfStranded = Runnable {
        if (display?.displayId != Panel.DISPLAY_ID) {
            Log.w(TAG, "stranded on display ${display?.displayId}, evicting")
            finish()
        }
    }

    private fun armEvictionTimer() {
        window.decorView.removeCallbacks(evictIfStranded)
        if (display?.displayId == Panel.DISPLAY_ID) {
            strandedSinceMs = -1L
            return
        }
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
        super.onDestroy()
    }
}
