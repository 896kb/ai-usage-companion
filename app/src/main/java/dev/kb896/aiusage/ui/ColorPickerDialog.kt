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

    fun show(context: Context, initial: Int, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 4))
        }

        // The colour as it stands — the same glass dot as everywhere a colour is shown —
        // with its hex beside it: the second way of doing the one job this dialog exists
        // for, and typing a value you already have still beats hunting for it.
        val preview = dev.kb896.aiusage.ui.glass.GlassChip(context).apply { colour = initial }
        val field = dev.kb896.aiusage.ui.glass.GlassField(context).apply {
            setText(hex(initial))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(7))
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Panel.Palette.FG)
            background = null
            UiType.dress(this)
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(preview, LinearLayout.LayoutParams(dp(context, 56), dp(context, 56)))
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(context, 14) })
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

        root.addView(head)
        root.addView(square)
        root.addView(hueBar)

        var syncing = false
        val palette = mutableListOf<dev.kb896.aiusage.ui.glass.GlassChip>()
        // Whatever moved is the source of truth; everything else is caught up to it.
        // [from] is left out of that, because writing back to the control under the
        // finger is how a drag ends up fighting itself.
        fun refresh(from: View? = null) {
            preview.colour = current()
            for (dot in palette) dot.ringed = (dot.colour or (0xFF shl 24)) == current()
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

        // THE PANEL'S OWN COLOURS, not a fixed set: every colour something on the panel is
        // drawn in, once each — "make this match that" is most of what this is for. A row
        // of glass dots that scrolls sideways when there are more than fit.
        root.addView(text(context, "ON THE PANEL", Panel.Palette.DIM, 11f), params(context, 14))
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 2), 0, dp(context, 2))
        }
        for (colour in dev.kb896.aiusage.data.ConfigStore(context).load().usedColours()) {
            val dot = dev.kb896.aiusage.ui.glass.GlassChip(context).apply {
                this.colour = colour
                ringed = colour == (initial or (0xFF shl 24))
                setOnClickListener {
                    Color.colorToHSV(colour, hsv)
                    refresh()
                }
            }
            palette.add(dot)
            strip.addView(dot, LinearLayout.LayoutParams(dp(context, 40), dp(context, 40))
                .apply { marginEnd = dp(context, 8) })
        }
        root.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(strip)
        }, params(context, 6))

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
            gravity = Gravity.START
            UiType.dress(this)
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
