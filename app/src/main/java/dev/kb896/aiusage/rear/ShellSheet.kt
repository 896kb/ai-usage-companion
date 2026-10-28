package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RecordingCanvas
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.GlassStyle
import dev.kb896.aiusage.data.PanelTheme
import java.util.concurrent.atomic.AtomicInteger

/**
 * The App Portal's sheet, drawn by the shell service on a layer of its own on the rear
 * display — over our panel or a guest, locked or not, since nothing is ever brought
 * "above" anything. See specs/2026-10-28-app-portal-shell-sheet-design.md.
 *
 * TWO LAYERS, made with ShellLayer:
 * - the SHEET ([ShellLayer.Z_SHEET], under the frame), a trusted overlay — fail closed, as
 *   the frame: if it cannot be trusted nothing is shown — drawn through
 *   Surface.lockHardwareCanvas, a RecordingCanvas (measured on the 17 Pro), so the
 *   backdrop's RenderEffect blur and the glass's lenses work;
 * - the BLOCKER ([ShellLayer.Z_BLOCKER], under the sheet), full screen, one fully
 *   transparent buffer, and NOT trusted. Its whole job is Android's untrusted-touch
 *   occlusion: while it is up, every touch to the windows below — the guest, our panel —
 *   is dropped, so a tap on Close cannot also tap the app. The touches themselves are
 *   read off the raw touchscreen by the service.
 * Both appear in one transaction and go in one transaction: a blocker without its sheet
 * would be an invisible wall over the rear, so there is never one without the other.
 *
 * WHAT IS DRAWN, per frame: cleared; the [backdrop] — the rear's screencap, decoded at
 * half size and stretched back to the panel — through a RenderNode with a blur of
 * [BLUR] × progress and an alpha of PortalPull.guestGround(progress), so at rest the live
 * screen shows through and the ground comes in with the pull; the dim, as the control
 * centre's; then PortalSheetPainter, its glass bending that node and tinted by SheetTint.
 *
 * THREADING. [begin] makes both layers and shows them on the caller's thread, under the
 * instance lock, so when it says true they are there. Setters may be called from any
 * thread; they publish and bump a version. One render thread per opening only draws:
 * while the version moves, sleeping [FRAME_MS] between looks (~60 fps while anything
 * changes, nothing redrawn while nothing does — the last buffer stays on screen). The
 * render thread never takes the instance lock, so [end] may join it. All painting in
 * this process happens under [DRAW]: the painters share Glass's shaders, and a run
 * outliving its join (a render thread stuck in the driver) must not paint over the next
 * one's uniforms.
 *
 * @param layerStack the rear display's layer stack (RearUserService.rearLayerStack).
 * @param face the panel's face, for every word on the sheet — PanelType.face for the
 *   panel's theme and font, so the sheet speaks as the panel does; [face] swaps it when
 *   either changes. The shell (app_process) has NO default typeface — text drawn without
 *   one aborts (gDefaultTypeface == nullptr) — so the caller builds it (see ShellFont.face):
 *   the system's fonts loaded first, then through our Resources, or Departure Mono read
 *   out of our APK when they cannot. Never Typeface.DEFAULT.
 */
class ShellSheet(private val layerStack: Int, face: PanelType.Face) {

    /** The backdrop as drawn and as measured: decoded once, read by every frame after. */
    private class Backdrop(val bitmap: Bitmap, val cells: FloatArray)

    private class Look(val theme: PanelTheme, val style: GlassStyle?)

    /** The pinned apps with their icons already decoded, so drawing never decodes. */
    private class Apps(val snapshot: PortalAppsSnapshot, val icons: List<Bitmap?>)

    /**
     * One opening: its layers, made by [begin]. The layers are guarded by the Run's own
     * monitor — [end] hides them, the render thread takes them away and releases them.
     * The surfaces are the render thread's from the moment it starts.
     */
    private class Run(
        val gen: Int,
        var blocker: SurfaceControl?,
        var sheet: SurfaceControl?,
        val blockerSurface: Surface,
        val surface: Surface,
    ) {
        /** Between a successful [begin] and [end], and not after the render loop died. */
        @Volatile var live = true
    }

    @Volatile private var progress = 0f
    @Volatile private var backdrop: Backdrop? = null
    @Volatile private var apps = Apps(PortalAppsSnapshot.EMPTY, emptyList())
    /** Null: no guest, so no Panel/Resume · Front · Close strip. */
    @Volatile private var guestOnTop: Boolean? = null
    @Volatile private var pressed = -1
    /** The app whose DPI row is open; null none. */
    @Volatile private var dpiPkg: String? = null
    /** The apps' DPI as the shell has it now (PanelConfig.portalDpi and the back's nudges). */
    @Volatile private var dpiValues: Map<String, Int> = emptyMap()
    @Volatile private var dpiDefault: Int? = null
    @Volatile private var look = Look(PanelTheme.GLASS, null)
    @Volatile private var face: PanelType.Face = face
    private val version = AtomicInteger()

    /** The current screencap's id ([newShot]); a backdrop for any other is dropped. Guarded by [shotLock]. */
    private var shot = 0
    private val shotLock = Any()

    /** Volatile: [up] reads it from threads that do not hold the lock. */
    @Volatile private var thread: Thread? = null
    @Volatile private var run: Run? = null
    /** Bumped by every begin() and end(); a render thread of another generation stops. */
    @Volatile private var gen = 0
    @Volatile private var failedAt = 0L
    /** Once per process: the firmware gives no way to make the sheet a trusted overlay. */
    @Volatile private var untrustedLogged = false

    /**
     * The sheet and its blocker are on the rear: true from a [begin] that returned true
     * until [end], false at once if the render loop dies (its layers are then gone).
     * Callers must gate hit-testing on it — a tap is the sheet's only while it is up;
     * otherwise it went to whatever is under it.
     */
    val up: Boolean get() = run?.live == true

    /** The same as [up]. */
    val showing: Boolean get() = up

    /**
     * Makes the blocker and the sheet, shows both in one transaction at [progressStart],
     * and starts the render thread — all before returning, so true means they are up.
     * False when it cannot (no trusted overlay, no layer, the blocker's buffer refused, or
     * any of these in the last [RETRY_NS]); then there is no sheet and no blocker and the
     * caller must not act as if one were up. Already up: just the progress.
     */
    @Synchronized
    fun begin(progressStart: Float = 0f): Boolean {
        progress(progressStart)
        if (up) return true
        if (failedAt != 0L && System.nanoTime() - failedAt < RETRY_NS) return false
        var blocker: SurfaceControl? = null
        var sheet: SurfaceControl? = null
        var blockerSurface: Surface? = null
        var surface: Surface? = null
        try {
            // Both made hidden, and shown together below.
            blocker = ShellLayer.create("portal-sheet-blocker", layerStack, ShellLayer.Z_BLOCKER, trusted = false, visible = false)
            sheet = ShellLayer.create("portal-sheet", layerStack, ShellLayer.Z_SHEET, trusted = true, visible = false)
            // A layer with no buffer has no input window and occludes nothing: the blocker
            // gets one, transparent, once. Assigned first, so a refused post still releases it.
            blockerSurface = Surface(blocker)
            clear(blockerSurface)
            surface = Surface(sheet)
            SurfaceControl.Transaction().use { t ->
                t.setVisibility(blocker, true).setVisibility(sheet, true).apply()
            }
            val r = Run(++gen, blocker, sheet, blockerSurface, surface)
            run = r
            thread = Thread({ render(r) }, "portal-sheet").apply { isDaemon = true; start() }
            return true
        } catch (e: Throwable) {
            if (e is ShellLayer.Untrusted) {
                if (!untrustedLogged) { untrustedLogged = true; Log.w(TAG, "portal sheet: not shown, no trusted overlay: ${e.message}") }
            } else {
                Log.w(TAG, "portal sheet: no layer: ${e.message}")
            }
            failedAt = System.nanoTime()
            run = null
            thread = null
            hide(blocker, sheet)
            runCatching { surface?.release() }
            runCatching { blockerSurface?.release() }
            ShellLayer.remove(sheet)
            ShellLayer.remove(blocker)
            return false
        }
    }

    /**
     * Hides the blocker and the sheet at once — in one transaction, on this thread, so the
     * blocker is gone no later than the sheet even if the render thread is stuck — stops
     * the render thread, and forgets the backdrop (and any screencap still on its way)
     * and the press: every opening brings its own screencap.
     */
    @Synchronized
    fun end() {
        val r = run
        run = null
        gen++
        if (r != null) synchronized(r) {
            r.live = false
            hide(r.blocker, r.sheet)
        }
        thread?.let { t ->
            t.interrupt()
            // The render thread never takes this lock, so waiting for it here is safe. If
            // it does not stop in time it still ends itself (its generation is gone) and
            // releases its own layers, which are hidden already.
            runCatching { t.join(JOIN_MS) }
        }
        thread = null
        synchronized(shotLock) {
            shot++
            backdrop = null
        }
        pressed = -1
        dpiPkg = null
        progress = 0f
        version.incrementAndGet()
    }

    /** How far open, 0 shut to 1 open. */
    fun progress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        version.incrementAndGet()
    }

    /**
     * A new screencap is being taken: forgets the backdrop shown now and returns the new
     * one's id, for [backdrop]. Any screencap taken before it is dropped when it lands.
     */
    fun newShot(): Int {
        val id = synchronized(shotLock) {
            backdrop = null
            ++shot
        }
        version.incrementAndGet()
        return id
    }

    /**
     * Screencap [shot] (from [newShot]) as PNG [png]; null forgets it. Dropped unless
     * [shot] is still the current one — a late screenshot stays out of the next opening.
     * Decoded here, on the caller's thread, at half size, and measured for the glass's
     * tint (5–15 ms): call it from the capture thread, never the getevent reader. A
     * picture that does not decode leaves the one before.
     */
    fun backdrop(png: ByteArray?, shot: Int) {
        if (shot != synchronized(shotLock) { this.shot }) return
        val b = png?.let { decode(it) ?: return }
        synchronized(shotLock) {
            if (shot != this.shot) return
            backdrop = b
        }
        version.incrementAndGet()
    }

    private fun decode(png: ByteArray): Backdrop? {
        val bmp = runCatching {
            BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = 2 })
        }.getOrNull() ?: run {
            Log.w(TAG, "portal sheet: backdrop of ${png.size} bytes did not decode")
            return null
        }
        val cells = runCatching {
            val side = GlassLuminance.SIDE
            val small = Bitmap.createScaledBitmap(bmp, side, side, true)
            val px = IntArray(side * side)
            small.getPixels(px, 0, side, 0, 0, side, side)
            if (small !== bmp) small.recycle()
            SheetTint.cells(px)
        }.getOrNull()
        return Backdrop(bmp, cells ?: FloatArray(GlassLuminance.SIDE * GlassLuminance.SIDE) { SheetTint.MIDDLING })
    }

    /** The pinned apps, in grid order. Their icons are decoded here, on the caller's thread. */
    fun apps(snapshot: PortalAppsSnapshot) {
        apps = Apps(snapshot, List(snapshot.size) { snapshot.icon(it) })
        version.incrementAndGet()
    }

    /**
     * The guest under the sheet: null for none (no strip), else whether it is on top (the
     * strip offers Panel) or behind our panel (it offers Resume) — see PortalSheet.buttonNames.
     */
    fun guest(onTop: Boolean?) {
        guestOnTop = onTop
        version.incrementAndGet()
    }

    /** The strip's button under a finger (0 to 2), a DPI row's (PortalSheet.dpiPress), or -1 for none. */
    fun press(index: Int) {
        pressed = index
        version.incrementAndGet()
    }

    /** [pkg]'s DPI row open under its icon; null closes it. A package not on the sheet shows no row. */
    fun dpiRow(pkg: String?) {
        dpiPkg = pkg
        version.incrementAndGet()
    }

    /** Every app's DPI as it stands, for the open row's value (PortalSheet.dpiLabel). */
    fun dpiValues(values: Map<String, Int>) {
        dpiValues = values
        version.incrementAndGet()
    }

    /** The rear display's own density, shown for an app without one of its own; null while unknown. */
    fun dpiDefault(dpi: Int?) {
        if (dpiDefault == dpi) return
        dpiDefault = dpi
        version.incrementAndGet()
    }

    /**
     * The panel's theme and Glass settings' style (PanelConfig.theme, .glassStyle): GLASS
     * draws glass, ORIGINAL flat; with a [style] every pane takes its tint, without one the
     * clear glass reacts to the backdrop. Takes effect next frame.
     */
    fun theme(theme: PanelTheme, style: GlassStyle?) {
        look = Look(theme, style)
        version.incrementAndGet()
    }

    /** The panel's face, its weight and size with it (PanelType.face). Takes effect next frame. */
    fun face(f: PanelType.Face) {
        face = f
        version.incrementAndGet()
    }

    /** Draws [r] until it ends, then takes its layers off the rear and lets everything go. */
    private fun render(r: Run) {
        try {
            loop(r)
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            Log.w(TAG, "portal sheet stopped: ${e.message}")
            failedAt = System.nanoTime()
        } finally {
            // Taken out of [end]'s reach first, then let go: end() never touches a released layer.
            val blocker: SurfaceControl?
            val sheet: SurfaceControl?
            synchronized(r) {
                r.live = false
                blocker = r.blocker; sheet = r.sheet
                r.blocker = null; r.sheet = null
            }
            runCatching { r.surface.release() }
            runCatching { r.blockerSurface.release() }
            ShellLayer.remove(sheet)
            ShellLayer.remove(blocker)
        }
    }

    private fun loop(r: Run) {
        val surface = r.surface
        val painter = Renderer()
        var drawn = -1
        // As the frame: if the hardware canvas is refused, the software one (no blur, flat buttons).
        var hardware = true
        try {
            while (!Thread.currentThread().isInterrupted && gen == r.gen) {
                val began = System.nanoTime()
                val v = version.get()
                if (v != drawn) {
                    val canvas = if (hardware) {
                        try { surface.lockHardwareCanvas() } catch (e: Throwable) {
                            Log.w(TAG, "portal sheet: hardware canvas refused (${e.message}); software")
                            hardware = false; continue
                        }
                    } else surface.lockCanvas(null)
                    try {
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        synchronized(DRAW) { painter.draw(canvas) }
                    } finally {
                        surface.unlockCanvasAndPost(canvas)
                    }
                    drawn = v
                }
                val took = (System.nanoTime() - began) / 1_000_000
                Thread.sleep(maxOf(FRAME_MS - took, 1L))
            }
        } finally {
            painter.release()
        }
    }

    /** One run's paints, nodes and painter, so an overlapping run shares nothing mutable of ours. */
    private inner class Renderer {
        private val sheetPainter = PortalSheetPainter(PanelTheme.GLASS, face)
        private val groundNode = RenderNode("portal-sheet-backdrop")
        /** Never recorded: the glass's source when there is no ground this frame. */
        private val none = RenderNode("portal-sheet-none")
        private val shotPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dim = Paint()
        private val full = RectF(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
        private var recordedFor: Backdrop? = null
        private var blurFor = -1f

        /** This frame's, for the pane tint below. */
        private var cells: FloatArray? = null
        private var frameLook = look

        init {
            sheetPainter.paneTint = { rect ->
                SheetTint.of(cells, rect.left, rect.top, rect.right, rect.bottom, frameLook.theme.isGlass, frameLook.style)
            }
        }

        fun draw(canvas: Canvas) {
            val p = progress
            val b = backdrop
            val a = apps
            val onTop = guestOnTop
            val row = dpiPkg
            frameLook = look
            cells = b?.cells
            val ground = PortalPull.guestGround(p)
            val recorder = canvas as? RecordingCanvas
            var bent = none
            if (b != null && ground > 0.004f) {
                if (recorder != null) {
                    if (recordedFor !== b) {
                        groundNode.setPosition(0, 0, Panel.WIDTH, Panel.HEIGHT)
                        val inner = groundNode.beginRecording(Panel.WIDTH, Panel.HEIGHT)
                        try { inner.drawBitmap(b.bitmap, null, full, shotPaint) } finally { groundNode.endRecording() }
                        recordedFor = b
                    }
                    val blur = blurRadius(p)
                    // A new effect only when the radius moves: each is a small object in front
                    // of a large native one (see GlassPainter.lensFor), and a settled sheet
                    // keeps its own.
                    if (blur != blurFor) {
                        groundNode.setRenderEffect(
                            if (blur > 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null,
                        )
                        blurFor = blur
                    }
                    // As a whole, so the ground fades as one picture.
                    groundNode.setAlpha(ground)
                    recorder.drawRenderNode(groundNode)
                    bent = groundNode
                } else {
                    shotPaint.alpha = (255 * ground).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(b.bitmap, null, full, shotPaint)
                    shotPaint.alpha = 255
                }
            }
            dim.color = dimColor(p, ground)
            canvas.drawRect(full, dim)
            sheetPainter.theme = frameLook.theme
            sheetPainter.face = face
            sheetPainter.draw(
                canvas, p, bent, a.icons, a.snapshot.labels,
                guestOnTop = onTop ?: false, withStrip = onTop != null, pressed = pressed,
                dpiApp = row?.let { a.snapshot.packages.indexOf(it) } ?: -1,
                dpiLabel = row?.let { PortalSheet.dpiLabel(dpiValues[it], dpiDefault) }.orEmpty(),
            )
        }

        /** Lets go of what the nodes hold (the screencap's texture among it). */
        fun release() {
            runCatching { groundNode.discardDisplayList() }
            runCatching { none.discardDisplayList() }
            recordedFor = null
        }
    }

    /** One transparent buffer, from the software canvas: no hardware renderer for a buffer posted once. */
    private fun clear(s: Surface) {
        val c = s.lockCanvas(null)
        try { c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) } finally { s.unlockCanvasAndPost(c) }
    }

    /** Hides [blocker] and [sheet] in one transaction; quiet if they are gone. */
    private fun hide(blocker: SurfaceControl?, sheet: SurfaceControl?) {
        if (blocker == null && sheet == null) return
        runCatching {
            SurfaceControl.Transaction().use { t ->
                blocker?.let { t.setVisibility(it, false) }
                sheet?.let { t.setVisibility(it, false) }
                t.apply()
            }
        }.onFailure { Log.w(TAG, "portal sheet: hide failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "AiUsageRear"
        private const val FRAME_MS = 16L
        private const val JOIN_MS = 300L
        /** After a failure, pulls do not try again for this long. */
        private const val RETRY_NS = 5_000_000_000L

        /** The backdrop's blur when fully open, in panel pixels: the control centre's (RearDialView.CC_BLUR). */
        const val BLUR = 22f

        /**
         * The dim over the backdrop when fully open: the control centre's (RearDialView.CC_DIM),
         * repeated here rather than reached for — that one is private to the panel's view.
         */
        const val DIM = 0.25f

        /** Every painter in the process paints under this: they share Glass's shaders. */
        private val DRAW = Any()

        /** The blur at [progress], in half pixels, so a settled sheet asks for no new effect. */
        internal fun blurRadius(progress: Float): Float =
            Math.round(BLUR * progress.coerceIn(0f, 1f) * 2f) / 2f

        /** The dim's colour at [progress] over a ground [ground] in: as the panel's portal dims. */
        internal fun dimColor(progress: Float, ground: Float): Int =
            ((DIM * progress.coerceIn(0f, 1f) * ground.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)) shl 24
    }
}
