package dev.kb896.aiusage.rear

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import java.lang.reflect.Method

/**
 * A guest kept right of the cameras: the app on a virtual display of its own, that display's
 * picture on a layer of ours right of the lenses. Owned by RearUserService, one at a time.
 *
 * WHY NOT THE REAR ITSELF. A task confined on display 1 (multi-window bounds, x 296–904) read
 * right to the window manager and was composed at x 0 by HyperOS whatever it was nudged with
 * (2026-10-28 to 30, the old GuestShape.confined). Here nothing of the app's is on the rear to
 * misplace. Proven by tools/spikes/VirtualDisplaySpike.java, tried by the owner on the phone
 * (2026-10-30, a map in Brave: picture, finger, pinch, double tap to zoom, strip): its flags and
 * calls are these, and each is worth the trouble below.
 *
 * THE LAYER IS DELIBERATELY NOT TRUSTED, unlike every other layer of ours but the sheet's
 * blocker. InputDispatcher then drops touches on what is under it ("Dropping untrusted touch
 * event due to /2000"), so the panel below gets none over x >= the cutout. The panel underneath
 * keeps the rear's focus, which is why HyperOS's double tap does not sleep the back over one of
 * these (its flag is on the panel's window) and why no BackGuard runs for them.
 *
 * OPAQUE, as a backdrop. A portrait-locked app letterboxes on this landscape display and
 * `wm set-ignore-orientation-request -d N true` did not take on this firmware (the spike's first
 * try; it is still said once, in [open]'s caller, in case another does). Whatever the app leaves
 * clear would show the panel through: marked opaque, the layer has no holes, and they read black.
 *
 * TOUCH IS INJECTED, on a thread of its own ([forward]): the reader must never wait for a binder
 * call, however quick (~0.5 ms, read to inject, in the spike). Back is a key on this display
 * ([back]): MIUI's skip of the pre-IME stages is only for displayId 1 (see SystemBack).
 *
 * IT ALL DIES WITH THE PROCESS. The layer is a child of ShellLayer's container, which a kill takes
 * with it; the display manager sees the virtual display's token die and removes it; and with
 * DESTROY_CONTENT_ON_REMOVAL the task on it goes too, so nothing lands on the front uninvited.
 * [release] is the same thing in order, which is why Front moves the task off first.
 */
internal class PrivateDisplay private constructor(
    /** The display's logical id, for `am start --display`. */
    val displayId: Int,
    private val vd: VirtualDisplay,
    /** What shows it, for the strip's capture: hold with ShellLayer.lease. */
    val layer: SurfaceControl,
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private var dpi: Int,
    private val wakeIt: () -> Unit,
) {
    @Volatile private var released = false
    private var shown: Boolean? = null
    private var wokenAt = 0L

    private val injector = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            try { r.run() } catch (e: Throwable) { Log.w(TAG, "private display: the injector failed", e) }
        }, "portal-inject").apply { isDaemon = true }
    }
    // The injector's own: one thread, so no lock.
    private val props = Array(TouchForward.MAX_POINTERS) {
        MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_FINGER }
    }
    private val coords = Array(TouchForward.MAX_POINTERS) {
        MotionEvent.PointerCoords().apply { pressure = 1f; size = 0.1f }
    }
    @Volatile private var events = 0L
    @Volatile private var failures = 0L

    /** The layer in view or not: Panel and Resume. The app goes on, on its display, either way. */
    @Synchronized
    fun show(on: Boolean) {
        if (released || shown == on) return
        shown = on
        runCatching { SurfaceControl.Transaction().use { it.setVisibility(layer, on).apply() } }
            .onFailure { Log.w(TAG, "private display $displayId: layer not ${if (on) "shown" else "hidden"}: ${it.message}") }
        Log.i(TAG, "private display $displayId: layer ${if (on) "shown" else "hidden"}")
    }

    /** At [wanted] dpi: the app relayouts, display 1 knows nothing of it. A comparison when it is already; true when it changed. */
    @Synchronized
    fun density(wanted: Int): Boolean {
        if (released || wanted == dpi) return false
        return runCatching { vd.resize(width, height, wanted) }
            .onSuccess { dpi = wanted; Log.i(TAG, "private display $displayId: at $wanted dpi") }
            .onFailure { Log.w(TAG, "private display $displayId: resize refused: ${it.message}") }
            .isSuccess
    }

    /** These events on the injector's thread, in order. Any thread; never waits. */
    fun forward(out: List<TouchForward.Out>) {
        if (released) return
        runCatching { injector.execute { out.forEach(::inject) } }
    }

    /**
     * Awake, if it has slept. It has a power group of its own (OWN_DISPLAY_GROUP) and so its own
     * screen timeout: nobody's fingers reach it while the rear is dark, or the panel is up, and
     * then the app is stopped behind a picture still on the layer. Only ever asked as somebody
     * comes back to it, at most every [WAKE_EVERY_MS]; off the caller's thread, for its exec.
     */
    fun ensureAwake() {
        if (released) return
        runCatching { injector.execute { wakeIfAsleep() } }
    }

    private fun wakeIfAsleep() {
        val state = runCatching { vd.display.state }.getOrDefault(Display.STATE_ON)
        val now = SystemClock.uptimeMillis()
        if (state == Display.STATE_ON || now - wokenAt < WAKE_EVERY_MS) return
        wokenAt = now
        Log.i(TAG, "private display $displayId: state $state — waking it")
        wakeIt()
    }

    private fun inject(o: TouchForward.Out) {
        val a = api ?: return
        if (released) return
        if (o.masked == MotionEvent.ACTION_DOWN) wakeIfAsleep()
        val n = o.ids.size
        for (i in 0 until n) {
            props[i].id = o.ids[i]
            coords[i].x = o.xs[i]
            coords[i].y = o.ys[i]
        }
        val e = MotionEvent.obtain(o.downMs, o.atMs, o.action, n, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            events++
            a.setDisplayId.invoke(e, displayId)
            if (a.inject.invoke(a.manager, e, INJECT_ASYNC) != true && failures++ < LOGGED_FAILURES) Log.w(TAG, "private display $displayId: inject said no: $e")
        } catch (t: Throwable) {
            if (failures++ < LOGGED_FAILURES) Log.w(TAG, "private display $displayId: inject failed: ${(t.cause ?: t).message}")
        } finally {
            e.recycle()
        }
        if (events == 1L || events % SUMMARY_EVERY == 0L) Log.i(TAG, "private display $displayId: $events events injected, $failures failed")
        if (o.masked == MotionEvent.ACTION_CANCEL) Log.i(TAG, "private display $displayId: a gesture cancelled on the app, the shell took it")
    }

    /**
     * BACK, down and up, into this display. The display has its own focus (OWN_FOCUS), so the key
     * goes to the app's window, not to whatever is globally focused. False when it could not be
     * sent; what the app then does about it is the app's, as with any Back. On the caller's thread,
     * the Back worker's: nothing here waits for the app.
     */
    fun back(): Boolean {
        val a = api ?: return false
        if (released) return false
        return try {
            val now = SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val e = KeyEvent(now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_KEYBOARD)
                InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(e, displayId)
                if (a.inject.invoke(a.manager, e, INJECT_ASYNC) != true) return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "private display $displayId: Back not injected: ${(t.cause ?: t).message}")
            false
        }
    }

    /**
     * The display and the layer gone, in that order; whatever is still on the display goes with it.
     * Move what should live off first. Safe twice.
     */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        injector.shutdown()
        runCatching { vd.release() }.onFailure { Log.w(TAG, "private display $displayId: release: ${it.message}") }
        ShellLayer.remove(layer)
        runCatching { surface.release() }
        Log.i(TAG, "private display $displayId: released, $events events injected, $failures failed")
    }

    private class Api(val manager: Any, val inject: Method, val setDisplayId: Method)

    companion object {
        private const val TAG = "AiUsageRear"
        // DisplayManager's VIRTUAL_DISPLAY_FLAG_*: most are hidden, and these are the spike's.
        private const val PUBLIC = 1
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val OWN_FOCUS = 1 shl 14
        private const val FLAGS = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL or
            TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or OWN_FOCUS
        /** InputManager's INJECT_INPUT_EVENT_MODE_ASYNC: the spike's, 1200+ events and none refused. */
        private const val INJECT_ASYNC = 0
        private const val LOGGED_FAILURES = 5L
        private const val SUMMARY_EVERY = 500L
        private const val WAKE_EVERY_MS = 2_000L

        /** The injection calls, looked up once; null (logged) where they are missing: no touch, the rest goes on. */
        private val api: Api? by lazy {
            runCatching {
                val setId = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                val manager = runCatching {
                    Class.forName("android.hardware.input.InputManagerGlobal")
                }.getOrElse { Class.forName("android.hardware.input.InputManager") }
                Api(
                    manager = manager.getMethod("getInstance").invoke(null)!!,
                    inject = manager.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType),
                    setDisplayId = setId,
                )
            }.onFailure { Log.w(TAG, "private display: nothing to inject with: ${(it.cause ?: it).message}") }.getOrNull()
        }

        /**
         * This package's context saying it is the shell, which is what the caller is: the display
         * manager checks the package against the uid (DisplayManagerService.validatePackageName),
         * and Shizuku's context is our app's name with uid 2000 behind it. The manager is made by
         * hand so that it keeps this context: asked of the context, it is the base's.
         */
        private fun displays(context: Context): DisplayManager {
            val shell = object : ContextWrapper(context) {
                override fun getPackageName() = "com.android.shell"
                override fun getOpPackageName() = "com.android.shell"
            }
            val make = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            make.isAccessible = true
            return make.newInstance(shell)
        }

        /**
         * A new display right of [rear]'s lenses at [dpi], its layer made and hidden. Null, with
         * nothing left behind and the reason logged, where it cannot be: the caller then has the
         * guest on the whole rear. [wake]: lights this display when asked by [ensureAwake].
         */
        fun open(context: Context, layerStack: Int, rear: RearGeometry, dpi: Int, wake: (Int) -> Unit): PrivateDisplay? {
            val area = GuestShape.appArea(rear)
            var layer: SurfaceControl? = null
            var surface: Surface? = null
            return try {
                val made = ShellLayer.create(
                    "portal-app", layerStack, ShellLayer.Z_APP, area.width, area.height,
                    trusted = false, visible = false, format = PixelFormat.RGBA_8888, opaque = true,
                )
                layer = made
                SurfaceControl.Transaction().use { it.setPosition(made, area.left.toFloat(), area.top.toFloat()).apply() }
                val s = Surface(made)
                surface = s
                val vd = displays(context).createVirtualDisplay("ai-usage-portal", area.width, area.height, dpi, s, FLAGS)
                    ?: throw IllegalStateException("createVirtualDisplay gave nothing")
                val id = vd.display.displayId
                Log.i(TAG, "private display $id: ${area.width}x${area.height} at $dpi dpi, layer at x ${area.left}, flags 0x${FLAGS.toString(16)}")
                PrivateDisplay(id, vd, made, s, area.width, area.height, dpi) { wake(id) }
            } catch (e: Throwable) {
                Log.w(TAG, "private display: none (${e.javaClass.simpleName}: ${e.message})")
                ShellLayer.remove(layer)
                runCatching { surface?.release() }
                null
            }
        }
    }
}
