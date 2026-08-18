package dev.kb896.aiusage.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.rear.RearDialView

/**
 * One account's ring and rows, cropped live out of the real panel.
 *
 * The same argument as PanelPreviewView, one step further: rather than draw a smaller
 * version of one account's block, this draws the WHOLE panel and shows the part of it
 * that belongs to this account. Nothing here knows the panel's layout, so nothing here
 * can disagree with it.
 *
 * The crop comes from RearDialView.accountBounds, which the view fills in as it draws.
 * That matters because the layout is data-dependent — how many windows an account
 * reports, whether the clock and battery are shown, how the leftover space divides — so
 * a rectangle computed here would be a second copy of that arithmetic and would drift.
 */
class AccountPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val dial = RearDialView(context)

    /** Which account to show. */
    var account: Account = Account.PERSONAL
        set(value) { field = value; requestLayout(); invalidate() }

    var state: DashboardState
        get() = dial.state
        set(value) { dial.state = value; requestLayout() }

    var config: PanelConfig
        get() = dial.config
        set(value) { dial.config = value; requestLayout() }

    init {
        addView(dial, LayoutParams(Panel.WIDTH, Panel.HEIGHT))
        setWillNotDraw(false)
        clipChildren = true
    }

    /** Whatever the panel last recorded for this account, or the whole panel if it has
     *  not drawn yet — better a correct full panel for one frame than an empty box. */
    private fun crop(): android.graphics.RectF =
        dial.accountBounds[account] ?: android.graphics.RectF(
            0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val box = crop()
        // The ring column is included whatever the block's own left edge is: this
        // account's ring lives there, and it is half of what is being previewed.
        val cropW = box.right
        val cropH = box.height().coerceAtLeast(1f)
        setMeasuredDimension(width, (width * cropH / cropW).toInt())
        dial.measure(
            MeasureSpec.makeMeasureSpec(Panel.WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(Panel.HEIGHT, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        dial.layout(0, 0, Panel.WIDTH, Panel.HEIGHT)
        val box = crop()
        val scale = width / box.right
        dial.pivotX = 0f
        dial.pivotY = 0f
        dial.scaleX = scale
        dial.scaleY = scale
        // Slide the scaled panel up so the account's block starts at this view's top.
        dial.translationY = -box.top * scale
    }
}
