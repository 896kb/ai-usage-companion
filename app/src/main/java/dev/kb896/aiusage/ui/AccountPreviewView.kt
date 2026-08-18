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
 * One account, rendered as a panel of its own.
 *
 * A real [RearDialView] with [RearDialView.onlyAccount] set, scaled to fit — so the same
 * layout code runs, over a list of one. Nothing here knows how a row is placed, so
 * nothing here can disagree with the panel about it.
 *
 * This replaced a CROP of the full panel, and the reason it had to is worth keeping: the
 * panel fits three accounts and overflows at four — 516px of content into 396px of room.
 * Past three, the account being edited might not be drawn at all, and a crop would be
 * cropping something that was not there. Rendering one account has no such limit, and it
 * also fills the width instead of showing one block adrift in a mostly empty panel.
 */
class AccountPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val dial = RearDialView(context)

    var account: Account = Account.PERSONAL
        set(value) { field = value; dial.onlyAccount = value }

    var state: DashboardState
        get() = dial.state
        set(value) { dial.state = value }

    var config: PanelConfig
        get() = dial.config
        set(value) { dial.config = value }

    init {
        addView(dial, LayoutParams(Panel.WIDTH, Panel.HEIGHT))
        dial.onlyAccount = account
        clipChildren = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(width, (width * Panel.HEIGHT / Panel.WIDTH.toFloat()).toInt())
        dial.measure(
            MeasureSpec.makeMeasureSpec(Panel.WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(Panel.HEIGHT, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        dial.layout(0, 0, Panel.WIDTH, Panel.HEIGHT)
        val scale = width / Panel.WIDTH.toFloat()
        dial.pivotX = 0f
        dial.pivotY = 0f
        dial.scaleX = scale
        dial.scaleY = scale
    }
}
