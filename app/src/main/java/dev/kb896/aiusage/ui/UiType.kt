package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Typeface
import dev.kb896.aiusage.R
import dev.kb896.aiusage.rear.PanelType

/**
 * The front screen's face: Outfit at SemiBold, always — the front is glass whatever the
 * back is (UiTheme), and the font chosen for the back is the back's alone.
 *
 * THE WEIGHT IS IN THE FACE. Outfit is one variable file whose default instance is Thin,
 * and the front has a couple of dozen places that set the face without its weight. They
 * only looked right while the activity's one sweep reached them; anything built after it —
 * every card rebuilt when a setting changed — came out Thin, and stayed so. Built from a
 * copy of the file with the weight set on its axis, the face is SemiBold wherever it goes,
 * and a lighter weight asked of it on top (CardTitle's 500) still applies.
 */
object UiType {
    @Volatile private var face: Typeface? = null

    fun of(context: Context): Typeface =
        face ?: synchronized(this) { face ?: build(context.applicationContext).also { face = it } }

    private fun build(context: Context): Typeface = try {
        val file = java.io.File(context.cacheDir, "outfit.ttf")
        if (!file.exists()) context.resources.openRawResource(R.font.outfit).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        Typeface.Builder(file).setFontVariationSettings("'wght' ${PanelType.GLASS_WEIGHT}").build()
    } catch (_: Exception) {
        PanelType.of(context.resources, dev.kb896.aiusage.data.PanelTheme.GLASS)
    }

    /**
     * The axes to set alongside the face: none — the weight is already in it. Kept for the
     * callers that set both, which then clear any weight a view had been given.
     */
    @Suppress("UNUSED_PARAMETER")
    fun variation(context: Context): String? = null

    /** The front's face on [view]. */
    fun dress(view: android.widget.TextView) {
        PanelType.weigh(view, of(view.context), variation(view.context))
    }
}
