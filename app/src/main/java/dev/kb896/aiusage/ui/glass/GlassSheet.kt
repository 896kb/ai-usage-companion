package dev.kb896.aiusage.ui.glass

import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.doOnLayout
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.Glass

/**
 * A dialog as a sheet of glass, ported from catalog/destinations/DialogContent.kt
 * (Apache 2.0): a 48dp corner, the card tint, and the catalog scrim behind it.
 *
 * The sheet is the background of the framework dialog's own panel rather than a root of
 * ours, so Modal's builder API and the framework's title, message and button layout are
 * untouched. The panel is the one child of android.R.id.content.
 */
class GlassSheetDrawable : Drawable() {
    var luminance = Pane.FALLBACK_LUMINANCE; private set

    override fun draw(canvas: Canvas) {
        // The panel is the callback: a Drawable set as a view's background gets that
        // view wired as its callback automatically, and that view is the one whose
        // screen position and density the pane needs to sample the wallpaper.
        val host = callback as? View ?: return
        val d = host.resources.displayMetrics.density
        luminance = Pane.draw(canvas, host, bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat(), 48 * d, Glass.CARD_TINT)
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

object GlassSheet {
    /** Turn a shown dialog to glass. False, and nothing done, under ORIGINAL. */
    fun dress(dialog: AlertDialog, danger: Boolean): Boolean {
        if (!UiTheme.isGlass(dialog.context)) return false
        val window = dialog.window ?: return false
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val panel = content.getChildAt(0) ?: return false
        // The scrim dims the whole screen behind the dialog, not just the panel's
        // footprint, so it reads the wallpaper's overall mean rather than the narrower
        // per-cell sample doOnLayout below takes for the panel's own text.
        val mean = Backdrop.meanLuminance()
        window.setDimAmount(Ink.scrimAlpha(mean))
        // FROSTED, the way the bottom bar is: the screen behind the dialog, drawn into the
        // sheet at the sheet's place and blurred, under a tint and a light rim. Drawn by
        // the sheet itself rather than asked of the system — the system's cross-window
        // blur left the sheet a clear tint on this phone. No activity to read: the
        // wallpaper glass, as before.
        val behind = activityContent(dialog.context)
        panel.background = if (behind != null) FrostDrawable(behind) else GlassSheetDrawable()
        // After layout, not before: the panel has no size yet when dress() runs, and a
        // pane drawn at zero size returns the fallback luminance forever. The buttons'
        // colours are decided inside paint(), after the sweep, not here — a sweep that
        // ran first would have its confirm colour immediately painted over by ink().
        panel.doOnLayout { paint(dialog, panel, Backdrop.luminanceOf(panel), danger) }
        return true
    }

    /**
     * Ink the sheet's text from its cell, then the buttons LAST — the sweep would
     * otherwise paint over them. The confirm keeps the danger red or the accent, through
     * a state list that goes dim while disabled, as the reset dialog's confirm is for
     * five seconds; the way out is dim, as it is under ORIGINAL.
     */
    fun paint(dialog: AlertDialog, panel: View, lum: Float, danger: Boolean) {
        ink(panel, lum)
        // The confirm is the accent the bottom bar shows the open tab in — the app's one
        // "this is the thing to press" colour; red only when it destroys something.
        val confirm = if (danger) Panel.Palette.CRIT else Ink.accent(lum)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(stateList(confirm, Ink.secondary(lum)))
        for (which in intArrayOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.setTextColor(Ink.secondary(lum))
        }
    }

    private fun stateList(enabled: Int, disabled: Int) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabled, enabled),
    )

    /** Recolour every TextView under [view] — title, message, buttons — from [lum]. */
    private fun ink(view: View, lum: Float) {
        // A card title inside a sheet keeps its dimmer ink, as it does on a card.
        if (view is TextView) view.setTextColor(if (view.tag == CardTitle.TAG) Ink.secondary(lum) else Ink.primary(lum))
        if (view is ViewGroup) for (i in 0 until view.childCount) ink(view.getChildAt(i), lum)
    }

    /** The activity's content view, under whatever wrappers the dialog's context has. */
    private fun activityContent(context: android.content.Context): View? {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c.findViewById(android.R.id.content)
            c = c.baseContext
        }
        return null
    }
}

/**
 * Frosted glass: [behind] — the screen under the sheet — drawn at the sheet's place on
 * screen, its colour lifted and blurred, under a translucent tint, with a light rim and
 * rounded corners. The same recipe as the bottom bar's glass (vibrancy, then blur), as a
 * view background, so any sheet can have it.
 *
 * The screen is drawn by reference — its views' own display lists — so this costs a
 * record and one blur pass per draw of the sheet, not a copy of the screen.
 */
class FrostDrawable(private val behind: View) : Drawable() {
    private val node = android.graphics.RenderNode("frost")
    private val here = IntArray(2)
    private val there = IntArray(2)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()

    override fun draw(canvas: Canvas) {
        val host = callback as? View ?: return
        val d = host.resources.displayMetrics.density
        val w = bounds.width(); val h = bounds.height()
        if (w <= 0 || h <= 0) return
        val r = RADIUS_DP * d
        if (canvas.isHardwareAccelerated && canvas is android.graphics.RecordingCanvas) {
            host.getLocationOnScreen(here)
            behind.getLocationOnScreen(there)
            node.setPosition(bounds.left, bounds.top, bounds.right, bounds.bottom)
            node.setOutline(android.graphics.Outline().apply { setRoundRect(0, 0, w, h, r) })
            node.clipToOutline = true
            val blur = BLUR_DP * d
            node.setRenderEffect(android.graphics.RenderEffect.createChainEffect(
                android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP),
                android.graphics.RenderEffect.createColorFilterEffect(
                    android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(VIBRANCY) })),
            ))
            val rc = node.beginRecording(w, h)
            try {
                rc.translate((there[0] - here[0] - bounds.left).toFloat(), (there[1] - here[1] - bounds.top).toFloat())
                behind.draw(rc)
            } finally {
                node.endRecording()
            }
            canvas.drawRenderNode(node)
        }
        rect.set(bounds)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = TINT
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 1 * d
        paint.color = RIM
        rect.inset(0.5f * d, 0.5f * d)
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val RADIUS_DP = 28f
        /** How far the blur reaches: a frost, not a smear. */
        const val BLUR_DP = 24f
        /** The colour lifted before the blur, as the bar's vibrancy does. */
        const val VIBRANCY = 1.5f
        const val TINT = 0x661E2228
        const val RIM = 0x2EFFFFFF
    }
}
