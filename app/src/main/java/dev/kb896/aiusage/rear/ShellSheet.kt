package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
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
 * @param rear the rear display as it is: the sheet's layers are its size, its content in panel units stretched to it (ShellOverlay).
 * @param face the panel's face, for every word on the sheet — PanelType.face for the
 *   panel's theme and font, so the sheet speaks as the panel does; [face] swaps it when
 *   either changes. The shell (app_process) has NO default typeface — text drawn without
 *   one aborts (gDefaultTypeface == nullptr) — so the caller builds it (see ShellFont.face):
 *   the system's fonts loaded first, then through our Resources, or Departure Mono read
 *   out of our APK when they cannot. Never Typeface.DEFAULT.
 */
class ShellSheet(layerStack: Int, private val rear: RearGeometry, face: PanelType.Face) {

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
    /** Every app's width as it stands (GuestWidth), for the guest's width slider. */
    @Volatile private var widthValues: Map<String, Int> = emptyMap()
    /** The width being dragged to: null while not dragged, else [Dragged.width] (null: the whole). */
    @Volatile private var widthDrag: Dragged? = null
    /** The portal frame's colour, for the width's preview. */
    @Volatile private var frameColor = 0xFFB14CFF.toInt()
    /** When the width's preview last turned on or off, and how much of it showed then. */
    @Volatile private var previewOn = false
    @Volatile private var previewAt = 0L
    @Volatile private var previewFrom = 0f
    /** The back's lock, shown in place of the apps while the phone is locked (BackLock); null: none. */
    @Volatile private var lockLook: PortalSheetPainter.LockLook? = null
    /** The guest is kept right of the cameras: its switch under the slider. */
    @Volatile private var keepRight = false
    /** No such switch for this open: the app runs nowhere else with the front locked (LockRetry). */
    @Volatile private var keepRightHidden = false
    @Volatile private var look = Look(PanelTheme.GLASS, null)
    @Volatile private var face: PanelType.Face = face

    /** Where the frame being drawn stands, read once per frame ([Drawing.frame]). Render thread only. */
    private var frameAt = 0f

    /** Where the rear's lenses end, in panel units, as this sheet lays itself out (PortalSheet.layout). */
    val lensRight: Float get() = rear.cutoutUnits

    private val overlay = ShellOverlay(layerStack, rear, "portal-sheet", "portal sheet", Drawing())

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
        shownAt.snap(positionStart, android.os.SystemClock.uptimeMillis())
        return overlay.begin()
    }

    /** Takes it down (ShellOverlay.end), and forgets the press, the page and a slider drag. */
    fun end() {
        overlay.end()
        pressed = -1
        page = 0f
        widthDrag = null
        lockLook = null
        fadingLock = null
        previewOn = false
        previewFrom = 0f
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

    /** The apps as a dome (PanelConfig.portalDome), not the grid. */
    fun dome(on: Boolean) {
        if (domeOn == on) return
        domeOn = on
        overlay.changed()
    }

    /** Where the dome is turned to. */
    fun domeAt(pose: PortalDome.Pose) {
        domePose = pose
        overlay.changed()
    }

    @Volatile private var domeOn = false
    @Volatile private var domePose = PortalDome.Pose(0f, 0f)

    /** The slider's thumb dragged to [dpi]; null when let go (the value then is the app's own). */
    fun sliderDrag(dpi: Int?) {
        sliderDrag = dpi
        overlay.changed()
    }

    /** The lock shown in place of the apps, as [look] has it; null to show the apps. */
    fun lock(look: PortalSheetPainter.LockLook?) {
        lockLook = look
        overlay.changed()
    }

    /** The lock as it was when it began to fade ([unlockFade]), and when; null when not fading. */
    @Volatile private var fadingLock: PortalSheetPainter.LockLook? = null
    @Volatile private var fadeAt = 0L

    /** The lock fading away over [UNLOCK_FADE_MS], what it gated coming in under it: the phone was unlocked. */
    fun unlockFade() {
        fadingLock = lockLook ?: return
        fadeAt = android.os.SystemClock.uptimeMillis()
        lockLook = null
        overlay.changed()
    }

    /** A width being dragged to; [width] null is the whole. */
    class Dragged(val width: Int?)

    /**
     * The width slider's thumb dragged to [width] (null: the whole), or let go ([dragging]
     * false). While dragged the portal frame's line runs round the box the app will have,
     * fading in — whatever the Portal border switch says, since it shows the width, not the
     * frame — and fades out again when let go.
     */
    fun widthDrag(width: Int?, dragging: Boolean) {
        val was = widthDrag != null
        widthDrag = if (dragging) Dragged(width) else null
        if (dragging != was) {
            val now = android.os.SystemClock.uptimeMillis()
            previewFrom = previewLevel(now)
            previewAt = now
            previewOn = dragging
        }
        overlay.changed()
    }

    /** Every app's width as it stands, for the guest's width slider. */
    fun widthValues(values: Map<String, Int>) {
        widthValues = values
        overlay.changed()
    }

    /** The portal frame's colour (PanelConfig.portalFrameColor), for the width's preview. */
    fun frameColor(color: Int) {
        frameColor = color
    }

    /** How much of the width's preview shows at [now]: easing towards on or off over [PREVIEW_FADE_MS]. */
    private fun previewLevel(now: Long): Float {
        val t = ((now - previewAt) / PREVIEW_FADE_MS.toFloat()).coerceIn(0f, 1f)
        return if (previewOn) previewFrom + (1f - previewFrom) * t else previewFrom * (1f - t)
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

    /** Whether the guest's "Keep right of the cameras" switch is on, and whether it is offered at all. */
    fun keepRight(on: Boolean, hidden: Boolean = false) {
        if (keepRight == on && keepRightHidden == hidden) return
        keepRight = on
        keepRightHidden = hidden
        overlay.changed()
    }

    /** The density shown for an app without one of its own (the rear's, or a private display's default); null while unknown. */
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

    private companion object {
        /** The width's preview fading in or out. */
        const val PREVIEW_FADE_MS = 220L
        /** The lock leaving when its code is given, or the phone is unlocked under it. */
        const val UNLOCK_FADE_MS = PortalSheetPainter.UNLOCK_MS
    }

    /**
     * Where the sheet is drawn: its position, but no faster than ControlCenter.DRAG_SPEED_MAX — a
     * fast pull or flick trails and catches up rather than the sheet appearing at once. Render thread only.
     */
    private val shownAt = ControlCenter.Chase()

    /** What the overlay draws: the sheet at its position. */
    private inner class Drawing : ShellOverlay.Content {
        override fun frame(): Float {
            frameAt = shownAt.to(position, android.os.SystemClock.uptimeMillis())
            return PortalSheet.progress(frameAt)
        }

        override fun painter(): ShellOverlay.Painter {
            val r = Renderer()
            // Another frame while it is still catching up with where it is meant to be.
            return ShellOverlay.Painter { canvas, _ -> r.draw(canvas, frameAt) || frameAt != position }
        }
    }

    /** One run's paints and painter, so an overlapping run shares nothing mutable of ours. */
    private inner class Renderer {
        private val sheetPainter = PortalSheetPainter(PanelTheme.GLASS, face)
        /** The portal frame's own drawing, at the sheet's size, for the width's preview. */
        private val border = PortalFrame.Renderer(PortalFrame.cssPx(rear) / rear.fitX)
        private val inUnits = rear.inPanelUnits()
        private val keypad = PortalSheet.keypad(rear.cutoutUnits, rear.fitY / rear.fitX)
        private val dots = PortalSheet.patternGrid(rear.cutoutUnits, rear.fitY / rear.fitX)
        private val keyboards = HashMap<Int, PortalSheet.Keyboard>()
        private var previewFor: Int? = -1
        private var previewShapes: List<ElectricBorder.Shape> = emptyList()
        private var frameLook = look
        /** The last layout and what it was made for, so a frame does not lay the sheet out again. */
        private var laid: PortalSheet.Layout? = null
        private var laidFor: Triple<PortalGrid, Int, Boolean?>? = null

        init {
            // No picture of what is under the sheet to measure: clear glass over a middling
            // ground, or the chosen style.
            sheetPainter.paneTint = { SheetTint.of(frameLook.theme.isGlass, frameLook.style) }
        }

        /** The sheet at [at] on its axis (read once per frame, so the blur goes with what is drawn). True: another frame, for the preview. */
        fun draw(canvas: Canvas, at: Float): Boolean {
            val a = apps
            val onTop = guestOnTop
            frameLook = look
            val key = Triple(grid, a.snapshot.size, onTop)
            val layout = laid?.takeIf { laidFor == key } ?: PortalSheet.layout(key.first, key.second, key.third, rear.cutoutUnits).also { laid = it; laidFor = key }
            val p = PortalSheet.progress(at)
            val slider = if (onTop != null) {
                val own = guestPkg?.let { dpiValues[it] }
                val dragged = sliderDrag
                // The display's own as read, else this rear's as its geometry says.
                val thumb = dragged ?: own ?: dpiDefault ?: rear.dpi
                val fraction = layout.slider?.track?.let { (PortalSheet.sliderX(it, thumb) - it.left) / it.width } ?: 0f
                PortalSheetPainter.SliderLook(fraction, PortalSheet.sliderLabel(dragged, own))
            } else null
            val dragging = widthDrag
            val shownWidth = if (dragging != null) dragging.width else guestPkg?.let { widthValues[it] }
            val width = if (onTop != null) {
                PortalSheetPainter.SliderLook(GuestWidth.fraction(rear, shownWidth), GuestWidth.label(rear, shownWidth))
            } else null
            sheetPainter.theme = frameLook.theme
            sheetPainter.face = face
            sheetPainter.dome = domePose.takeIf { domeOn }
            // The lock fading out over what it gated, fading in (unlockFade).
            val fading = fadingLock
            val fadeT = if (fading == null) 1f else ((android.os.SystemClock.uptimeMillis() - fadeAt) / UNLOCK_FADE_MS.toFloat()).coerceIn(0f, 1f)
            if (fading != null && fadeT >= 1f) fadingLock = null
            // What it gated comes in only as the pill goes (PortalSheetPainter.drawUnlock).
            val shown = if (fading == null) 1f else ((fadeT - PortalSheetPainter.UNLOCK_GOING) / (1f - PortalSheetPainter.UNLOCK_GOING)).coerceIn(0f, 1f)
            val inLayer = if (fading != null && fadeT < 1f) canvas.saveLayerAlpha(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), (255 * shown).toInt()) else -1
            sheetPainter.draw(
                canvas, p, PortalSheet.scroll(at), layout, page, a.icons, a.snapshot.labels,
                guestOnTop = onTop ?: false, pressed = pressed,
                guestIcon = guestIcon, slider = slider, names = names, width = width,
                keepRight = keepRight, keepRightHidden = keepRightHidden,
                lock = lockLook?.let { l -> if (pressed >= PortalSheetPainter.PRESS_KEY) l.pressing(pressed - PortalSheetPainter.PRESS_KEY) else l },
                keypad = keypad, grid = dots,
                keyboard = lockLook?.takeIf { it.kind == BackLock.Kind.PASSWORD }?.let { keyboards.getOrPut(it.page) { PortalSheet.keyboard(rear.cutoutUnits, it.page) } },
            )
            if (inLayer >= 0) canvas.restoreToCount(inLayer)
            // The lock leaving, over it: its glass run together into a pill, the tick, and gone.
            if (fading != null && fadeT < 1f) {
                sheetPainter.draw(
                    canvas, p, PortalSheet.scroll(at), layout, page, a.icons, a.snapshot.labels,
                    guestOnTop = onTop ?: false, lock = fading.unlocking(fadeT), keypad = keypad, grid = dots,
                    keyboard = fading.takeIf { it.kind == BackLock.Kind.PASSWORD }?.let { keyboards.getOrPut(it.page) { PortalSheet.keyboard(rear.cutoutUnits, it.page) } },
                )
            }
            return drawPreview(canvas, shownWidth, p) || (fading != null && fadeT < 1f)
        }

        /**
         * The width's preview: the portal frame's line round the box an app [w] wide stands in,
         * as much of it as [previewLevel] says, over the sheet. True while it shows or fades.
         */
        private fun drawPreview(canvas: Canvas, w: Int?, progress: Float): Boolean {
            val now = android.os.SystemClock.uptimeMillis()
            val level = previewLevel(now) * progress
            if (level <= 0.004f) return previewOn
            val stepped = GuestWidth.of(rear, w)
            if (previewFor != stepped) {
                val a = GuestWidth.area(rear, w)
                val box = GuestShape.Bounds(
                    Math.round(a.left / rear.fitX), Math.round(a.top / rear.fitY),
                    Math.round(a.right / rear.fitX), Math.round(a.bottom / rear.fitY),
                )
                previewShapes = PortalFrame.shapesFor(inUnits, box)
                previewFor = stepped
            }
            val layer = canvas.saveLayerAlpha(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), (255 * level).toInt())
            border.draw(canvas, previewShapes, frameColor, now / 1000f)
            canvas.restoreToCount(layer)
            return true
        }
    }
}
