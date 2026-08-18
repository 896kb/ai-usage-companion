package dev.kb896.aiusage.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
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

    /** Swatches per row. Three fits the names and keeps related colours on one line. */
    private const val COLUMNS = 3

    private fun label(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Panel.Palette.DIM)
        typeface = context.resources.getFont(R.font.departure_mono)
    }

    private fun rowParams(topDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = topDp }

    /** One swatch's worth of work: what it is called, what it is now, what to do next. */
    data class Swatch(val name: String, val color: Int, val onChange: (Int) -> Unit)

    /**
     * Colours as a grid of swatches, three across.
     *
     * One full-width row per colour, each with its own hex field, made a column too busy
     * to scan — and scanning is the actual task here, since these are chosen against each
     * other rather than in isolation. Three across fits the names, keeps related colours
     * on one line, and lets you see the set as a set.
     *
     * The swatch IS the control. Editing moved into the picker, where a hex field is the
     * second way of doing that dialog's one job rather than clutter in a list.
     */
    fun colorGrid(parent: LinearLayout, swatches: List<Swatch>) {
        val context = parent.context
        swatches.chunked(COLUMNS).forEach { row ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { swatch -> line.addView(cell(context, swatch)) }
            // Pad a short last row with empty weight, so three items and four items line
            // up on the same columns instead of the last row stretching to fill.
            repeat(COLUMNS - row.size) {
                line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            parent.addView(line, rowParams(dp(context, 10)))
        }
    }

    private fun cell(context: Context, swatch: Swatch): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(context, 8) }
        }
        val block = View(context).apply {
            setBackgroundColor(swatch.color)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 34))
        }
        // Reopen on the CURRENT colour, not the one this row was built with.
        //
        // swatch.color is captured when the row is constructed, so passing it meant the
        // picker always came back with the value from the last rebuild — pick a colour,
        // reopen, and it offered the previous one as though the change had not happened.
        // The block's own background is the live value; it is updated on every pick.
        var current = swatch.color
        block.setOnClickListener {
            ColorPickerDialog.show(context, current) { picked ->
                current = picked
                block.setBackgroundColor(picked)
                swatch.onChange(picked)
            }
        }
        cell.addView(label(context, swatch.name))
        cell.addView(block, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 34)).apply { topMargin = dp(context, 4) })
        return cell
    }

    /**
     * A named free-text field. Blank is passed through as blank so the caller can decide
     * what it means — for a name it means "use the built-in", which is not this
     * component's business to know.
     */
    fun textField(
        parent: LinearLayout,
        name: String,
        initial: String,
        onChange: (String) -> Unit,
    ) {
        val context = parent.context
        parent.addView(label(context, name), rowParams(dp(context, 10)))
        parent.addView(android.widget.EditText(context).apply {
            setText(initial)
            textSize = 14f
            maxLines = 1
            setTextColor(Panel.Palette.FG)
            setHintTextColor(Panel.Palette.DIM)
            typeface = context.resources.getFont(R.font.departure_mono)
            doAfterTextChanged { onChange(it?.toString().orEmpty().trim()) }
        }, rowParams(dp(context, 2)))
    }

    /** A row that opens something else. The chevron is the whole affordance. */
    fun navigation(parent: LinearLayout, name: String, onClick: () -> Unit) {
        val context = parent.context
        parent.addView(android.widget.Button(context).apply {
            text = "$name  ›"
            textSize = 14f
            setTextColor(Panel.Palette.FG)
            typeface = context.resources.getFont(R.font.departure_mono)
            setOnClickListener { onClick() }
        }, rowParams(dp(context, 8)))
    }

    fun toggle(
        parent: LinearLayout,
        name: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val context = parent.context
        // A Switch, not a CheckBox. These are settings that take effect immediately and
        // stay that way, which is what a switch means; a checkbox reads as one of several
        // selections waiting to be confirmed, and there is nothing here to confirm.
        parent.addView(android.widget.Switch(context).apply {
            text = name
            textSize = 13f
            isChecked = initial
            setTextColor(Panel.Palette.FG)
            typeface = context.resources.getFont(R.font.departure_mono)
            // Boxy and green, matching the sliders and the panel. The platform's pill
            // and circle are the only rounded things in an app made of rectangles.
            thumbDrawable = context.getDrawable(R.drawable.switch_thumb)
            trackDrawable = context.getDrawable(R.drawable.switch_track)
            // The tints would multiply over drawables that already carry their own
            // colours, turning the green muddy.
            thumbTintList = null
            trackTintList = null
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
