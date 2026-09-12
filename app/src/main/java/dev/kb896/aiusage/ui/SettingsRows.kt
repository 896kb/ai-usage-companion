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
import dev.kb896.aiusage.ui.glass.*

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

    /** Which set of controls a row builds. Read once per row, not cached, so a theme
     *  change picked up on rebuild needs nothing here to invalidate. */
    private fun glass(context: Context) = UiTheme.isGlass(context)

    private fun label(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Panel.Palette.DIM)
        typeface = context.resources.getFont(R.font.departure_mono)
    }

    /**
     * A line of explanation under a control, in the same dim type as a row's own label.
     *
     * For a switch that is on but cannot do anything yet — the setting is not wrong, so
     * it is not an error, and disabling the row would hide the feature from the person
     * who most needs to find it. Saying why is the whole content.
     */
    fun note(parent: LinearLayout, text: String): TextView {
        val view = label(parent.context, text)
        parent.addView(view, rowParams(dp(parent.context, 6)))
        return view
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
        // Declared as View, not GlassChip: under ORIGINAL this is a plain background-colour
        // View exactly as before, and the two shapes only meet again at the click listener
        // below, which branches on the runtime type.
        val block: View = if (glass(context)) {
            GlassChip(context).apply {
                colour = swatch.color
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 34))
            }
        } else {
            View(context).apply {
                setBackgroundColor(swatch.color)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 34))
            }
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
                if (block is GlassChip) block.colour = picked else block.setBackgroundColor(picked)
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
    /** @param maxChars 0 for no cap — see TitleLimits for where the real ones come from */
    fun textField(
        parent: LinearLayout,
        name: String,
        initial: String,
        maxChars: Int = 0,
        onChange: (String) -> Unit,
    ) {
        val context = parent.context
        parent.addView(label(context, name), rowParams(dp(context, 10)))
        parent.addView(android.widget.EditText(context).apply {
            setText(initial)
            textSize = 14f
            maxLines = 1
            if (maxChars > 0) filters = TitleLimits.filter(maxChars)
            setTextColor(Panel.Palette.FG)
            setHintTextColor(Panel.Palette.DIM)
            typeface = context.resources.getFont(R.font.departure_mono)
            doAfterTextChanged { onChange(it?.toString().orEmpty().trim()) }
        }, rowParams(dp(context, 2)))
    }

    /**
     * A colour chip that opens the picker, sized to sit at the end of a row.
     *
     * Inline rather than in the colour grid below. A setting and the colour it applies
     * to belong on one line — the grid is for colours that are chosen against each other
     * (the ramp, the accents), and a lone swatch labelled TIME sitting three sections
     * away from the TIME toggle is a puzzle rather than a control.
     */
    private fun swatch(context: Context, colour: Int, onPick: (Int) -> Unit): View {
        if (glass(context)) {
            val chip = GlassChip(context).apply {
                this.colour = colour
                layoutParams = LinearLayout.LayoutParams(dp(context, 44), dp(context, 26))
                    .apply { marginStart = dp(context, 12) }
            }
            var current = colour
            chip.setOnClickListener {
                ColorPickerDialog.show(context, current) { picked -> current = picked; chip.colour = picked; onPick(picked) }
            }
            return chip
        }
        val chip = View(context).apply {
            setBackgroundColor(colour)
            layoutParams = LinearLayout.LayoutParams(dp(context, 44), dp(context, 26))
                .apply { marginStart = dp(context, 12) }
        }
        // The chip REPAINTS ITSELF, and remembers what it is now.
        //
        // Both of these are the bug documented in cell() above, which this function did
        // not get. `colour` is captured when the row is built, and the rows are not
        // rebuilt on an ordinary edit — see MainActivity.write, which deliberately only
        // saves, pushes to the panel and refreshes the preview, because rebuilding
        // wholesale is for structural changes that rename other rows.
        //
        // So the panel changed colour immediately and the chip beside the switch went on
        // showing the old one until the app was restarted; and reopening the picker
        // offered the pre-edit colour as though the change had never happened. A control
        // that does not show the value it just set reads as the setting not having taken.
        var current = colour
        chip.setOnClickListener {
            ColorPickerDialog.show(context, current) { picked ->
                current = picked
                chip.setBackgroundColor(picked)
                onPick(picked)
            }
        }
        return chip
    }

    /**
     * A toggle with its own colour beside it.
     *
     * @param colour null for a toggle that governs no colour of its own
     */
    fun toggleWithColor(
        parent: LinearLayout,
        name: String,
        initial: Boolean,
        colour: Int,
        onChange: (Boolean) -> Unit,
        onPick: (Int) -> Unit,
    ) {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The switch takes the space the swatch does not, so every row's chip lines up
        // on one right edge however long its label is.
        val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        toggle(holder, name, initial, onChange)
        row.addView(holder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(swatch(context, colour, onPick))
        parent.addView(row, rowParams(dp(context, 8)))
    }

    /**
     * A text field with a colour beside it, and a hard cap on its length.
     *
     * The cap is what stops a title running off the panel. Enforced here, while typing,
     * rather than by truncating at draw time: a title silently cut short on a screen you
     * are not looking at is indistinguishable from a rendering fault.
     */
    fun textFieldWithColor(
        parent: LinearLayout,
        name: String,
        initial: String,
        maxChars: Int,
        colour: Int,
        onChange: (String) -> Unit,
        onPick: (Int) -> Unit,
    ) {
        val context = parent.context
        parent.addView(label(context, "$name  (max $maxChars)"), rowParams(dp(context, 10)))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(android.widget.EditText(context).apply {
            setText(initial)
            textSize = 14f
            maxLines = 1
            filters = TitleLimits.filter(maxChars)
            setTextColor(Panel.Palette.FG)
            setHintTextColor(Panel.Palette.DIM)
            typeface = context.resources.getFont(R.font.departure_mono)
            doAfterTextChanged { onChange(it?.toString().orEmpty().trim()) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(swatch(context, colour, onPick))
        parent.addView(row, rowParams(dp(context, 2)))
    }

    /**
     * A labelled colour, with no switch beside it.
     *
     * For settings that are only a colour — where [toggleWithColor] would need a toggle
     * that governs nothing, and the colour grid would put it in a different part of the
     * screen from the thing it belongs to.
     */
    fun colorRow(parent: LinearLayout, name: String, colour: Int, onPick: (Int) -> Unit) {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(context).apply {
                text = name
                textSize = 13f
                setTextColor(Panel.Palette.FG)
                typeface = context.resources.getFont(R.font.departure_mono)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(swatch(context, colour, onPick))
        parent.addView(row, rowParams(dp(context, 8)))
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
        // GlassButton IS-A Button, so callers that stash the return value as
        // android.widget.Button (AccountSettingsSheet's reveal toggle) keep working under
        // either theme without knowing which one built it.
        return (if (glass(context)) GlassButton(context) else android.widget.Button(context)).apply {
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
        // Read once per row, as the helper's own doc comment says — UiTheme.isGlass hits
        // ConfigStore.load, which decodes a SharedPreferences string, so a call per button
        // is a needless decode of the same value twice for one row.
        val isGlass = glass(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Same GlassButton-under-GLASS substitution as navigationButton, for both the
        // row's own button and its move handle.
        row.addView((if (isGlass) GlassButton(context) else android.widget.Button(context)).apply {
            text = "$name  ›"
            textSize = 14f
            setTextColor(Panel.Palette.FG)
            typeface = context.resources.getFont(R.font.departure_mono)
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        })
        row.addView((if (isGlass) GlassButton(context) else android.widget.Button(context)).apply {
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
        if (!glass(context)) {
            // A Switch, not a CheckBox. These are settings that take effect immediately
            // and stay that way, which is what a switch means; a checkbox reads as one of
            // several selections waiting to be confirmed, and there is nothing here to
            // confirm.
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
            return
        }
        // Under GLASS the platform Switch is a GlassSwitch beside its own label instead
        // of using its built-in text — the liquid track has no room in its 64x28dp frame
        // for a label the way the platform switch draws one inline.
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = name
            textSize = 13f
            typeface = context.resources.getFont(R.font.departure_mono)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(GlassSwitch(context).apply {
            setCheckedSilently(initial)
            onCheckedChange = onChange
        })
        parent.addView(row, rowParams(dp(context, 8)))
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
        // GlassSegmented replaces the Spinner outright under GLASS: a dropdown menu opens
        // an opaque platform overlay that the theme has no way to make of glass, and with
        // three or four options a segmented control reads faster than a menu anyway.
        if (glass(context)) {
            parent.addView(label(context, name), rowParams(dp(context, 10)))
            parent.addView(GlassSegmented(context, options, selected).apply { this.onChange = onChange },
                rowParams(dp(context, 4)))
            return
        }
        val spinner = Spinner(context).apply {
            // A custom adapter, because a Spinner inflates its rows from a SYSTEM layout
            // — the closed view and every dropdown item — so neither the palette nor the
            // font sweep in applyPalette can ever reach them. They came out in the
            // platform sans on a screen that is otherwise entirely monospaced, which is
            // exactly the mismatch that made them look pasted in.
            // The theme's face. A Spinner inflates from a SYSTEM layout, so applyPalette's
            // sweep never reaches these rows and they have to be styled here.
            val mono = UiType.of(context)
            val axes = UiType.variation(context)
            adapter = object : ArrayAdapter<String>(
                context, android.R.layout.simple_spinner_dropdown_item, options
            ) {
                private fun style(view: View, dropdown: Boolean): View {
                    (view as? TextView)?.apply {
                        typeface = mono
                        fontVariationSettings = axes
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
