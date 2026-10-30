package dev.kb896.aiusage.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.service.UpdateCheck
import dev.kb896.aiusage.ui.glass.GlassButton
import dev.kb896.aiusage.ui.glass.StoreButton

/**
 * A newer release than this build, at the top of the screen under the preview: its version,
 * the one this phone has, the first lines of its notes, and the way to update.
 *
 * The 896 Store first; with it on the phone it is the only way, and opens straight onto this
 * app's update. Without it, its button gets the store (store.896.ro) and "Update" downloads
 * the APK as before. Which applies is read on every fill, so installing the store and coming
 * back leaves just its button.
 */
object UpdateCard {

    /** The card's contents into [into] (emptied first); [open] starts what a button names. */
    fun fill(into: LinearLayout, release: UpdateCheck.Release, open: (android.content.Intent) -> Unit) {
        val context = into.context
        into.removeAllViews()
        into.addView(TextView(context).apply {
            text = "AI Usage ${release.tag.removePrefix("v")} is out"
            dev.kb896.aiusage.ui.glass.CardTitle.style(this)
        })
        val notes = UpdateCheck.plain(release.notes).lineSequence().filter { it.isNotBlank() }.take(3).toList()
        SettingsRows.note(into, (listOf("You have ${UpdateCheck.version(context)}.") + notes).joinToString("\n"))

        val inStore = UpdateCheck.inStore(context)
        into.addView(RowOrColumn(context, gap = dp(context, 10)).apply {
            addView(StoreButton(context).apply {
                text = "Update via 896 Store"
                style(this)
                setOnClickListener { open(inStore ?: UpdateCheck.view(UpdateCheck.STORE_SITE)) }
            })
            if (inStore == null) addView(GlassButton(context).apply {
                text = "Update"
                style(this)
                setOnClickListener { open(UpdateCheck.view(UpdateCheck.APK)) }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 12)
        })
    }

    private fun style(button: GlassButton) {
        button.textSize = 14f
        button.setTextColor(Panel.Palette.FG)
        button.typeface = UiType.of(button.context)
    }

    /** A row while its children fit side by side, else a column (a narrow phone, large text); the card's width either way. */
    private class RowOrColumn(context: Context, private val gap: Int) : LinearLayout(context) {
        init { gravity = Gravity.CENTER_VERTICAL }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                val shown = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }
                val any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                val wanted = shown.sumOf { it.measure(any, any); it.measuredWidth } + gap * (shown.size - 1).coerceAtLeast(0)
                val horizontal = wanted <= MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                orientation = if (horizontal) HORIZONTAL else VERTICAL
                shown.forEachIndexed { i, child ->
                    (child.layoutParams as LayoutParams).apply {
                        // Always the card's whole width: stacked, each button; side by side,
                        // the first (the store's) takes what the others leave — alone, all of it.
                        val fills = !horizontal || i == 0
                        width = if (!horizontal) ViewGroup.LayoutParams.MATCH_PARENT else if (fills) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                        weight = if (horizontal && fills) 1f else 0f
                        leftMargin = if (horizontal && i > 0) gap else 0
                        topMargin = if (!horizontal && i > 0) gap else 0
                    }
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
