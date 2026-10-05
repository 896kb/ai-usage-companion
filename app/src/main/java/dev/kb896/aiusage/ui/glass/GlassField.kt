package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.EditText
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.Glass
import dev.kb896.aiusage.ui.UiType

/**
 * A text field on a pane of glass, in the front's face.
 *
 * The platform's EditText brought the old design into every glass sheet it was put in:
 * a grey underline and the monospaced face, the one thing on the screen not made of glass.
 * This is the same field with the underline gone and a rounded pane behind its text, the
 * card recipe at field size.
 */
class GlassField @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : EditText(context, attrs), Glassy {

    private val density = context.resources.displayMetrics.density

    init {
        background = null
        val h = (16 * density).toInt(); val v = (11 * density).toInt()
        setPadding(h, v, h, v)
        setTextColor(Panel.Palette.FG)
        setHintTextColor(Panel.Palette.DIM)
        UiType.dress(this)
    }

    override fun onDraw(canvas: Canvas) {
        if (width > 0 && height > 0) {
            Pane.draw(
                canvas, this, scrollX.toFloat(), scrollY.toFloat(),
                scrollX + width.toFloat(), scrollY + height.toFloat(),
                minOf(RADIUS_DP * density, height / 2f), Glass.CARD_TINT, interior = false,
            )
        }
        super.onDraw(canvas)
    }

    private companion object {
        const val RADIUS_DP = 18f
    }
}
