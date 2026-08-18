package dev.kb896.aiusage.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.rear.RearDialView

/**
 * What the back of the phone is showing, on the front of it.
 *
 * Holds a REAL [RearDialView] at its true 904x572 and scales it down, rather than
 * reimplementing the panel at preview size. Two things follow from that, and both are
 * the reason it is built this way:
 *
 *  * It cannot drift. A second implementation would be a second thing to keep in step,
 *    and the whole point of a preview is to be trusted — one that lies is worse than
 *    none, because a setting that looks wrong here would send you hunting for a bug on
 *    the panel that does not exist.
 *  * It animates. Bars ease, rings sweep, mascots walk, the burn-in drift drifts. This
 *    is the panel, just smaller.
 *
 * Scaling with a view transform rather than by drawing into a bitmap keeps it live and
 * costs nothing extra: the child was going to draw anyway.
 */
class PanelPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val dial = RearDialView(context)

    var state: DashboardState
        get() = dial.state
        set(value) { dial.state = value }

    var config: PanelConfig
        get() = dial.config
        set(value) { dial.config = value }

    init {
        // Fixed at the panel's own size. The scale below does the fitting, so the child
        // never has to know it is being previewed and its layout constants stay honest.
        addView(dial, LayoutParams(Panel.WIDTH, Panel.HEIGHT))
        clipChildren = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val height = (width * Panel.HEIGHT / Panel.WIDTH.toFloat()).toInt()
        setMeasuredDimension(width, height)
        dial.measure(
            MeasureSpec.makeMeasureSpec(Panel.WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(Panel.HEIGHT, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        dial.layout(0, 0, Panel.WIDTH, Panel.HEIGHT)
        // Pivot at the top-left so the scaled child lands exactly on this view's origin;
        // the default pivot is the centre, which would leave it offset by half the
        // difference between the panel's size and the preview's.
        val scale = width / Panel.WIDTH.toFloat()
        dial.pivotX = 0f
        dial.pivotY = 0f
        dial.scaleX = scale
        dial.scaleY = scale
    }
}
