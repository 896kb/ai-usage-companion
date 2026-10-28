package dev.kb896.aiusage.ui

import android.app.Activity
import android.widget.LinearLayout
import dev.kb896.aiusage.rear.RearDensity

/**
 * A pinned app's own settings, on the front: its density on the back, and Unpin.
 *
 * The slider only writes when the finger lets go (see [SettingsRows.slider]): each write
 * saves the config and pushes it to the shell, which re-applies the density live, so a
 * write per tick would resize the back's display a hundred times in one drag.
 * Unpin asks nothing further — opening this sheet is the deliberate step, and it sits
 * apart from the slider and the Default button.
 */
object PortalAppSheet {

    /** The slider's position for [dpi]; an unset app starts mid-range, labelled "Default". */
    internal fun progressOf(dpi: Int?): Int =
        ((dpi?.let { RearDensity.clamp(it) } ?: 450) - RearDensity.MIN) / RearDensity.STEP

    internal fun dpiOf(progress: Int): Int = RearDensity.MIN + progress * RearDensity.STEP

    fun show(
        activity: Activity,
        pkg: String,
        label: String,
        currentDpi: Int?,
        onDpi: (Int?) -> Unit,
        onUnpin: () -> Unit,
    ) {
        // Until the slider is touched an unset app reads "Default"; after, the number.
        var touched = currentDpi != null
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), 0)
        }
        SettingsRows.note(column, "Density of the back while $pkg is on top. Other times it keeps its own.")
        SettingsRows.slider(
            column,
            max = (RearDensity.MAX - RearDensity.MIN) / RearDensity.STEP,
            progress = progressOf(currentDpi),
            text = { if (touched) "${dpiOf(it)} dpi" else "Default" },
            onMove = { touched = true },
            onDone = { touched = true; onDpi(dpiOf(it)) },
        )
        Modal.show(
            Modal.builder(activity).setTitle(label.trim())
                .setView(column)
                .setPositiveButton("Unpin") { _, _ -> onUnpin() }
                .setNeutralButton("Default") { _, _ -> onDpi(null) }
                .setNegativeButton("Close", null),
            danger = true,
        )
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
