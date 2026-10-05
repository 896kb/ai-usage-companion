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
        // FROSTED: what is behind the dialog — the settings, the wallpaper — blurred
        // through it, the system's own cross-window blur, clipped to the sheet's rounded
        // background; a translucent tint and a light rim over it. Where the system will
        // not blur (battery saver, a phone without it) the tint alone still reads as a
        // sheet; and before blur existed here, the wallpaper glass did the job.
        val d = dialog.context.resources.displayMetrics.density
        val wm = dialog.context.getSystemService(android.view.WindowManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 31 && wm?.isCrossWindowBlurEnabled == true) {
            window.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = SHEET_RADIUS_DP * d
                setColor(FROST_TINT)
            })
            window.setBackgroundBlurRadius((FROST_BLUR_DP * d).toInt())
            panel.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = SHEET_RADIUS_DP * d
                setStroke((1 * d).toInt().coerceAtLeast(1), FROST_RIM)
            }
        } else {
            panel.background = GlassSheetDrawable()
        }
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
        if (view is TextView) view.setTextColor(Ink.primary(lum))
        if (view is ViewGroup) for (i in 0 until view.childCount) ink(view.getChildAt(i), lum)
    }

    /** The sheet's corners, its frosted tint over the blur, its rim, and how far the blur reaches. */
    private const val SHEET_RADIUS_DP = 28f
    private const val FROST_TINT = 0x8C1E2228.toInt()
    private const val FROST_RIM = 0x2EFFFFFF
    private const val FROST_BLUR_DP = 28f
}
