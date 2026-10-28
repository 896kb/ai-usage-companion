package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.AnimationUtils
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PortalGrid
import dev.kb896.aiusage.rear.PanelType
import dev.kb896.aiusage.rear.PortalFrame
import dev.kb896.aiusage.rear.PortalSheet
import dev.kb896.aiusage.rear.PortalSheetPainter
import dev.kb896.aiusage.rear.SheetTint
import dev.kb896.aiusage.rear.ShellOverlay

/**
 * The App Portal's sheet as the back shows it, over the preview's panel while the Portal
 * tab is chosen (PanelPreviewView.portal): the sheet's dim, the pinned apps fully open on
 * their first page, and the portal border over them.
 *
 * DRAWN BY THE SHELL'S OWN CODE — PortalSheetPainter for the sheet, PortalFrame's Renderer
 * for the border — for the reason PanelPreviewView holds a real RearDialView: a second
 * drawing of it would drift. The blur under it is the preview's (a RenderEffect on the
 * panel), as SurfaceFlinger's is under the shell's layer.
 *
 * No guest: the front has none to show, so no buttons and no slider. The border animates
 * as the frame does on the back, a frame at a time while it is shown.
 *
 * Laid out at the panel's 904x572 and scaled with it; everything is in panel pixels. It
 * draws on the main thread, as the preview's panel does — the painters there share Glass's
 * shaders one at a time.
 */
internal class PortalSheetPreview(context: Context) : View(context) {

    /** The panel's config: the grid, the names, the theme, the font, the glass, the border and its colour. */
    var config: PanelConfig = PanelConfig.DEFAULT
        set(value) {
            field = value
            painter.theme = value.theme
            painter.face = PanelType.face(resources, value.theme, value.font)
            invalidate()
        }

    private var labels: List<String> = emptyList()
    private var icons: List<Bitmap?> = emptyList()

    /** The pinned apps as the back has them (PortalAppsLoader.shown), in grid order. */
    fun apps(labels: List<String>, icons: List<Bitmap?>) {
        this.labels = labels
        this.icons = icons
        invalidate()
    }

    private val painter = PortalSheetPainter(config.theme, PanelType.face(resources, config.theme, config.font)).apply {
        // As the shell's: no picture behind to react to, so clear glass over a middling ground.
        paneTint = { SheetTint.of(config.theme.isGlass, config.glassStyle) }
    }
    private val border = PortalFrame.Renderer()
    private val shapes = PortalFrame.shapesFor()
    private val dim = Paint().apply { color = ShellOverlay.dimColor(1f) }
    /** The border's clock starts with the view; only its motion shows, not where it began. */
    private val born = AnimationUtils.currentAnimationTimeMillis()

    /** The last layout and what it was made for, so a frame of the border does not lay the sheet out again. */
    private var laid: PortalSheet.Layout? = null
    private var laidFor: Pair<PortalGrid, Int>? = null

    override fun onDraw(canvas: Canvas) {
        val w = Panel.WIDTH.toFloat(); val h = Panel.HEIGHT.toFloat()
        canvas.drawRect(0f, 0f, w, h, dim)
        val key = config.portalGrid to labels.size
        val layout = laid?.takeIf { laidFor == key }
            ?: PortalSheet.layout(key.first, key.second, guestOnTop = null).also { laid = it; laidFor = key }
        painter.draw(
            canvas, progress = 1f, scroll = 0f, layout = layout, page = 0f, icons = icons, labels = labels,
            guestOnTop = false, names = config.portalAppNames,
        )
        if (!config.portalBorder) return
        // At the frame's opacity as one picture, as its layer is on the back.
        val saved = canvas.saveLayerAlpha(0f, 0f, w, h, Math.round(255 * PortalFrame.ALPHA))
        try {
            border.draw(canvas, shapes, config.portalFrameColor, (AnimationUtils.currentAnimationTimeMillis() - born) / 1000f)
        } finally {
            canvas.restoreToCount(saved)
        }
        postInvalidateOnAnimation()
    }
}
