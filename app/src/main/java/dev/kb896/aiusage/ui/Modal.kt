package dev.kb896.aiusage.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R

/**
 * The only way this app should be putting a dialog on screen.
 *
 * Everything else here is drawn or built by hand, so a dialog was the one surface still
 * arriving in the platform's own clothes: a blue accent, a light-on-dark title in Roboto,
 * and buttons in a typeface that appears nowhere else. On a screen whose whole point is a
 * particular look, that reads as another app's window opening on top of this one.
 *
 * Two halves, for a reason. [R.style.PanelDialog] colours what the framework inflates
 * before any of our code can reach it. [dress] fixes the typeface afterwards, because a
 * font set at theme level does not reliably travel into the title and the button bar —
 * their text appearances name a family, and that wins.
 *
 * Anything with rows to choose from should pass a view of real buttons rather than
 * `setItems`. A dialog's list rows are made by an adapter as they scroll into view, so a
 * one-shot sweep like [dress] cannot reach them and half of them come back stock.
 */
object Modal {

    fun builder(context: Context): AlertDialog.Builder =
        AlertDialog.Builder(context, R.style.PanelDialog)

    /**
     * Show it, then repaint what the theme could not.
     *
     * @param danger true when the positive button destroys something, which colours it
     *   the same red the panel uses for a spent limit. A confirmation whose confirm looks
     *   exactly like every other confirm is the one place that distinction is worth
     *   making, and it is the only signal separating "Use it" from "Sign out".
     */
    fun show(dialog: AlertDialog, danger: Boolean = false): AlertDialog {
        dialog.show()
        dress(dialog, danger)
        return dialog
    }

    /** As [show], for callers holding a builder rather than a dialog. */
    fun show(builder: AlertDialog.Builder, danger: Boolean = false): AlertDialog =
        show(builder.create(), danger)

    /**
     * Sweep the shown dialog for anything still in the platform's typeface.
     *
     * After show(), not before: the title, the message and the button bar do not exist
     * until the window is created, and getButton returns null before then.
     */
    fun dress(dialog: AlertDialog, danger: Boolean = false) {
        val mono = dialog.context.resources.getFont(R.font.departure_mono)

        fun walk(view: View) {
            when (view) {
                // Typeface only. Colours come from the theme, and views we build
                // ourselves have already chosen theirs — overwriting here would flatten
                // the token example and the expiry warning back to one tone.
                is TextView -> view.typeface = mono
                is ViewGroup -> for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        dialog.window?.decorView?.let(::walk)

        // The buttons again, explicitly. They are inside the decor and the sweep already
        // reached them, but the positive one needs its colour decided here and reading it
        // back off the theme would lose the danger case.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            typeface = mono
            setTextColor(tint(if (danger) Panel.Palette.CRIT else Panel.Palette.WORK))
        }
        for (which in intArrayOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply {
                typeface = mono
                // Dim, so the way out of a dialog is legible without competing with the
                // thing the dialog is asking.
                setTextColor(tint(Panel.Palette.DIM))
            }
        }
    }

    /**
     * [colour], but going dark while the button is disabled.
     *
     * A flat int paints every state the same, which would leave the reset dialog's
     * confirm looking live throughout the five seconds it refuses to be pressed — the
     * one dialog where a disabled button has to look disabled.
     */
    private fun tint(colour: Int) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(Panel.Palette.DIM, colour),
    )
}
