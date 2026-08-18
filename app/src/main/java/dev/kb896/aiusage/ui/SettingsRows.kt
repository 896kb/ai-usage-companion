package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R

/**
 * Builds settings controls in code rather than in XML.
 *
 * There are already a dozen colours, four dropdowns and three toggles, and the count
 * grows with every account. Hand-written XML for each would be a wall of near-identical
 * blocks, every one of which is a chance to forget the typeface or a palette colour —
 * which has happened once here already. Built from one place, a control cannot be styled
 * inconsistently, and adding an account adds rows rather than markup.
 */
object SettingsRows {

    private fun label(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Panel.Palette.DIM)
        typeface = context.resources.getFont(R.font.departure_mono)
    }

    private fun rowParams(topDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = topDp }

    /**
     * A colour, edited as hex, with a live swatch.
     *
     * Hex rather than a colour wheel: these are being matched to a palette that is itself
     * written as hex, and typing the value you already have beats hunting for it in a
     * gradient. The swatch is what makes that bearable.
     *
     * [onChange] fires only for a COMPLETE, valid colour. Reacting to every keystroke
     * would push a half-typed "#1D1" to the panel and repaint it in whatever that
     * happens to parse as.
     */
    fun color(
        parent: LinearLayout,
        name: String,
        initial: Int,
        onChange: (Int) -> Unit,
    ) {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val swatch = View(context).apply {
            setBackgroundColor(initial)
            layoutParams = LinearLayout.LayoutParams(dp(context, 28), dp(context, 28))
        }
        val field = EditText(context).apply {
            setText(String.format("#%06X", initial and 0xFFFFFF))
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(7))
            textSize = 14f
            setTextColor(Panel.Palette.FG)
            setHintTextColor(Panel.Palette.DIM)
            typeface = context.resources.getFont(R.font.departure_mono)
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            doAfterTextChanged { text ->
                val parsed = parseColor(text?.toString())
                if (parsed != null) {
                    swatch.setBackgroundColor(parsed)
                    onChange(parsed)
                }
            }
        }
        row.addView(field)
        row.addView(swatch)
        parent.addView(label(context, name), rowParams(dp(context, 10)))
        parent.addView(row, rowParams(dp(context, 2)))
    }

    /**
     * Opaque only. A translucent panel background is not a preference, it is a way to
     * break the panel — with nothing occluding it the system's own rear launcher resumes
     * and takes the display. Alpha typed into the field is ignored rather than obeyed.
     */
    private fun parseColor(raw: String?): Int? {
        val text = raw?.trim()?.removePrefix("#") ?: return null
        if (text.length != 6) return null
        return try {
            Color.parseColor("#$text") or (0xFF shl 24)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun toggle(
        parent: LinearLayout,
        name: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val context = parent.context
        parent.addView(CheckBox(context).apply {
            text = name
            textSize = 13f
            isChecked = initial
            setTextColor(Panel.Palette.FG)
            typeface = context.resources.getFont(R.font.departure_mono)
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }, rowParams(dp(context, 6)))
    }

    /**
     * A choice from a fixed set.
     *
     * [onChange] ignores the selection the adapter makes while being populated —
     * setSelection fires the listener too, and reacting to it would write the seeded
     * value back over the stored one on every open.
     */
    fun choice(
        parent: LinearLayout,
        name: String,
        options: List<String>,
        selected: Int,
        onChange: (Int) -> Unit,
    ) {
        val context = parent.context
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected.coerceIn(0, options.size - 1), false)
            var seeded = false
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (!seeded) { seeded = true; return }
                    onChange(pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
        parent.addView(label(context, name), rowParams(dp(context, 10)))
        parent.addView(spinner, rowParams(dp(context, 2)))
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
