package dev.kb896.aiusage.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R

/**
 * Pick a colour off a square, a hue strip, the panel's own palette, or by typing it.
 *
 * It was three sliders — hue, saturation, brightness — which is honest about the model
 * and miserable in use: no bar shows what the other two are doing, so "the same green but
 * warmer" is found by dragging, glancing at a swatch elsewhere, dragging back, repeating.
 * The square shows every colour one hue can make at once, and the answer is wherever your
 * finger lands. See [ColorField].
 *
 * The hex field stays, because typing a value you already have still beats hunting for
 * it, and so do the presets: most choices here are "make this match that", and the set of
 * things worth matching is small and already named.
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
            // Under GLASS this view is the content of a Modal, and the sheet drawable
            // from Task 12 already paints the pane behind it — a second, opaque fill
            // here would hide that pane rather than sit on it. Under ORIGINAL there is
            // no sheet to show through, so the flat panel colour stays.
            if (!dev.kb896.aiusage.ui.glass.UiTheme.isGlass(context))
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
        fun current() = Color.HSVToColor(hsv) or (0xFF shl 24)

        val square = ColorField.SatVal(context).apply {
            hue = hsv[0]
            place(hsv[1], hsv[2])
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 170)
            ).apply { topMargin = dp(context, 10) }
        }
        val hueBar = ColorField.Hue(context).apply {
            hue = hsv[0]
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 26)
            ).apply { topMargin = dp(context, 8) }
        }

        root.addView(preview)
        root.addView(square)
        root.addView(hueBar)
        root.addView(field, params(context, 8))

        var syncing = false
        // Whatever moved is the source of truth; everything else is caught up to it.
        // [from] is left out of that, because writing back to the control under the
        // finger is how a drag ends up fighting itself.
        fun refresh(from: View? = null) {
            preview.setBackgroundColor(current())
            if (from !== square) { square.hue = hsv[0]; square.place(hsv[1], hsv[2]) }
            else square.hue = hsv[0]
            if (from !== hueBar) hueBar.hue = hsv[0]
            if (from !== field) {
                syncing = true
                field.setText(hex(current()))
                syncing = false
            }
        }
        // Typing drives the square, dragging drives the field, and the guard stops the
        // two chasing each other — without it, setText from a drag re-parses and re-seeds
        // the very control being dragged.
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                if (syncing) return
                val parsed = parse(e?.toString()) ?: return
                Color.colorToHSV(parsed, hsv)
                refresh(from = field)
            }
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) = Unit
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) = Unit
        })

        square.onPick = { sat, value -> hsv[1] = sat; hsv[2] = value; refresh(from = square) }
        // The square is repainted for the new hue as the strip moves, so the colour under
        // the cursor is always the one the swatch is showing.
        hueBar.onPick = { h -> hsv[0] = h; refresh(from = hueBar) }

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
