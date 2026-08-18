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
        navigationButton(parent, name, onClick)
    }

    /** As [navigation], but hands back the button for callers that must relabel it. */
    fun navigationButton(
        parent: LinearLayout,
        name: String,
        onClick: () -> Unit,
    ): android.widget.Button {
        val context = parent.context
        return android.widget.Button(context).apply {
            text = "$name  ›"
            textSize = 14f
            setTextColor(Panel.Palette.FG)
            typeface = context.resources.getFont(R.font.departure_mono)
            setOnClickListener { onClick() }
            parent.addView(this, rowParams(dp(context, 8)))
        }
    }

    /**
     * A row that opens something, with a handle for reordering it.
     *
     * Reordering by MOVE buttons rather than by dragging. Dragging inside a ScrollView
     * needs a touch-interception fight with the scroller, and the payoff is worse than
     * it sounds at this size: two or three rows are reordered in one tap either way, and
     * a tap target is reachable one-handed where a long-press-and-drag is not.
     *
     * @param canMoveUp false for the first row, whose handle is then inert rather than
     *   absent — a row that loses its control changes width and the column jumps
     */
    fun reorderable(
        parent: LinearLayout,
        name: String,
        canMoveUp: Boolean,
        onMoveUp: () -> Unit,
        // Last, so the trailing lambda at the call site is the one that opens the row —
        // which is what the row mostly does.
        onClick: () -> Unit,
    ) {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(android.widget.Button(context).apply {
            text = "$name  ›"
            textSize = 14f
            setTextColor(Panel.Palette.FG)
            typeface = context.resources.getFont(R.font.departure_mono)
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        })
        row.addView(android.widget.Button(context).apply {
            text = "↑"
            textSize = 15f
            isEnabled = canMoveUp
            setTextColor(if (canMoveUp) Panel.Palette.WORK else Panel.Palette.DIM)
            typeface = context.resources.getFont(R.font.departure_mono)
            setOnClickListener { onMoveUp() }
        })
        parent.addView(row, rowParams(dp(context, 8)))
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
            // A custom adapter, because a Spinner inflates its rows from a SYSTEM layout
            // — the closed view and every dropdown item — so neither the palette nor the
            // font sweep in applyPalette can ever reach them. They came out in the
            // platform sans on a screen that is otherwise entirely monospaced, which is
            // exactly the mismatch that made them look pasted in.
            val mono = context.resources.getFont(R.font.departure_mono)
            adapter = object : ArrayAdapter<String>(
                context, android.R.layout.simple_spinner_dropdown_item, options
            ) {
                private fun style(view: View, dropdown: Boolean): View {
                    (view as? TextView)?.apply {
                        typeface = mono
                        textSize = 14f
                        setTextColor(Panel.Palette.FG)
                        // The open list draws over whatever is behind it, so it needs its
                        // own background or it renders as text on the panel underneath.
                        if (dropdown) setBackgroundColor(Panel.Palette.BG)
                    }
                    return view
                }

                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup) =
                    style(super.getView(position, convertView, parent), false)

                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup) =
                    style(super.getDropDownView(position, convertView, parent), true)
            }
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
