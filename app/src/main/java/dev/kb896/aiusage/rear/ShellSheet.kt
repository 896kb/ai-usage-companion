package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.GlassStyle
import dev.kb896.aiusage.data.PanelTheme
import dev.kb896.aiusage.data.PortalGrid
import java.util.concurrent.atomic.AtomicInteger

/**
 * The App Portal's sheet, drawn by the shell service on a layer of its own on the rear
 * display — over our panel or a guest, locked or not, since nothing is ever brought
 * "above" anything. See specs/2026-10-28-app-portal-shell-sheet-design.md.
 *
 * TWO LAYERS, made with ShellLayer:
 * - the SHEET ([ShellLayer.Z_SHEET], under the frame), a trusted overlay — fail closed, as
 *   the frame: if it cannot be trusted nothing is shown — drawn through
 *   Surface.lockHardwareCanvas (a RecordingCanvas, measured on the 17 Pro), and asking
 *   SurfaceFlinger to blur whatever is under it ([blurPx]);
 * - the BLOCKER ([ShellLayer.Z_BLOCKER], under the sheet), full screen, one fully
 *   transparent buffer, and NOT trusted. Its whole job is Android's untrusted-touch
 *   occlusion: while it is up, every touch to the windows below — the guest, our panel —
 *   is dropped, so a tap on Close cannot also tap the app. The touches themselves are
 *   read off the raw touchscreen by the service.
 * Both appear in one transaction and go in one transaction: a blocker without its sheet
 * would be an invisible wall over the rear, so there is never one without the other.
 *
 * THE BLUR IS SURFACEFLINGER'S, OF THE LIVE REAR. The sheet layer's background blur
 * radius (the hidden Transaction.setBackgroundBlurRadius) comes in as the sheet rises,
 * [blurPx] — so whatever is under it, the guest or our panel, is blurred as it is NOW,
 * every frame. It used to be a screencap of the rear (~240 ms) taken as the finger landed:
 * on a fast pull it came late or stale, and the sheet blocked the picture instead of
 * blurring it. Measured on the 17 Pro (ro.surface_flinger.supports_background_blur=1): a
 * shell layer with a translucent buffer blurs what is under it, smoothly ramped 0→80 over
 * 61 frames. Where the call is missing or refused, the sheet is drawn with the dim alone,
 * and that is logged once.
 *
 * ONLY THE SHEET IS BLURRED. The layer is cropped (Transaction.setCrop) to the sheet, from
 * its top edge down — [cropTop] — its top corners rounded where the firmware lets the
 * layer have a corner radius (the hidden setCornerRadius) — and the crop follows the sheet
 * every frame with the blur. SurfaceFlinger blurs only within a layer's bounds, so in
 * PEEK only the strip is blurred and the guest above it stays sharp. If the crop is
 * refused, the blur goes too (logged once): uncropped, it would blur the whole rear.
 *
 * WHAT IS DRAWN, per frame: cleared; the dim over the sheet (from its top edge down, the
 * blur's ground); then PortalSheetPainter, its glass panes unbacked — there is no
 * picture in this process for them to bend — and tinted as clear glass over a middling
 * ground (SheetTint with no cells), or in Glass settings' style.
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

    /** How high the sheet stands, 0 to PortalSheet.FULL. */
    @Volatile private var height = 0f
    @Volatile private var apps = Apps(PortalAppsSnapshot.EMPTY, emptyList())
    /** The guest's own icon, for Resume and the slider's strip; null shows the Panel glyph on Resume instead. */
    @Volatile private var guestIcon: Bitmap? = null
    /** Null: no guest, so no Panel/Resume · Front · Close buttons; true: on top, so the slider's strip too. */
    @Volatile private var guestOnTop: Boolean? = null
    /** The guest's package, for its DPI on the slider. */
    @Volatile private var guestPkg: String? = null
    @Volatile private var pressed = -1
    @Volatile private var grid = PortalGrid.R3C4
    /** The page showing, in pages (SheetPager.position). */
    @Volatile private var page = 0f
    /** The DPI the slider's thumb is being dragged to; null while it is not. */
    @Volatile private var sliderDrag: Int? = null
    /** The apps' DPI as the shell has it now (PanelConfig.portalDpi and the back's changes). */
    @Volatile private var dpiValues: Map<String, Int> = emptyMap()
    @Volatile private var dpiDefault: Int? = null
    @Volatile private var look = Look(PanelTheme.GLASS, null)
    @Volatile private var face: PanelType.Face = face
    private val version = AtomicInteger()

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
     * Makes the blocker and the sheet, shows both in one transaction at [heightStart],
     * and starts the render thread — all before returning, so true means they are up.
     * False when it cannot (no trusted overlay, no layer, the blocker's buffer refused, or
     * any of these in the last [RETRY_NS]); then there is no sheet and no blocker and the
     * caller must not act as if one were up. Already up: just the height.
     */
    @Synchronized
    fun begin(heightStart: Float = 0f): Boolean {
        height(heightStart)
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
                // Cropped to the sheet from the first frame: the blur must never cover the rear.
                t.setCrop(sheet, Rect(0, cropTop(height), Panel.WIDTH, Panel.HEIGHT))
                cornerRadius?.let { call -> runCatching { call.invoke(t, sheet, CORNER) } }
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
     * the render thread, and forgets the press, the page and a slider drag.
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
        pressed = -1
        page = 0f
        sliderDrag = null
        height = 0f
        version.incrementAndGet()
    }

    /** How high it stands, 0 shut to PortalSheet.FULL. */
    fun height(h: Float) {
        height = h.coerceIn(0f, PortalSheet.FULL)
        version.incrementAndGet()
    }

    /** How many apps a page holds (PanelConfig.portalGrid). */
    fun grid(g: PortalGrid) {
        if (grid == g) return
        grid = g
        version.incrementAndGet()
    }

    /** The page showing, in pages: fractional while they move. */
    fun page(position: Float) {
        page = position
        version.incrementAndGet()
    }

    /** The slider's thumb dragged to [dpi]; null when let go (the value then is the app's own). */
    fun sliderDrag(dpi: Int?) {
        sliderDrag = dpi
        version.incrementAndGet()
    }

    /** The pinned apps, in grid order. Their icons are decoded here, on the caller's thread. */
    fun apps(snapshot: PortalAppsSnapshot) {
        apps = Apps(snapshot, List(snapshot.size) { snapshot.icon(it) })
        version.incrementAndGet()
    }

    /**
     * The guest under the sheet, [pkg]: [onTop] null for none (no buttons), else whether it
     * is on top (the buttons offer Panel, and its DPI slider is along the bottom) or behind
     * our panel (they offer Resume) — see PortalSheet.layout.
     */
    fun guest(onTop: Boolean?, pkg: String?) {
        guestOnTop = onTop
        guestPkg = pkg
        version.incrementAndGet()
    }

    /** The guest's icon (any size; drawn at PortalSheet.APP_MARK) for Resume, or null for none. */
    fun guestIcon(icon: Bitmap?) {
        if (guestIcon === icon) return
        guestIcon = icon
        version.incrementAndGet()
    }

    /** The button under a finger (0 to 2), the slider's (PortalSheetPainter.PRESS_VALUE, PRESS_THUMB), or -1 for none. */
    fun press(index: Int) {
        pressed = index
        version.incrementAndGet()
    }

    /** Every app's DPI as it stands, for the guest's slider (PortalSheet.sliderLabel). */
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
     * draws glass, ORIGINAL flat; with a [style] every pane takes its tint, without one
     * clear glass. Takes effect next frame.
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
        // As the frame: if the hardware canvas is refused, the software one (flat buttons).
        var hardware = true
        var placed: Pair<Int, Int>? = null
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
                val h = height
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    synchronized(DRAW) { painter.draw(canvas, h) }
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                // With the frame it goes with, and only when it moves.
                val at = cropTop(h) to blurPx(ground(h))
                if (at != placed && place(r, at.first, at.second)) placed = at
                drawn = v
            }
            val took = (System.nanoTime() - began) / 1_000_000
            Thread.sleep(maxOf(FRAME_MS - took, 1L))
        }
    }

    /** One run's paints and painter, so an overlapping run shares nothing mutable of ours. */
    private inner class Renderer {
        private val sheetPainter = PortalSheetPainter(PanelTheme.GLASS, face)
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ground = Path()
        private val groundRect = RectF()
        private var frameLook = look
        /** The last layout and what it was made for, so a frame does not lay the sheet out again. */
        private var laid: PortalSheet.Layout? = null
        private var laidFor: Triple<PortalGrid, Int, Boolean?>? = null

        init {
            // No picture of what is under the sheet to measure: clear glass over a middling
            // ground, or the chosen style.
            sheetPainter.paneTint = { rect ->
                SheetTint.of(null, rect.left, rect.top, rect.right, rect.bottom, frameLook.theme.isGlass, frameLook.style)
            }
        }

        /** The sheet at height [h] (read once by the loop, so the crop and blur go with what is drawn). */
        fun draw(canvas: Canvas, h: Float) {
            val a = apps
            val onTop = guestOnTop
            frameLook = look
            val key = Triple(grid, a.snapshot.size, onTop)
            val layout = laid?.takeIf { laidFor == key } ?: PortalSheet.layout(key.first, key.second, key.third).also { laid = it; laidFor = key }
            // Over the sheet, from its top edge down: SurfaceFlinger blurs under it, and the dim is its ground.
            val top = Panel.HEIGHT - h
            if (h > 0.5f) {
                groundRect.set(0f, top, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
                ground.rewind()
                ground.addRoundRect(groundRect, floatArrayOf(CORNER, CORNER, CORNER, CORNER, 0f, 0f, 0f, 0f), Path.Direction.CW)
                dim.color = dimColor(ground(h))
                canvas.drawPath(ground, dim)
            }
            val slider = if (onTop == true) {
                val own = guestPkg?.let { dpiValues[it] }
                val dragged = sliderDrag
                PortalSheetPainter.SliderLook(
                    thumbDpi = dragged ?: own ?: dpiDefault ?: DensityDump.FALLBACK,
                    label = PortalSheet.sliderLabel(dragged, own),
                )
            } else null
            sheetPainter.theme = frameLook.theme
            sheetPainter.face = face
            sheetPainter.draw(
                canvas, h, layout, page, null, a.icons, a.snapshot.labels,
                guestOnTop = onTop ?: false, pressed = pressed,
                guestIcon = guestIcon, slider = slider,
            )
        }
    }

    /**
     * [r]'s sheet layer cropped to the sheet from [top] down, and its background blur at
     * [radius] SurfaceFlinger px, in one transaction. True when done; false when the layer
     * is gone. A refused blur is never tried again (logged once): the sheet keeps its dim
     * and goes without. A refused crop takes the blur with it — uncropped, the blur would
     * cover the whole rear, the guest above the strip too — and is logged once.
     */
    private fun place(r: Run, top: Int, radius: Int): Boolean = synchronized(r) {
        val sheet = r.sheet ?: return@synchronized false
        SurfaceControl.Transaction().use { t ->
            if (!cropBroken) {
                try {
                    t.setCrop(sheet, Rect(0, top, Panel.WIDTH, Panel.HEIGHT))
                } catch (e: Throwable) {
                    cropBroken = true
                    Log.w(TAG, "portal sheet: no crop (${e.javaClass.simpleName}: ${e.message}); no blur either, the dim alone")
                }
            }
            val call = backgroundBlur
            if (call != null && !blurBroken) {
                try {
                    // Without a crop, none: a blur once set is taken back.
                    call.invoke(t, sheet, if (cropBroken) 0 else radius)
                } catch (e: Throwable) {
                    val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                    blurBroken = true
                    Log.w(TAG, "portal sheet: no background blur (${cause.javaClass.simpleName}: ${cause.message}); the dim alone")
                }
            }
            runCatching { t.apply() }.onFailure { Log.w(TAG, "portal sheet: placing failed: ${it.message}") }
        }
        true
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

        /**
         * SurfaceFlinger's blur radius under the sheet when fully open, in its px. Meant to
         * read as the control centre's 22-unit RenderEffect blur over the panel
         * (RearDialView.CC_BLUR), but the two are not the same scale: picked from the
         * spike's 0–80 ramp on the phone, and the number to tune by eye.
         */
        const val BLUR_MAX = 64

        /**
         * The dim over the blurred rear when fully open: the control centre's (RearDialView.CC_DIM),
         * repeated here rather than reached for — that one is private to the panel's view.
         */
        const val DIM = 0.25f

        /** Every painter in the process paints under this: they share Glass's shaders. */
        private val DRAW = Any()

        /** The sheet's top corners' radius, in panel px. */
        const val CORNER = 28f

        /**
         * How much of the sheet's ground — its blur and its dim — is in at height [h], 0 to
         * 1: all of it once the strip is (PortalSheet.PEEK), so the strip alone is blurred
         * and dimmed as the full sheet is, and the crop does the rest.
         */
        internal fun ground(h: Float): Float = (h / PortalSheet.PEEK).coerceIn(0f, 1f)

        /** The layer's crop's top at height [h]: the sheet's top edge, in whole px. */
        internal fun cropTop(h: Float): Int = Math.round(Panel.HEIGHT - h.coerceIn(0f, PortalSheet.FULL))

        /** The blur at [ground] (0 to 1), in whole SurfaceFlinger px, so a settled sheet sends no new transaction. */
        internal fun blurPx(ground: Float): Int = Math.round(BLUR_MAX * ground.coerceIn(0f, 1f))

        /** The dim's colour at [ground]: as the control centre's, coming in with the pull. */
        internal fun dimColor(ground: Float): Int =
            ((DIM * ground.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)) shl 24

        /**
         * The hidden Transaction.setBackgroundBlurRadius(SurfaceControl, int), looked up once
         * as ShellLayer looks up setLayerStack; null where the firmware has none (logged once).
         */
        private val backgroundBlur: java.lang.reflect.Method? by lazy {
            runCatching {
                SurfaceControl.Transaction::class.java
                    .getMethod("setBackgroundBlurRadius", SurfaceControl::class.java, Int::class.javaPrimitiveType)
            }.onFailure { Log.w(TAG, "portal sheet: no setBackgroundBlurRadius on this firmware; the dim alone") }.getOrNull()
        }

        /**
         * The hidden Transaction.setCornerRadius(SurfaceControl, float), for the sheet's
         * rounded top; null where the firmware has none — square corners then, logged once.
         */
        private val cornerRadius: java.lang.reflect.Method? by lazy {
            runCatching {
                SurfaceControl.Transaction::class.java
                    .getMethod("setCornerRadius", SurfaceControl::class.java, Float::class.javaPrimitiveType)
            }.onFailure { Log.w(TAG, "portal sheet: no setCornerRadius on this firmware; square corners") }.getOrNull()
        }

        /** The call threw once: no more tries in this process. */
        @Volatile private var blurBroken = false
        /** setCrop threw once: no crop, and so no blur, in this process. */
        @Volatile private var cropBroken = false
    }
}
