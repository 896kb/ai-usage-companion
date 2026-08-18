package dev.kb896.aiusage.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R

/**
 * Pick a colour by hue, saturation and value, or from the palette the panel is drawn in.
 *
 * HSV rather than RGB sliders. Dragging R, G and B to reach "the same green but darker"
 * means moving three numbers in concert and is close to impossible by feel; in HSV it is
 * one slider. The hex field beside the swatch stays, because typing a value you already
 * have still beats hunting for it.
 *
 * The presets are the panel's own palette and ramp. Most choices here are "make this
 * match that", and the set of things worth matching is small and already named.
 */
object ColorPickerDialog {

    private val PRESETS = listOf(
        "BG" to Panel.Palette.BG,
        "FG" to Panel.Palette.FG,
        "ACCENT 1" to Panel.Palette.PERSONAL,
        "ACCENT 2" to Panel.Palette.WORK,
        "DIM" to Panel.Palette.DIM,
        "TRACK" to Panel.Palette.TRACK,
        "WARN" to Panel.Palette.WARN,
        "CRIT" to Panel.Palette.CRIT,
    )

    fun show(context: Context, initial: Int, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 4))
            setBackgroundColor(Panel.Palette.BG)
        }

        val preview = View(context).apply {
            setBackgroundColor(initial)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 56))
        }
        // The hex field lives HERE rather than in the settings list. Out there it was one
        // more thing in a column that was already too busy; in here it is the second way
        // of doing the one job this dialog exists for.
        val field = android.widget.EditText(context).apply {
            setText(hex(initial))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(7))
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Panel.Palette.FG)
            typeface = context.resources.getFont(R.font.departure_mono)
        }
        root.addView(preview)
        root.addView(field, params(context, 8))

        fun current() = Color.HSVToColor(hsv) or (0xFF shl 24)

        var syncing = false
        fun refresh() {
            preview.setBackgroundColor(current())
            syncing = true
            field.setText(hex(current()))
            syncing = false
        }
        // Typing drives the sliders, dragging drives the field, and the guard stops the
        // two chasing each other — without it, setText from a drag re-parses and re-seeds
        // the very sliders being dragged.
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                if (syncing) return
                val parsed = parse(e?.toString()) ?: return
                Color.colorToHSV(parsed, hsv)
                preview.setBackgroundColor(parsed)
            }
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) = Unit
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) = Unit
        })

        // Hue is 0..360, the other two 0..100 — the slider works in whole numbers, and a
        // degree of hue or a percent of saturation is finer than the eye resolves here.
        slider(context, root, "HUE", 360, (hsv[0]).toInt()) { hsv[0] = it.toFloat(); refresh() }
        slider(context, root, "SATURATION", 100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f; refresh() }
        slider(context, root, "BRIGHTNESS", 100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f; refresh() }

        root.addView(text(context, "FROM THE PALETTE", Panel.Palette.DIM, 11f), params(context, 14))
        val presets = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((_, colour) in PRESETS) {
            presets.addView(View(context).apply {
                setBackgroundColor(colour)
                layoutParams = LinearLayout.LayoutParams(0, dp(context, 30), 1f).apply {
                    marginEnd = dp(context, 4)
                }
                setOnClickListener {
                    Color.colorToHSV(colour, hsv)
                    refresh()
                }
            })
        }
        root.addView(presets, params(context, 4))

        Modal.show(Modal.builder(context)
            .setView(root)
            // Read the FIELD, not the sliders: a typed value that was never dragged has
            // not reached them, and taking the sliders would silently discard it.
            .setPositiveButton("Use it") { _, _ -> onPick(parse(field.text?.toString()) ?: current()) }
            .setNegativeButton("Cancel", null))
    }

    private fun slider(
        context: Context,
        parent: LinearLayout,
        name: String,
        max: Int,
        value: Int,
        onChange: (Int) -> Unit,
    ) {
        parent.addView(text(context, name, Panel.Palette.DIM, 11f), params(context, 12))
        parent.addView(SeekBar(context).apply {
            // The same square thumb and palette track as the ring sliders. A round
            // platform thumb here would be the one control that came from somewhere else.
            progressDrawable = context.getDrawable(R.drawable.seek_track)
            thumb = context.getDrawable(R.drawable.seek_thumb)
            thumbOffset = 0
            this.max = max
            progress = value.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(b: SeekBar) = Unit
                override fun onStopTrackingTouch(b: SeekBar) = Unit
            })
        }, params(context, 2))
    }

    private fun text(context: Context, value: String, colour: Int, size: Float) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(colour)
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = context.resources.getFont(R.font.departure_mono)
        }

    private fun params(context: Context, topDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(context, topDp) }

    private fun parse(raw: String?): Int? {
        val text = raw?.trim()?.removePrefix("#") ?: return null
        if (text.length != 6) return null
        return try {
            Color.parseColor("#$text") or (0xFF shl 24)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun hex(colour: Int) = String.format("#%06X", colour and 0xFFFFFF)

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
