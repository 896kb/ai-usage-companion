package dev.kb896.aiusage.ui.glass

import android.util.TypedValue
import android.widget.TextView
import dev.kb896.aiusage.ui.UiType

/**
 * The one look of a title at the top of a card, or of the label over a control in one —
 * Theme, Background, Advanced, Title, Weather: 13sp, medium, white at 60%, in the front's
 * face. They were each their own — 11sp or 13sp, full white or dimmed, by whichever code
 * built them and by GlassCard's size rule — and side by side they looked unrelated.
 *
 * Tagged, so GlassCard's inking gives a title this colour whatever its size.
 */
object CardTitle {
    const val TAG = "card_title"
    const val SIZE_SP = 13f

    fun style(view: TextView) {
        view.tag = TAG
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_SP)
        UiType.dress(view)
        view.fontVariationSettings = "'wght' 500"
        view.setTextColor(Ink.secondary(0f))
    }
}
