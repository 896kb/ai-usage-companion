package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import dev.kb896.aiusage.data.GlassStyle
import dev.kb896.aiusage.data.PanelTheme
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The back's control centre over a GUEST, drawn and driven by the shell service: pulled down
 * from the top edge over whatever app is on the rear, looking and behaving as the panel's
 * own (RearDialView, which keeps it over our panel). Its sheet is a ShellOverlay, as the
 * portal sheet's is — the live blur and dim, the blocker while it is up — and what is on it
 * is ControlCenterPainter's, the panel's drawing; its state is a ControlCenterModel.
 *
 * TOUCHES come from the service's raw touch reader ([touch]), which hands over every event
 * with what EdgeGestures made of it. The top-edge pull (EdgeGestures' TopPull*) opens it
 * when the service says it may ([touch]'s opens: a guest on top, no portal sheet). Once it
 * is down at all, every finger that lands is its own (ControlCenterFinger): the cell, the
 * button, the tiles, a drag up to shut it, and the right edge's Back, which shuts it rather
 * than going back in the guest. The service's own reading of those fingers is dropped, but
 * for the endings of a gesture of its own that never lifted.
 *
 * WHAT IT DOES: the brightness and automatic mode it sets itself, through [rear] (the
 * service's own setRearBrightness / setRearAutoBrightness), on a worker, a drag's writes no
 * more often than ControlCenter.SEND_MS and only the latest; the brightness is read as it
 * opens, and every second while it is open (automatic changes it). The tiles are the app's:
 * a tap goes out by [events] (IRearEvents.onQuick) and the states come back by
 * [quickStates]; opening asks for them (onControlCenterOpening).
 *
 * THREADING. [lock] guards the model and the flags. [touch] runs on the reader thread
 * (under the service's sheet lock); the overlay's render thread reads the model under
 * [lock] once a frame; [clock], one thread, runs the overlay's begin()/end() in the order
 * asked, so the reader never waits for a render thread's join; [io] runs the commands.
 *
 * @param layerStack the rear display's layer stack, asked once, when the sheet is first made.
 * @param geometry the rear display as it is (RearGeometry), asked then too: the sheet's size and scale.
 * @param onProgress how far it is drawn open, each frame, and 0 once down (the portal frame
 *   fades under it: PortalFrame.controlCenterOpen).
 */
class ShellControlCenter(
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    private val rear: Rear,
    private val events: () -> dev.kb896.aiusage.IRearEvents?,
    private val onProgress: (Float) -> Unit,
) {

    /** The rear's brightness, as the service reads and writes it. Called on [io]. */
    interface Rear {
        /** 1 to 255; -1 when it cannot be read. */
        fun brightness(): Int
        fun auto(): Boolean
        fun setBrightness(value: Int)
        fun setAuto(on: Boolean)
    }

    private val lock = Any()
    private val model = ControlCenterModel()
    /** Hit-tested right of this rear's own lenses, where it is drawn (ControlCenterLayout). */
    private val finger = ControlCenterFinger { x, y -> ControlCenterLayout.hit(x, y, geometry().cutoutUnits) }
    /** The finger down now is the control centre's. Under [lock]. */
    private var owns = false
    /** Where the latest finger landed, for the opening pull. Under [lock]. */
    private var downY = 0f
    /** Asked up (begin queued or done) and not yet ended. Under [lock]. */
    @Volatile private var up = false

    /** Up, or on its way: for the touch reader to read with no lock (a private display's fingers stop). */
    val isUp: Boolean get() = up
    /** begin() queued on [clock] and not yet run. Under [lock]. */
    private var beginPending = false
    /** Its sheet, made with the face (off the touch reader: the layer stack is a dumpsys). */
    @Volatile private var overlay: ShellOverlay? = null
    private val making = Any()
    @Volatile private var face: PanelType.Face? = null
    @Volatile private var look: Pair<PanelTheme, GlassStyle?> = PanelTheme.GLASS to null
    /** The panel's background (PanelConfig.background): a flat button's mark and the cell's ground under ORIGINAL. */
    @Volatile private var background = 0xFF000000.toInt()
    private var shutCheck: ScheduledFuture<*>? = null
    private var reads: ScheduledFuture<*>? = null
    /** The latest brightness a drag asked for, written by [io]; -1 none pending. */
    private val pendingBrightness = AtomicInteger(-1)

    private val clock: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "control-centre-clock").apply { isDaemon = true }
    }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "control-centre-io").apply { isDaemon = true } }

    /**
     * The panel's face (ShellFont.face), for the A and the figure; nothing opens before it is
     * known. The first one makes the sheet, on the caller's thread (the service's sheet-making,
     * off the touch reader).
     */
    fun face(f: PanelType.Face) {
        face = f
        overlayOrNull()?.changed()
    }

    /** The panel's theme, Glass settings' style (as the portal sheet takes them) and background. */
    fun theme(theme: PanelTheme, style: GlassStyle?, background: Int) {
        look = theme to style
        this.background = background
        overlay?.changed()
    }

    /** The tiles' states as the app has them (IRearService.setQuickStates). Any thread. */
    fun quickStates(states: BooleanArray, reachable: Boolean) {
        synchronized(lock) { model.quickStates(states, reachable, SystemClock.uptimeMillis()) }
        overlay?.changed()
    }

    /** Down at all, or being pulled: a finger landing now would be its own. Any thread. */
    val shown: Boolean get() = synchronized(lock) { up && model.shown(SystemClock.uptimeMillis()) }

    /**
     * One raw touch [e], with what EdgeGestures made of it ([actions]). Takes what is its own
     * — the top pull, every finger while it is shown — and returns the rest for the service;
     * of a finger of its own only the endings of a gesture that never lifted go back
     * (PullEnd, SlideEnd, Cancel: the portal sheet's, harmless when it is down). [opens] says
     * whether a pull may open it now (a guest on top, no portal sheet); asked on this thread.
     */
    fun touch(e: RearTouches.Event, actions: List<EdgeGestures.Action>, opens: () -> Boolean): List<EdgeGestures.Action> {
        val effects = ArrayList<ControlCenterModel.Effect>()
        val rest = ArrayList<EdgeGestures.Action>()
        var begin = false
        synchronized(lock) {
            val now = e.atMs
            if (e is RearTouches.Down) {
                downY = e.y
                // Its layers went (the render loop died): nothing shows, so nothing is held.
                if (up && !beginPending && overlay?.up != true) {
                    Log.w(TAG, "control centre: its layers are gone — shut")
                    up = false; model.reset(); stopTimers()
                }
                owns = up && model.shown(now)
            }
            for (a in actions) when (a) {
                // Up but shut (its layers about to go): pulled again, it comes back on them.
                is EdgeGestures.Action.TopPullStart -> if (!owns && opens() && overlay != null && face != null) {
                    model.beginPull(downY, a.y, now)
                    if (!up) { up = true; begin = true; beginPending = true }
                }
                is EdgeGestures.Action.TopPullMove -> model.movePull(a.y, now)
                is EdgeGestures.Action.TopPullEnd -> effects += model.endPull(a.y, now)
                is EdgeGestures.Action.PullEnd, is EdgeGestures.Action.SlideEnd, EdgeGestures.Action.Cancel -> rest += a
                else -> if (!owns) rest += a
            }
            if (owns) for (f in finger.feed(e)) effects += model.finger(f, now)
        }
        if (begin) open()
        if (synchronized(lock) { up }) overlay?.changed()
        effects.forEach(::carryOut)
        return rest
    }

    /**
     * Shut at once, no settle (the rear is going dark, the touch reader stopped, the service
     * is going). [sync]: the layers go on this thread (destroy, which exits next).
     */
    fun closeNow(why: String, sync: Boolean = false) {
        val o = synchronized(lock) {
            if (!up) return
            Log.i(TAG, "control centre: shut — $why")
            up = false
            model.reset()
            stopTimers()
            overlay
        } ?: return
        if (sync) runCatching { o.end() } else runCatching { clock.execute { runCatching { o.end() } } }
    }

    /** The pull began: the sheet up on [clock], and the brightness and the tiles asked for. */
    private fun open() {
        val o = overlay
        if (o == null) { synchronized(lock) { up = false; beginPending = false; model.reset() }; return }
        val queued = runCatching {
            clock.execute {
                val ok = synchronized(lock) { up } && runCatching { o.begin() }.getOrElse {
                    Log.w(TAG, "control centre: begin threw: ${it.message}"); false
                }
                synchronized(lock) {
                    beginPending = false
                    if (!ok) {
                        if (up) Log.i(TAG, "control centre: could not come up — the pull does nothing")
                        up = false; model.reset(); stopTimers()
                    }
                }
                if (!ok) runCatching { o.end() }
            }
        }.isSuccess
        if (!queued) { synchronized(lock) { up = false; beginPending = false; model.reset() }; return }
        runCatching { events()?.onControlCenterOpening() }
        read()
        synchronized(lock) {
            reads?.cancel(false)
            reads = runCatching { clock.scheduleAtFixedRate({ if (synchronized(lock) { up }) read() }, READ_EVERY_MS, READ_EVERY_MS, TimeUnit.MILLISECONDS) }.getOrNull()
        }
    }

    /** Its sheet, made once; null if it cannot be (tried again with the next face). */
    private fun overlayOrNull(): ShellOverlay? {
        overlay?.let { return it }
        synchronized(making) {
            overlay?.let { return it }
            val made = runCatching { ShellOverlay(layerStack(), geometry(), "control-centre", "control centre", Drawing()) }
                .onFailure { Log.w(TAG, "control centre: no sheet: ${it.message}") }.getOrNull() ?: return null
            made.onProgress = onProgress
            overlay = made
            return made
        }
    }

    /** The brightness and its mode, read on [io] and handed to the model. */
    private fun read() {
        runCatching {
            io.execute {
                val value = runCatching { rear.brightness() }.getOrDefault(-1)
                val auto = runCatching { rear.auto() }.getOrDefault(false)
                synchronized(lock) { model.reading(value, auto, SystemClock.uptimeMillis()) }
                overlay?.changed()
            }
        }
    }

    private fun carryOut(effect: ControlCenterModel.Effect) {
        when (effect) {
            is ControlCenterModel.Effect.Brightness -> {
                // Only the newest is worth writing: the worker writes whatever is latest when it gets to it.
                pendingBrightness.set(effect.value)
                runCatching {
                    io.execute {
                        val v = pendingBrightness.getAndSet(-1)
                        if (v in 1..255) runCatching { rear.setBrightness(v) }.onFailure { Log.w(TAG, "control centre: brightness: ${it.message}") }
                    }
                }
            }
            is ControlCenterModel.Effect.Auto -> runCatching {
                io.execute {
                    runCatching { rear.setAuto(effect.on) }.onFailure { Log.w(TAG, "control centre: automatic: ${it.message}") }
                }
                // The level automatic picks shows a moment later.
                clock.schedule({ read() }, AUTO_READ_MS, TimeUnit.MILLISECONDS)
            }
            is ControlCenterModel.Effect.Quick -> {
                val ev = events()
                if (ev == null) Log.w(TAG, "control centre: ${effect.which} — no app to switch it")
                else runCatching { ev.onQuick(effect.which, effect.on) }.onFailure { Log.w(TAG, "control centre: ${effect.which}: ${it.message}") }
            }
            is ControlCenterModel.Effect.ShutAt -> synchronized(lock) {
                shutCheck?.cancel(false)
                val delay = (effect.atMs - SystemClock.uptimeMillis()).coerceAtLeast(0L) + SHUT_GRACE_MS
                shutCheck = runCatching { clock.schedule({ endIfShut() }, delay, TimeUnit.MILLISECONDS) }.getOrNull()
            }
        }
    }

    /** Settled shut with nothing in hand: the layers go (on [clock]). */
    private fun endIfShut() {
        val o = synchronized(lock) {
            if (!up || !model.isShut(SystemClock.uptimeMillis())) return
            up = false
            stopTimers()
            overlay
        } ?: return
        runCatching { o.end() }.onFailure { Log.w(TAG, "control centre: end failed: ${it.message}") }
    }

    /** Under [lock]. */
    private fun stopTimers() {
        shutCheck?.cancel(false); shutCheck = null
        reads?.cancel(false); reads = null
    }

    /** What the overlay draws: the model as it is, through the panel's painter. */
    private inner class Drawing : ShellOverlay.Content {
        private var frameProgress = 0f
        private var frameLook: ControlCenterPainter.Look? = null

        override fun frame(): Float = synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            frameProgress = model.progress(now)
            frameLook = model.look(now)
            frameProgress
        }

        override fun painter(): ShellOverlay.Painter {
            val f = face ?: return ShellOverlay.Painter { _, _ -> false }
            val rear = geometry()
            val painter = ControlCenterPainter(f, rear.cutoutUnits)
            val cell = LiquidCellPainter.Cell()
            val glass = GlassPainter()
            var style: Pair<PanelTheme, GlassStyle?> = look
            // No picture of what is under it to bend: unbacked panes, clear glass over a
            // middling ground or the chosen style, as the portal sheet's (SheetTint).
            val lens = object : LiquidCellPainter.Lens {
                override fun pane(canvas: Canvas, rect: RectF, radius: Float, shape: Path?, rim: Boolean) =
                    glass.drawPane(canvas, rect, radius, SheetTint.of(style.first.isGlass, style.second), shape = shape, rim = rim, source = null, local = true)
            }
            return ShellOverlay.Painter { canvas, progress ->
                val l = frameLook ?: return@Painter false
                face?.let { painter.face = it }
                style = look
                painter.theme = style.first
                glass.beginFrame(canvas.width, canvas.height)
                painter.draw(
                    canvas, progress, l, cell, lens, SystemClock.elapsedRealtime(),
                    // The rear's own corner, in panel units as the cell is drawn.
                    corner = rear.cornerPx / rear.fitX,
                    buttonGround = { background or (0xFF shl 24) },
                    cellGround = background,
                )
                // The liquid moves and the figure keeps time while it is down at all.
                progress > 0f
            }
        }
    }

    companion object {
        private const val TAG = "AiUsageRear"
        /** While it is open, the brightness is read this often: automatic moves it. */
        private const val READ_EVERY_MS = 1_000L
        /** After automatic is switched, when the level it picked is read. */
        private const val AUTO_READ_MS = 700L
        /** A settle's end, and a frame more, before its layers go. */
        private const val SHUT_GRACE_MS = 32L
    }
}
