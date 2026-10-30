package dev.kb896.aiusage.service

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.RearActivity
import dev.kb896.aiusage.rear.ShizukuBridge
import java.util.concurrent.Executors

/**
 * POCKET MODE: the rear proximity sensor covered while the back is lit switches the back off
 * at once; uncovered, it comes back as it was, the slider up. [PocketMode] decides; this
 * holds the sensor, the clock and the calls.
 *
 * IN THE POLL SERVICE'S PROCESS, NOT THE PANEL'S, and that is the point of this class. The
 * watch used to be RearActivity's, which was fine while all it had to see was a pocket coming
 * — the back lit, the panel up. Now it has to see the way OUT, with the back switched off,
 * and a dark back stops the panel's activity: its :rear process is then cached like any app
 * in the background, and a cached process can be frozen, which a sensor's events do not thaw.
 * A guest on top stops it too, lit. This process holds the foreground service, so it is
 * neither cached nor frozen, and its uid counts as active for the sensor service, which
 * gives idle uids no on-change events at all.
 *
 * THE WAKE-UP SENSOR, where the phone has one. The 17 Pro has two on the back, the same
 * "xiaomi.sensor.proxmity_back" (sic) as a non-wake-up and a wake-up sensor; with both
 * screens off the application processor suspends, and only a wake-up sensor's event wakes
 * it to be delivered. Its delivery holds a wakelock only until the listener returns, and
 * the way out is two binder calls and an exec after that, so a short wakelock of our own
 * carries it ([WAKE_HOLD_MS]).
 *
 * Only while the switch is on and the back is lit, or switched off for a pocket: a back
 * dark for any other reason is not watched (PocketMode.watching).
 */
class PocketWatch(private val context: Context, private val bridge: ShizukuBridge) {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val displays = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private val mode = PocketMode(RearActivity.POCKET_DEBOUNCE_MS)

    /**
     * The binder calls (an `am stack list` and an exec in the shell) and the panel's word, in
     * order, off the main thread: a way out queued behind the switch-off it undoes must land
     * after it, and the panel must hear "in a pocket" before "out of it".
     */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pocket").apply { isDaemon = true } }

    private val wakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiUsage:pocket")
            .apply { setReferenceCounted(false) }
    }

    /**
     * The proximity sensor on the BACK of the phone, and only that one, the wake-up one
     * first. Never the front sensor: falling back to it made the panel read "in a pocket"
     * whenever the phone was held or lay on its face. Matched loosely after the 17 Pro's own
     * name, in case another model spells it differently.
     */
    private val sensor: Sensor? by lazy {
        val all = sensors.getSensorList(Sensor.TYPE_ALL)
        val rear = Regex("(?i)prox.*(back|rear|sub)|(back|rear|sub).*prox")
        val backs = all.filter { it.stringType == "xiaomi.sensor.proxmity_back" }
            .ifEmpty { all.filter { rear.containsMatchIn(it.stringType) || rear.containsMatchIn(it.name) } }
        (backs.firstOrNull { it.isWakeUpSensor } ?: backs.firstOrNull()).also {
            if (it == null) Log.w(TAG, "no rear proximity sensor — no pocket mode")
            else Log.i(TAG, "the rear proximity sensor: ${it.name} (${it.stringType}), wake-up ${it.isWakeUpSensor}, range ${it.maximumRange}")
        }
    }

    private var switchOn = false
    private var watching = false
    private var stopped = false

    /** Whether a guest app was on top when the back went into the pocket. The worker's. */
    @Volatile private var guestAtSleep = false

    /** The switch, from the config: read once per write, not once per display change. */
    fun setSwitch(on: Boolean) {
        if (on == switchOn) return
        switchOn = on
        sync()
    }

    /**
     * The rear display changed — any property, as DisplayListener says it; PocketMode only
     * hears the power. And everything else that changes what to watch comes here too.
     */
    fun sync() {
        if (stopped) return
        mode.lit(displays.getDisplay(Panel.DISPLAY_ID)?.state == android.view.Display.STATE_ON, SystemClock.uptimeMillis())
        if (!switchOn && mode.pocketed) {
            // Switched off in the pocket: the back stays as it is, and wakes light it again.
            callOff()
        }
        if (!switchOn) mode.end()
        watch(switchOn && mode.watching)
        schedule()
    }

    /** The service going: a pocket is called off, not left declining every wake for good. */
    fun stop() {
        if (stopped) return
        if (mode.pocketed) callOff()
        mode.end()
        watch(false)
        stopped = true
        handler.removeCallbacks(due)
        worker.shutdown() // what is queued still runs: the pocket called off reaches the shell
    }

    private fun watch(on: Boolean) {
        if (on == watching) return
        val s = sensor ?: return
        watching = on
        if (on) {
            // An on-change sensor hands a new listener its current reading, so a back lit
            // already covered is counted from here.
            sensors.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "pocket mode: watching")
        } else {
            sensors.unregisterListener(listener)
            Log.i(TAG, "pocket mode: not watching")
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val covered = e.values[0] < e.sensor.maximumRange.coerceAtLeast(1f) * 0.5f
            act(mode.reading(covered, SystemClock.uptimeMillis()))
            sync()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** The debounce's end. A back the panel is already letting go of is left to go (PocketMode.due). */
    private val due = Runnable {
        act(mode.due(SystemClock.uptimeMillis(), lettingGo = bridge.isDozing()))
        sync()
    }

    private fun schedule() {
        handler.removeCallbacks(due)
        val at = mode.dueAt()
        if (at >= 0 && watching) handler.postAtTime(due, at)
    }

    private fun act(a: PocketMode.Act) {
        if (stopped) return // a reading already queued as the watch stopped
        when (a) {
            PocketMode.Act.SLEEP -> sleep()
            PocketMode.Act.RESTORE -> restore()
            PocketMode.Act.SWITCH_OFF -> switchOff()
            PocketMode.Act.NONE -> Unit
        }
    }

    /**
     * Into the pocket. The shell switches the back off first, and only then is the panel
     * told — to let go of the display and take no hold until it is out (RearActivity.intoPocket):
     * told first, it would let go of a back the shell then refused to switch off, the other
     * build's say. Whether a guest was on top is read before, while the back is still lit:
     * coming out, no slider can go over a guest, and the panel behind it is left alone.
     */
    private fun sleep() {
        Log.i(TAG, "pocket: covered while lit — switching the back off")
        worker.execute {
            val guest = bridge.guestOnTop() == true
            if (bridge.pocketSleep()) {
                guestAtSleep = guest
                tellPanel(pocketed = true, restore = false, guest = guest)
            } else {
                Log.w(TAG, "pocket: the back could not be switched off — left as it is")
                handler.post { mode.refused(); sync() }
            }
        }
    }

    /**
     * Covered while the back was already going dark (the double tap's dim): off now, and no
     * pocket — the shell's is ended at once, without a light, so nothing is held off and the
     * way out has nothing to undo. The panel is not told: the display going dark under its
     * dim is the double tap's ordinary end (RearActivity.endDim).
     */
    private fun switchOff() {
        Log.i(TAG, "pocket: covered while going dark — switching the back off now")
        worker.execute {
            if (bridge.pocketSleep()) bridge.pocketEnd(light = false)
        }
    }

    /**
     * Out of the pocket: the panel first — the slider up, holds allowed again — then the back
     * switched on, so what lights up is the guarded panel. Under a wakelock: see the class.
     */
    private fun restore() {
        Log.i(TAG, "pocket: uncovered — switching the back on, as it was")
        wakeLock.acquire(WAKE_HOLD_MS)
        worker.execute {
            try {
                tellPanel(pocketed = false, restore = true, guest = guestAtSleep)
                bridge.pocketEnd(light = true)
            } finally {
                wakeLock.release()
            }
        }
    }

    /** The pocket called off, the back left as it is: wakes light it again, the panel holds again. */
    private fun callOff() {
        Log.i(TAG, "pocket: called off — the back stays as it is")
        worker.execute {
            tellPanel(pocketed = false, restore = false, guest = guestAtSleep)
            bridge.pocketEnd(light = false)
        }
    }

    private fun tellPanel(pocketed: Boolean, restore: Boolean, guest: Boolean) {
        context.sendBroadcast(
            Intent(RearActivity.ACTION_POCKET).setPackage(context.packageName)
                .putExtra(RearActivity.EXTRA_POCKETED, pocketed)
                .putExtra(RearActivity.EXTRA_POCKET_RESTORE, restore)
                .putExtra(RearActivity.EXTRA_POCKET_GUEST, guest),
        )
    }

    private companion object {
        const val TAG = "AiUsagePocket"
        /** Long enough for the panel's word, the binder call and the shell's wake; released as they end. */
        const val WAKE_HOLD_MS = 3_000L
    }
}
