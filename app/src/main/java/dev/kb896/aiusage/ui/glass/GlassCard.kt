package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.kb896.aiusage.rear.Glass

/**
 * A section of the settings screen.
 *
 * Under GLASS: a pane behind its rows, the catalog's card recipe
 * (destinations/ScrollContainerContent.kt, Apache 2.0) — a 28dp corner with the panel's
 * card tint. Under ORIGINAL: an inert LinearLayout, so declaring it in the XML changes
 * nothing about the layout ORIGINAL has today.
 */
class GlassCard @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), Glassy {

    val glass = UiTheme.isGlass(context)
    var luminance = Pane.FALLBACK_LUMINANCE; private set
    private var inkedLight: Boolean? = null
    private val density = context.resources.displayMetrics.density

    init {
        orientation = VERTICAL
        if (glass) {
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
            setWillNotDraw(false)
        }
    }

    // The parent hands the child its LayoutParams here, synchronously, at addView time —
    // onAttachedToWindow is too late: a view never added to a real window (as in a
    // Robolectric host.addView/measure/layout test) never gets attached at all.
    override fun setLayoutParams(params: ViewGroup.LayoutParams) {
        if (glass) (params as? MarginLayoutParams)?.bottomMargin = (12 * density).toInt()
        super.setLayoutParams(params)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (glass) ink()
    }

    override fun onDraw(canvas: Canvas) {
        if (!glass) { super.onDraw(canvas); return }
        // interior = false: a card is the largest pane on the screen and nearly all of it
        // is middle, where the lens hands the backdrop straight through — the same
        // wallpaper the root has already painted behind it. Drawing the refraction only
        // in the rim band is the same picture for a fraction of the shader's work, which
        // is what a 18ms scroll frame under GLASS was mostly made of.
        luminance = Pane.draw(canvas, this, 0f, 0f, width.toFloat(), height.toFloat(),
            28 * density, Glass.CARD_TINT, interior = false)
        // A card sliding from a dark region of the picture into a light one flips its
        // ink; only when the side changes, or every frame would restyle every row.
        if (inkedLight != Ink.isLight(luminance)) ink()
        super.onDraw(canvas)
    }

    /** Recolour every plain TextView inside from this card's cell. Glass controls ink themselves. */
    fun ink() {
        if (!glass) return
        if (Backdrop.bitmap != null) luminance = Backdrop.luminanceOf(this)
        inkedLight = Ink.isLight(luminance)
        fun walk(v: View) {
            if (v is Glassy && v !== this) return
            if (v is TextView) v.setTextColor(if (v.textSize <= 11.5f * density) Ink.secondary(luminance) else Ink.primary(luminance))
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
    }
}
