package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Paint
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R

/**
 * How many characters each title can hold before it runs off the panel.
 *
 * MEASURED, not guessed. The panel's font is monospaced, so a title's width is exactly
 * its length times one advance — but that advance is a property of Departure Mono at a
 * particular size, and hardcoding a character count would be a number nobody could check
 * and nobody would update when a text size moved. Asking the same Paint the panel draws
 * with costs one measurement at startup and cannot drift.
 *
 * These are enforced as input filters rather than by truncating at draw time. A title
 * silently cut off on a screen you are not looking at is indistinguishable from a
 * rendering bug; a field that stops accepting characters is obvious while you are typing.
 */
object TitleLimits {

    /**
     * What the masthead can occupy, which is NOT the bar column it is centred over.
     *
     * It is centred at the bar column's midpoint and then free to extend past it in both
     * directions — measuring the column itself gave 10 characters, which would have
     * truncated the app's own default title of CLAUDE USAGE at twelve.
     *
     * The real limits are the content edge on the left and the extras column on the
     * right, which shares this line: the clock and battery are right-aligned at
     * CONTENT_RIGHT, and a title running under them is worse than one cut short. Being
     * centred, the tighter of the two distances is what binds, doubled.
     *
     * Measured with the extras present. They are optional, so the cap is a little
     * conservative when they are all switched off — the alternative is a limit that
     * changes as other settings are toggled, which would silently invalidate a title
     * someone had already typed.
     */
    private const val MASTHEAD_CENTRE = (442f + 720f) / 2f
    private const val CONTENT_LEFT = 330f
    private const val CONTENT_RIGHT = 870f

    /** Icon, gap and "HH:MM" at the extras' 18px — the widest of the three rows. */
    private const val EXTRAS_WIDTH = 20f + 8f + 5f * 18f * 0.6364f

    private val MASTHEAD_WIDTH = 2f * minOf(
        MASTHEAD_CENTRE - CONTENT_LEFT,
        (CONTENT_RIGHT - EXTRAS_WIDTH) - MASTHEAD_CENTRE,
    )
    private const val MASTHEAD_SIZE = 40f

    /**
     * An account heading shares its line with the plan tag on the right, so it gets the
     * space up to where that tag starts rather than the full column.
     */
    /** "MAX 5X" at the plan tag's 18px, which shares the heading's line. Declared
     *  BEFORE the width that uses it: a const cannot read one below it. */
    private const val PLAN_TAG_WIDTH = 6f * 18f * 0.6364f
    private const val HEADING_WIDTH = (904f - 34f - 82f) - 330f - PLAN_TAG_WIDTH
    private const val HEADING_SIZE = 22.5f

    private var cached: Pair<Int, Int>? = null

    private fun limits(context: Context): Pair<Int, Int> = cached ?: run {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = context.resources.getFont(R.font.departure_mono)
        }
        // "M" rather than an average: in a monospaced face every glyph advances the same,
        // and picking the widest-looking one makes that assumption visible.
        paint.textSize = MASTHEAD_SIZE
        val masthead = (MASTHEAD_WIDTH / paint.measureText("M")).toInt()
        paint.textSize = HEADING_SIZE
        val heading = (HEADING_WIDTH / paint.measureText("M")).toInt()
        (masthead to heading).also { cached = it }
    }

    /** Characters the masthead can hold. */
    fun masthead(context: Context): Int = limits(context).first

    /** Characters an account name can hold. */
    fun accountName(context: Context): Int = limits(context).second

    /**
     * An input filter for [max] characters.
     *
     * Emoji survive this: a filter counts UTF-16 units, and one emoji is often two. That
     * is a real inaccuracy and the honest trade — the alternative is counting grapheme
     * clusters, which does not match what the monospaced advance measures either, since
     * the panel draws an emoji at whatever width the font gives it. The limit is a guard
     * against overflow, not a character budget.
     */
    fun filter(max: Int) = arrayOf<android.text.InputFilter>(
        android.text.InputFilter.LengthFilter(max),
    )

    /** Panel width, kept here so the constants above can be checked against it. */
    const val PANEL_WIDTH = Panel.WIDTH
}
