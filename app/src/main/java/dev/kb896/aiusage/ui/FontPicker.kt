package dev.kb896.aiusage.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnLayout
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.PanelFont
import dev.kb896.aiusage.data.PanelTheme
import dev.kb896.aiusage.rear.PanelType
import dev.kb896.aiusage.ui.glass.CardTitle
import dev.kb896.aiusage.ui.glass.Ink

/**
 * The panel's font: every face it can be set in, each name written in its own face, the
 * chosen one ticked. Import first, then the theme's own face, the two the app ships,
 * imported ones (a long press removes them) and the phone's families — found in the
 * background, as that means opening every font file on it, with a glass spinner turning meanwhile.
 * The spinner fades out and the fonts slide in from the left, fading up one after another.
 */
object FontPicker {

    fun show(
        context: Context,
        theme: PanelTheme,
        current: PanelFont?,
        onPick: (PanelFont?) -> Unit,
        onImport: () -> Unit,
        onRemove: (PanelFont) -> Unit,
    ) {
        val res = context.resources
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 8))
        }
        lateinit var dialog: AlertDialog
        // Each row with its face, put back after Modal's sweep sets the app's face on every
        // text in the dialog.
        val faced = mutableListOf<Pair<TextView, PanelType.Face>>()

        fun dress(row: TextView, face: PanelType.Face) {
            row.typeface = face.typeface(PanelType.MEDIA)
            row.fontVariationSettings = face.variation(PanelType.MEDIA)
        }

        fun row(name: String, font: PanelFont?, face: PanelType.Face, at: Int = box.childCount): TextView {
            val chosen = font == current
            val row = TextView(context).apply {
                text = name
                // Every name at the size the panel would set it, so a small face looks small.
                textSize = 19f * face.scale / PanelType.GLASS_SCALE
                setTextColor(Ink.primary(0f))
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(context, 48)
                setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
                if (chosen) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(context, 14).toFloat()
                        setColor(0x1FFFFFFF)
                    }
                    setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_check, 0)
                    compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ink.accent(0f))
                }
                setOnClickListener { dialog.dismiss(); onPick(font) }
                if (font != null && PanelFonts.isImported(context, font)) setOnLongClickListener {
                    Modal.show(Modal.builder(context)
                        .setTitle("Remove ${font.name}?")
                        .setMessage("Its files are deleted from the app. The panel goes back to its theme's font if it was set in it.")
                        .setNegativeButton("Keep", null)
                        .setPositiveButton("Remove") { _, _ -> dialog.dismiss(); onRemove(font) },
                        danger = true)
                    true
                }
            }
            dress(row, face)
            faced += row to face
            box.addView(row, at, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 2) })
            return row
        }

        fun heading(text: String, at: Int = box.childCount): TextView =
            TextView(context).apply {
                this.text = text
                CardTitle.style(this)
                setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 4))
                box.addView(this, at)
            }

        SettingsRows.navigation(box, "Import a font file") { dialog.dismiss(); onImport() }
        val own = PanelType.face(res, theme, null)
        row("Theme default", null, own)
        for (font in PanelFonts.bundled) row(font.name, font, PanelType.face(res, theme, font))
        val imported = PanelFonts.imported(context)
        if (imported.isNotEmpty()) {
            heading("Imported")
            for (font in imported) row(font.name, font, PanelType.face(res, theme, font))
        }
        val phoneHeading = heading("On this phone")
        val looking = dev.kb896.aiusage.ui.glass.GlassSpinner(context).also { box.addView(it) }

        val scroll = ScrollView(context).apply { addView(box) }
        // No title: the row that opened it already says Font, and the list starts at once.
        dialog = Modal.show(Modal.builder(context)
            .setView(scroll)
            .setNegativeButton("Close", null))
        // The sheet keeps the height it opened at: the phone's fonts arriving scroll inside
        // it rather than stretching it.
        scroll.doOnLayout { scroll.layoutParams = scroll.layoutParams.apply { height = scroll.height } }
        faced.forEach { (row, face) -> dress(row, face) }

        Thread {
            val found = PanelFonts.system().map { it to PanelType.face(res, theme, it) }
            box.post {
                if (!dialog.isShowing) return@post
                // The spinner fades out; then the fonts come in one after another, each
                // sliding in from the left as it fades up, a beat behind the one above.
                looking.animate().alpha(0f).setDuration(SPINNER_OUT_MS).withEndAction {
                    var at = box.indexOfChild(looking)
                    box.removeView(looking)
                    if (found.isEmpty()) { box.removeView(phoneHeading); return@withEndAction }
                    val from = -dp(context, 28).toFloat()
                    found.forEachIndexed { i, (font, face) ->
                        row(font.name, font, face, at++).apply {
                            alpha = 0f
                            translationX = from
                            animate().alpha(1f).translationX(0f)
                                // Capped, so a long list below the fold is not still waiting
                                // its turn when it is scrolled to.
                                .setStartDelay(ROW_STAGGER_MS * minOf(i, ROW_STAGGER_CAP))
                                .setDuration(ROW_IN_MS)
                                .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                        }
                    }
                }
            }
        }.start()
    }

    private const val SPINNER_OUT_MS = 160L
    private const val ROW_IN_MS = 240L
    private const val ROW_STAGGER_MS = 35L
    private const val ROW_STAGGER_CAP = 14

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
