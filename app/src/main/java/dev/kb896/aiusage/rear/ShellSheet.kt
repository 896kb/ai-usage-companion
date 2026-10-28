package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import dev.kb896.aiusage.data.GlassStyle
import dev.kb896.aiusage.data.PanelTheme
import dev.kb896.aiusage.data.PortalGrid

/**
 * The App Portal's sheet, drawn by the shell service on a layer of its own on the rear
 * display — over our panel or a guest, locked or not, since nothing is ever brought
 * "above" anything. See specs/2026-10-28-app-portal-shell-sheet-design.md.
 *
 * Its layers, its blocker, its live blur and dim and its render thread are a ShellOverlay's
 * (shared with the control centre over a guest, ShellControlCenter): see there. This is
 * what goes on it — PortalSheetPainter, its glass panes unbacked (there is no picture in
 * this process for them to bend) and tinted as clear glass over a middling ground
 * (SheetTint), or in Glass settings' style — and the state it is drawn from.
 *
 * Setters may be called from any thread; they publish and tell the overlay
 * ([ShellOverlay.changed]).
 *
 * @param layerStack the rear display's layer stack (RearUserService.rearLayerStack).
 * @param face the panel's face, for every word on the sheet — PanelType.face for the
 *   panel's theme and font, so the sheet speaks as the panel does; [face] swaps it when
 *   either changes. The shell (app_process) has NO default typeface — text drawn without
 *   one aborts (gDefaultTypeface == nullptr) — so the caller builds it (see ShellFont.face):
 *   the system's fonts loaded first, then through our Resources, or Departure Mono read
 *   out of our APK when they cannot. Never Typeface.DEFAULT.
 */
class ShellSheet(layerStack: Int, face: PanelType.Face) {

    private class Look(val theme: PanelTheme, val style: GlassStyle?)

    /** The pinned apps with their icons already decoded, so drawing never decodes. */
    private class Apps(val snapshot: PortalAppsSnapshot, val icons: List<Bitmap?>)

    /** Where the sheet stands on its axis (SheetMotion), 0 shut to PortalSheet.REVEALED. */
    @Volatile private var position = 0f
    @Volatile private var apps = Apps(PortalAppsSnapshot.EMPTY, emptyList())
    /** The guest's own icon, for Resume and the slider's row; null shows the Panel glyph on Resume instead. */
    @Volatile private var guestIcon: Bitmap? = null
    /** Null: no guest, so no Panel/Resume · Front · Close buttons and no slider; true on top (Panel), false behind (Resume). */
    @Volatile private var guestOnTop: Boolean? = null
    /** The guest's package, for its DPI on the slider. */
    @Volatile private var guestPkg: String? = null
    @Volatile private var pressed = -1
    @Volatile private var grid = PortalGrid.R3C4
    /** The apps' names under their icons (PanelConfig.portalAppNames); without, bigger icons. */
    @Volatile private var names = true
    /** The page showing, in pages (SheetPager.position). */
    @Volatile private var page = 0f
    /** The DPI the slider's thumb is being dragged to; null while it is not. */
    @Volatile private var sliderDrag: Int? = null
    /** The apps' DPI as the shell has it now (PanelConfig.portalDpi and the back's changes). */
    @Volatile private var dpiValues: Map<String, Int> = emptyMap()
    @Volatile private var dpiDefault: Int? = null
    @Volatile private var look = Look(PanelTheme.GLASS, null)
    @Volatile private var face: PanelType.Face = face

    /** Where the frame being drawn stands, read once per frame ([Drawing.frame]). Render thread only. */
    private var frameAt = 0f

    private val overlay = ShellOverlay(layerStack, "portal-sheet", "portal sheet", Drawing())

    /**
     * Told how far open the sheet is drawn, 0 to 1 (PortalSheet.progress: scrolled to the
     * slider is open), with each frame, and 0 once it has [end]ed — the portal frame fades
     * under it (PortalFrame.sheetOpen). See ShellOverlay.onProgress.
     */
    var onProgress: ((Float) -> Unit)?
        get() = overlay.onProgress
        set(value) { overlay.onProgress = value }

    /** The sheet and its blocker are on the rear: see ShellOverlay.up. */
    val up: Boolean get() = overlay.up

    /**
     * Shows the sheet at [positionStart] — the blocker and the layer up, the render thread
     * started, before returning, so true means they are up. False when it cannot: then there
     * is no sheet and no blocker. Already up: just the position. See ShellOverlay.begin.
     */
    fun begin(positionStart: Float = 0f): Boolean {
        position(positionStart)
        return overlay.begin()
    }

    /** Takes it down (ShellOverlay.end), and forgets the press, the page and a slider drag. */
    fun end() {
        overlay.end()
        pressed = -1
        page = 0f
        sliderDrag = null
        position = 0f
        overlay.changed()
    }

    /** Where it stands on its axis: 0 shut, PortalSheet.OPEN open, past it scrolled to the slider. */
    fun position(p: Float) {
        position = p.coerceIn(0f, PortalSheet.REVEALED)
        overlay.changed()
    }

    /** How many apps a page holds (PanelConfig.portalGrid). */
    fun grid(g: PortalGrid) {
        if (grid == g) return
        grid = g
        overlay.changed()
    }

    /** Whether the apps' names show under their icons. */
    fun names(on: Boolean) {
        if (names == on) return
        names = on
        overlay.changed()
    }

    /** The page showing, in pages: fractional while they move. */
    fun page(position: Float) {
        page = position
        overlay.changed()
    }

    /** The slider's thumb dragged to [dpi]; null when let go (the value then is the app's own). */
    fun sliderDrag(dpi: Int?) {
        sliderDrag = dpi
        overlay.changed()
    }

    /** The pinned apps, in grid order. Their icons are decoded here, on the caller's thread. */
    fun apps(snapshot: PortalAppsSnapshot) {
        apps = Apps(snapshot, List(snapshot.size) { snapshot.icon(it) })
        overlay.changed()
    }

    /**
     * The guest under the sheet, [pkg]: [onTop] null for none (no buttons), else whether it
     * is on top (the buttons offer Panel, and its DPI slider is along the bottom) or behind
     * our panel (they offer Resume) — see PortalSheet.layout.
     */
    fun guest(onTop: Boolean?, pkg: String?) {
        guestOnTop = onTop
        guestPkg = pkg
        overlay.changed()
    }

    /** The guest's icon (any size; drawn at PortalSheet.APP_MARK) for Resume, or null for none. */
    fun guestIcon(icon: Bitmap?) {
        if (guestIcon === icon) return
        guestIcon = icon
        overlay.changed()
    }

    /** The button under a finger (0 to 2), the slider's (PortalSheetPainter.PRESS_VALUE, PRESS_THUMB), or -1 for none. */
    fun press(index: Int) {
        pressed = index
        overlay.changed()
    }

    /** Every app's DPI as it stands, for the guest's slider (PortalSheet.sliderLabel). */
    fun dpiValues(values: Map<String, Int>) {
        dpiValues = values
        overlay.changed()
    }

    /** The rear display's own density, shown for an app without one of its own; null while unknown. */
    fun dpiDefault(dpi: Int?) {
        if (dpiDefault == dpi) return
        dpiDefault = dpi
        overlay.changed()
    }

    /**
     * The panel's theme and Glass settings' style (PanelConfig.theme, .glassStyle): GLASS
     * draws glass, ORIGINAL flat; with a [style] every pane takes its tint, without one
     * clear glass. Takes effect next frame.
     */
    fun theme(theme: PanelTheme, style: GlassStyle?) {
        look = Look(theme, style)
        overlay.changed()
    }

    /** The panel's face, its weight and size with it (PanelType.face). Takes effect next frame. */
    fun face(f: PanelType.Face) {
        face = f
        overlay.changed()
    }

    /** What the overlay draws: the sheet at its position, and the [TIMING_TAG] log. */
    private inner class Drawing : ShellOverlay.Content {
        override fun frame(): Float {
            frameAt = position
            return PortalSheet.progress(frameAt)
        }

        override fun painter(): ShellOverlay.Painter {
            val r = Renderer()
            return ShellOverlay.Painter { canvas, _ -> r.draw(canvas, frameAt); false }
        }

        private var timing: Timing? = null

        override fun drew(gen: Int, sinceBeganMs: Long, progress: Float) {
            val t = timing?.takeIf { it.gen == gen } ?: Timing(gen).also { timing = it }
            t.drew(sinceBeganMs, frameAt, guestOnTop != null)
        }
    }

    /**
     * The [TIMING_TAG] log, one run's worth: when its first frame was posted, when its contents
     * first showed (Arrival past nothing) and whether the guest's buttons were among them —
     * and, if they came later, when. A few lines per opening, for the "buttons come late"
     * report: `adb logcat -s PortalSheetTiming`.
     */
    private class Timing(val gen: Int) {
        private var first = false
        private var contentsAt = -1L
        private var buttonsAt = -1L

        fun drew(ms: Long, at: Float, buttons: Boolean) {
            if (!first) { first = true; Log.i(TIMING_TAG, "sheet $gen: first frame +$ms ms, guest buttons ${if (buttons) "in" else "out"}") }
            val showing = Arrival.of(PortalSheet.progress(at)) > 0.004f
            if (showing && contentsAt < 0) {
                contentsAt = ms
                Log.i(TIMING_TAG, "sheet $gen: contents show +$ms ms, guest buttons ${if (buttons) "with them" else "not there"}")
            }
            if (showing && buttons && buttonsAt < 0) {
                buttonsAt = ms
                if (ms > contentsAt) Log.i(TIMING_TAG, "sheet $gen: guest buttons LATE +$ms ms, ${ms - contentsAt} ms after the contents")
            }
        }
    }

    /** One run's paints and painter, so an overlapping run shares nothing mutable of ours. */
    private inner class Renderer {
        private val sheetPainter = PortalSheetPainter(PanelTheme.GLASS, face)
        private var frameLook = look
        /** The last layout and what it was made for, so a frame does not lay the sheet out again. */
        private var laid: PortalSheet.Layout? = null
        private var laidFor: Triple<PortalGrid, Int, Boolean?>? = null

        init {
            // No picture of what is under the sheet to measure: clear glass over a middling
            // ground, or the chosen style.
            sheetPainter.paneTint = { SheetTint.of(frameLook.theme.isGlass, frameLook.style) }
        }

        /** The sheet at [at] on its axis (read once per frame, so the blur goes with what is drawn). */
        fun draw(canvas: Canvas, at: Float) {
            val a = apps
            val onTop = guestOnTop
            frameLook = look
            val key = Triple(grid, a.snapshot.size, onTop)
            val layout = laid?.takeIf { laidFor == key } ?: PortalSheet.layout(key.first, key.second, key.third).also { laid = it; laidFor = key }
            val p = PortalSheet.progress(at)
            val slider = if (onTop != null) {
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
                canvas, p, PortalSheet.scroll(at), layout, page, a.icons, a.snapshot.labels,
                guestOnTop = onTop ?: false, pressed = pressed,
                guestIcon = guestIcon, slider = slider, names = names,
            )
        }
    }

    companion object {
        /** The sheet's opening times (Timing), apart from the rest so they can be read alone. */
        private const val TIMING_TAG = "PortalSheetTiming"
    }
}
