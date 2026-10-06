package dev.kb896.aiusage.rear

import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.PanelFont
import dev.kb896.aiusage.data.PanelTheme
import java.io.File

/**
 * The face each theme is set in, and the corrections that come with changing it.
 *
 * A FONT IS NOT A COLOUR. Everything on this panel was positioned against Departure
 * Mono: baselines, column widths, the gap that stops a percentage running into a bar.
 * Swapping in a proportional face at the same numbers does not give you the same layout
 * in a different style, it gives you a broken one — so the two things that change with
 * the face travel with it here rather than being scattered through the drawing code.
 *
 * WHAT CHANGES. Outfit's lowercase is tall relative to its cap height and its digits are
 * narrower than a monospaced cell, so at a shared point size it reads SMALLER than
 * Departure Mono while occupying less width. Both are corrected by [Face.scale] rather than
 * by retuning forty text sizes, which is the only way to keep the two themes from
 * drifting apart every time one of them is adjusted.
 */
object PanelType {

    /**
     * The face itself.
     *
     * Cached because this is asked for on every frame by several paints, and
     * `Resources.getFont` is a parse and an allocation — it is not a lookup.
     */
    fun of(res: Resources, theme: PanelTheme): Typeface =
        cache.getOrPut(theme) {
            when (theme) {
                PanelTheme.ORIGINAL -> res.getFont(R.font.departure_mono)
                PanelTheme.GLASS -> res.getFont(R.font.outfit)
            }
        }

    /** The ids of the two faces the app ships, as a [PanelFont.bundled] names them. */
    const val OUTFIT = "outfit"
    const val DEPARTURE = "departure"

    /**
     * The panel's face: [font] when one was chosen and can still be built, the theme's
     * own otherwise — a chosen file that has gone (an imported font deleted, a system
     * update that renamed a file) falls back rather than drawing nothing.
     */
    @Synchronized
    fun face(res: Resources, theme: PanelTheme, font: PanelFont?): Face =
        faces.getOrPut(theme to font) {
            font?.let { build(res, it) } ?: bundled(res, if (theme == PanelTheme.ORIGINAL) DEPARTURE else OUTFIT)!!
        }

    private val faces = HashMap<Pair<PanelTheme, PanelFont?>, Face>()

    private fun build(res: Resources, font: PanelFont): Face? =
        font.bundled?.let { bundled(res, it) } ?: try { fromFiles(res, font) } catch (_: Exception) { null }

    private fun bundled(res: Resources, id: String): Face? = when (id) {
        DEPARTURE -> Face(of(res, PanelTheme.ORIGINAL), variable = false, weighted = false, scale = 1f)
        OUTFIT -> Face(of(res, PanelTheme.GLASS), variable = true, weighted = true, scale = GLASS_SCALE)
        else -> null
    }

    /**
     * A face from files on the phone. One with a weight axis is the whole family on its
     * own; otherwise every weight is a file, and the face asks for the nearest. Glyphs it
     * lacks — an emoji, a script it does not cover — come from the system's sans, as they
     * do under the faces the app ships.
     */
    private fun fromFiles(res: Resources, font: PanelFont): Face? {
        val files = font.files.filter { File(it.path).canRead() }
        if (files.isEmpty()) return null
        fun load(f: dev.kb896.aiusage.data.PanelFontFile) =
            Font.Builder(File(f.path)).setTtcIndex(f.index).setWeight(f.weight)
                .setSlant(FontStyle.FONT_SLANT_UPRIGHT).build()
        val axis = files.firstOrNull { it.variable }
        val family = if (axis != null) FontFamily.Builder(load(axis)).build() else {
            val byWeight = files.distinctBy { it.weight }
            FontFamily.Builder(load(byWeight[0])).apply {
                byWeight.drop(1).forEach { addFont(load(it)) }
            }.build()
        }
        fun at(weight: Int): Typeface = Typeface.CustomFallbackBuilder(family)
            .setSystemFallback("sans-serif")
            .setStyle(FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT))
            .build()
        val face = Face(at(BODY), variable = axis != null, weighted = axis == null && files.size > 1, scale = 1f, byWeight = ::at)
        return face.copy(scale = matched(res, face))
    }

    /**
     * Sized to read as Outfit does at the panel's sizes: its capitals as tall as Outfit's
     * at [GLASS_SCALE]. Faces differ a lot in how much of the em their letters take, and a
     * chosen one set at the same numbers came out either crowding the lines or lost in them.
     */
    private fun matched(res: Resources, face: Face): Float {
        val paint = Paint().apply { textSize = 100f }
        val bounds = Rect()
        fun capHeight(f: Face): Float {
            f.set(paint, BODY)
            paint.getTextBounds("H", 0, 1, bounds)
            return bounds.height().toFloat()
        }
        val outfit = capHeight(bundled(res, OUTFIT)!!)
        val own = capHeight(face)
        if (own <= 0f) return GLASS_SCALE
        return (GLASS_SCALE * outfit / own).coerceIn(0.8f, 1.5f)
    }

    /**
     * A face at the panel's weights: [set] puts it on a paint at one of them. A variable
     * face takes the weight on its axis; a family of files picks the file; a face of one
     * weight (Departure Mono, a single imported file) stays as it is.
     */
    data class Face(
        val base: Typeface,
        val variable: Boolean,
        val weighted: Boolean,
        /** What to multiply a text size by — see [matched]. */
        val scale: Float,
        val byWeight: ((Int) -> Typeface)? = null,
    ) {
        private val weights = HashMap<Int, Typeface>()

        fun typeface(weight: Int): Typeface =
            if (!weighted || byWeight == null) base else weights.getOrPut(weight) { byWeight.invoke(weight) }

        fun variation(weight: Int): String? = if (variable) "'wght' $weight" else null

        /** Cleared as well as set: a face without an axis must take a previous one's off. */
        fun set(paint: Paint, weight: Int) {
            paint.typeface = typeface(weight)
            paint.fontVariationSettings = variation(weight)
        }
    }

    /** The panel's weights: body, the masthead's, the charging figure's, a track's name. */
    const val BODY = 600 // GLASS_WEIGHT, below: a const cannot read one declared after it
    const val TITLE = 800
    const val HEAVIEST = 900
    const val MEDIA = 400

    /**
     * The variation axes to set on a paint, or null if the face has none.
     *
     * THIS IS NOT WHAT Typeface.create DOES, and the difference is the whole reason this
     * exists. `Typeface.create(face, 400, false)` picks a weight from a FAMILY — several
     * files, one per weight — and a variable font is one file with an axis. Handed one,
     * it has nothing to choose between and returns the face's default instance, which
     * for Outfit[wght] is Thin. The panel came up drawn in hairlines and the request for
     * Regular had quietly done nothing.
     *
     * Set on the Paint rather than baked into the Typeface because that is where the API
     * for it is: Paint.setFontVariationSettings drives the axis directly.
     */
    fun variation(theme: PanelTheme): String? =
        if (theme == PanelTheme.GLASS) "'wght' $GLASS_WEIGHT" else null

    private val cache = HashMap<PanelTheme, Typeface>(2)

    /** How much taller Outfit has to be set to match Departure Mono's presence. */
    const val GLASS_SCALE = 1.12f

    /**
     * Where on the variable axis the panel's body is cut. 600 is SemiBold.
     *
     * Heavier than a page of prose would be set, because this is not one. The panel is
     * read at arm's length, on the back of a phone, in a glance — and every string on it
     * is a label or a number rather than a sentence. Regular held up at the sizes the
     * masthead uses and went thin everywhere else.
     */
    const val GLASS_WEIGHT = 600
}
