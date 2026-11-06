package dev.kb896.aiusage.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
    /** A colour dot at the end of a row: a circle, so as tall as it is wide. */
    const val SWATCH_DP = 36

    /** Every row's label, whichever kind of row it is. */
    private const val ROW_SP = 14f

    /** Which set of controls a row builds. Read once per row, not cached, so a theme
     *  change picked up on rebuild needs nothing here to invalidate. */
    private fun glass(context: Context) = UiTheme.isGlass(context)

    private fun rowLabel(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = ROW_SP
        setTextColor(Panel.Palette.FG)
        UiType.dress(this)
    }

    /**
     * Rows that belong to the one above, indented. Empty until something is added to it, so
     * it can be made before knowing whether any of them apply.
     */
    fun sub(parent: LinearLayout): LinearLayout {
        val context = parent.context
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), 0, 0, 0)
            parent.addView(this)
        }
    }

    /** A label over a control: the card title's look (CardTitle), the same everywhere. */
    private fun label(context: Context, text: String) = TextView(context).apply {
        this.text = text
        dev.kb896.aiusage.ui.glass.CardTitle.style(this)
    }

    /**
     * A card's title, at its top: the card title's look, and no gap above it when it is the first
     * thing in the card, as Theme's and Background's sit. A note's gap put the titles added since
     * lower in their cards than those.
     */
    fun title(parent: LinearLayout, text: String): TextView {
        val view = label(parent.context, text)
        parent.addView(view, rowParams(if (parent.childCount == 0) 0 else dp(parent.context, 10)))
        return view
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

    /**
     * A value along a range: a label that says it, over a slider. [text] writes the label
     * for a position, live under the finger; [onDone] gets the position once the finger
     * lets go — saving on every step would rebuild the card under the slider mid-drag.
     */
    fun slider(
        parent: LinearLayout,
        max: Int,
        progress: Int,
        text: (Int) -> String,
        onMove: ((Int) -> Unit)? = null,
        onDone: (Int) -> Unit,
    ) {
        val context = parent.context
        val caption = label(context, text(progress))
        parent.addView(caption, rowParams(dp(context, 10)))
        val bar = dev.kb896.aiusage.ui.glass.GlassSlider(context).apply {
            background = null
            this.max = max
            this.progress = progress.coerceIn(0, max)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    // onMove first: a caller's text() may depend on state it sets.
                    if (fromUser) onMove?.invoke(p)
                    caption.text = text(p)
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
                override fun onStopTrackingTouch(bar: android.widget.SeekBar) = onDone(bar.progress)
            })
        }
        parent.addView(bar, rowParams(0))
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
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 40))
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
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 40)).apply { topMargin = dp(context, 4) })
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
        parent.addView(label(context, name), rowParams(dp(context, if (parent.childCount == 0) 0 else 10)))
        parent.addView(dev.kb896.aiusage.ui.glass.GlassField(context).apply {
            setText(initial)
            textSize = 14f
            maxLines = 1
            if (maxChars > 0) filters = TitleLimits.filter(maxChars)
            setTextColor(Panel.Palette.FG)
            setHintTextColor(Panel.Palette.DIM)
            typeface = UiType.of(context)
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
                layoutParams = LinearLayout.LayoutParams(dp(context, SWATCH_DP), dp(context, SWATCH_DP))
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
        // The switch's own row keeps no top margin in here: the 8dp it carries as a row of
        // its own put it 8dp below the colour dot it now sits beside, which is centred.
        (holder.getChildAt(0)?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 0
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
        row.addView(dev.kb896.aiusage.ui.glass.GlassField(context).apply {
            setText(initial)
            textSize = 14f
            maxLines = 1
            filters = TitleLimits.filter(maxChars)
            setTextColor(Panel.Palette.FG)
            setHintTextColor(Panel.Palette.DIM)
            typeface = UiType.of(context)
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
            rowLabel(context, name),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(swatch(context, colour, onPick))
        parent.addView(row, rowParams(dp(context, 8)))
    }

    /**
     * A glass button that does something when tapped: its name, what it stands at now if anything,
     * and an icon. [submenu]: it opens a sheet of this app's, and has the menu icon; otherwise it
     * runs something, and has the arrow. [link]: it opens a web page, and has the link icon.
     * [icon], for one with an icon of its own: edit, reset.
     */
    fun navigation(parent: LinearLayout, name: String, value: String? = null, submenu: Boolean = false, link: Boolean = false, icon: Int? = null, onClick: () -> Unit): View {
        val context = parent.context
        val icon = icon ?: if (link) R.drawable.ic_link else if (submenu) R.drawable.ic_menu else R.drawable.ic_open
        return opener(context, name, value, icon, onClick).also { parent.addView(it, rowParams(dp(context, 8))) }
    }

    private fun named(name: String, value: String?) = if (value.isNullOrEmpty()) name else "$name: $value"

    private fun opener(context: Context, name: String, value: String?, icon: Int, onClick: () -> Unit) =
        GlassButton(context).apply {
            text = named(name, value)
            textSize = ROW_SP
            setTextColor(Panel.Palette.FG)
            typeface = UiType.of(context)
            iconEnd(this, icon)
            setOnClickListener { onClick() }
        }

    /**
     * [icon] at the end of [button], its text centred on the button as if it were not there: the
     * same icon unseen at the start takes the same room.
     */
    fun iconEnd(button: TextView, icon: Int) {
        val context = button.context
        val unseen = context.getDrawable(icon)!!.mutate().apply { alpha = 0 }
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(unseen, null, context.getDrawable(icon), null)
    }

    /** As [navigation], but hands back the button for callers that must relabel it. */
    fun navigationButton(
        parent: LinearLayout,
        name: String,
        icon: Int? = null,
        onClick: () -> Unit,
    ): android.widget.Button {
        val context = parent.context
        // GlassButton IS-A Button, so callers that stash the return value as
        // android.widget.Button (AccountSettingsSheet's reveal toggle) keep working under
        // either theme without knowing which one built it.
        return (if (glass(context)) GlassButton(context) else android.widget.Button(context)).apply {
            text = name
            textSize = 14f
            setTextColor(Panel.Palette.FG)
            typeface = UiType.of(context)
            icon?.let { iconEnd(this, it) }
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
        row.addView(opener(context, name, null, R.drawable.ic_person, onClick), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Disabled and dimmed on the first row rather than absent: see canMoveUp.
        row.addView(GlassIconButton(context).apply {
            setImageResource(R.drawable.ic_arrow_up)
            contentDescription = "Move $name up"
            isEnabled = canMoveUp
            alpha = if (canMoveUp) 1f else 0.35f
            setOnClickListener { onMoveUp() }
        }, LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)).apply { marginStart = dp(context, 10) })
        parent.addView(row, rowParams(dp(context, 8)))
    }

    /**
     * A switch, [name] beside it. Returns a way to set it back without asking anyone — for
     * a change the caller turns down (the portal's pin list when it is full); under
     * ORIGINAL the platform Switch still calls [onChange] with the value it is set to.
     */
    fun toggle(
        parent: LinearLayout,
        name: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ): (Boolean) -> Unit {
        val context = parent.context
        if (!glass(context)) {
            // A Switch, not a CheckBox. These are settings that take effect immediately
            // and stay that way, which is what a switch means; a checkbox reads as one of
            // several selections waiting to be confirmed, and there is nothing here to
            // confirm.
            val switch = android.widget.Switch(context)
            parent.addView(switch.apply {
                text = name
                textSize = ROW_SP
                isChecked = initial
                setTextColor(Panel.Palette.FG)
                typeface = UiType.of(context)
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
            return { on -> switch.isChecked = on }
        }
        // Under GLASS the platform Switch is a GlassSwitch beside its own label instead
        // of using its built-in text — the liquid track has no room in its 64x28dp frame
        // for a label the way the platform switch draws one inline.
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(rowLabel(context, name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // The 896 Store's toggle, copied whole (ui/liquid/LiquidToggle): the app's own
        // stuttered, the store's runs smoothly. On is the app's accent.
        val liquid = dev.kb896.aiusage.ui.liquid.LiquidToggle(context).apply {
            setCheckedSilently(initial)
            onCheckedChange = onChange
        }
        row.addView(liquid)
        parent.addView(row, rowParams(dp(context, 8)))
        return { on -> liquid.setCheckedSilently(on) }
    }

    /**
     * A choice from a fixed set with every answer in view as a pill, under its name: for the
     * few choices worth seeing whole and changing often. The rest are [choice].
     */
    fun pills(
        parent: LinearLayout,
        name: String,
        options: List<String>,
        selected: Int,
        onChange: (Int) -> Unit,
    ) {
        val context = parent.context
        parent.addView(label(context, name), rowParams(dp(context, if (parent.childCount == 0) 0 else 10)))
        parent.addView(GlassSegmented(context, options, selected).apply { this.onChange = onChange }, rowParams(dp(context, 4)))
    }

    /**
     * A choice from a fixed set: its name and the current answer on one row, the same row as
     * anything else that opens something. The answers are in a modal of real rows (see Modal).
     * [onChange] only for a different answer.
     */
    fun choice(
        parent: LinearLayout,
        name: String,
        options: List<String>,
        selected: Int,
        icon: Int = R.drawable.ic_menu,
        onChange: (Int) -> Unit,
    ) {
        val context = parent.context
        var current = selected.coerceIn(0, options.size - 1)
        lateinit var row: View
        row = navigation(parent, name, options[current], icon = icon) {
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 20), dp(context, 4), dp(context, 20), 0)
            }
            val dialog = Modal.builder(context).setTitle(name).setView(column).setNegativeButton("Cancel", null).create()
            options.forEachIndexed { i, option ->
                column.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(context, 44)
                    addView(rowLabel(context, option), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (i == current) addView(rowLabel(context, "\u2713").apply { setTextColor(Ink.accent(0f)) })
                    setOnClickListener {
                        dialog.dismiss()
                        if (i != current) {
                            current = i
                            (row as TextView).text = named(name, option)
                            onChange(i)
                        }
                    }
                })
            }
            Modal.show(dialog)
        }
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
