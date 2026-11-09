package dev.kb896.aiusage.ui

import android.app.Activity
import android.widget.LinearLayout
import dev.kb896.aiusage.rear.GuestWidth
import dev.kb896.aiusage.rear.RearDensity
import dev.kb896.aiusage.rear.RearGeometry

/**
 * A pinned app's own settings, on the front: its density on the back, whether it is kept
 * right of the cameras there (experimental), and Unpin.
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

    /** The slider's first stop counted from: the step at or below the minimum (70 for 72). */
    private val BASE = RearDensity.MIN / RearDensity.STEP * RearDensity.STEP

    /** The slider's position for [dpi]; an unset app starts mid-range, labelled "Default". */
    private fun progressOf(dpi: Int?): Int =
        ((dpi?.let { RearDensity.clamp(it) } ?: DEFAULT_GUESS) - BASE) / RearDensity.STEP

    /** The first stop is the minimum, the rest the tens: 72, 80, 90… */
    private fun dpiOf(progress: Int): Int = maxOf(RearDensity.MIN, BASE + progress * RearDensity.STEP)

    fun show(
        activity: Activity,
        pkg: String,
        label: String,
        currentDpi: Int?,
        confined: Boolean,
        currentWidth: Int?,
        onDpi: (Int?) -> Unit,
        onWidth: (Int?) -> Unit,
        onConfined: (Boolean) -> Unit,
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
            max = (RearDensity.MAX - BASE) / RearDensity.STEP,
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
        // The app's width right of the cameras (GuestWidth): the whole of it at the far end.
        val rear = RearGeometry.of(activity)
        val lo = GuestWidth.min(rear)
        val steps = (GuestWidth.full(rear) - lo + GuestWidth.STEP - 1) / GuestWidth.STEP
        fun widthOf(p: Int): Int? = GuestWidth.clamp(rear, lo + p * GuestWidth.STEP)
        val widthNow = GuestWidth.clamp(rear, currentWidth)
        SettingsRows.note(column, "Width of ${label.trim()} right of the cameras. Narrower suits apps that grow with the screen.")
        SettingsRows.slider(
            column,
            max = steps,
            progress = widthNow?.let { (it - lo) / GuestWidth.STEP } ?: steps,
            text = { GuestWidth.label(rear, widthOf(it)) },
            onDone = { if (widthOf(it) != widthNow) onWidth(widthOf(it)) },
        )
        // Off, as every app starts: the whole back. Experimental for what it cannot do yet: its
        // keyboard opens on the front.
        SettingsRows.toggle(column, "Keep right of the cameras (experimental)", confined) { on -> onConfined(on) }
        SettingsRows.note(column, "On, ${label.trim()} runs on a screen of its own beside the cameras. Its keyboard opens on the front.")
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
