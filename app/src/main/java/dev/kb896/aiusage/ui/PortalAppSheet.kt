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

    /**
     * Where an unset app's slider rests. Only a guess: the real default is the display's,
     * and the app cannot read it (a guest may be overriding it right now), so the value
     * line says "Default" rather than this number until the slider is moved.
     */
    private const val DEFAULT_GUESS = 450

    /** The slider's position for [dpi]; an unset app starts mid-range, labelled "Default". */
    private fun progressOf(dpi: Int?): Int =
        ((dpi?.let { RearDensity.clamp(it) } ?: DEFAULT_GUESS) - RearDensity.MIN) / RearDensity.STEP

    private fun dpiOf(progress: Int): Int = RearDensity.MIN + progress * RearDensity.STEP

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
        SettingsRows.note(column, "Density of the back while ${label.trim()} is open there. Other times it keeps its own.")
        SettingsRows.slider(
            column,
            max = (RearDensity.MAX - RearDensity.MIN) / RearDensity.STEP,
            progress = progressOf(currentDpi),
            text = { if (touched) "${dpiOf(it)} dpi" else "Default" },
            onMove = { touched = true },
            onDone = {
                // A tap on the thumb without moving it must not save the guess as a choice.
                val untouchedTap = currentDpi == null && !touched && it == progressOf(null)
                touched = true
                if (!untouchedTap) onDpi(dpiOf(it))
            },
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
