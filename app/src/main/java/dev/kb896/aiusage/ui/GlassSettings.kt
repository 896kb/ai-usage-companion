package dev.kb896.aiusage.ui

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.GlassReactivity
import dev.kb896.aiusage.data.GlassStyle
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.rear.RearDialView

/**
 * Glass settings: the back's glass tried out before it is chosen. At the top the lab — the
 * background alone with a strip from black to white under it, and a round piece of the
 * back's own glass to drag over both — and under it what the glass is made of: how it
 * reacts to what is behind it, its tint and how strong. Every change shows in the lab at
 * once and is kept at once ([onKeep]), so the back follows as well.
 */
object GlassSettings {
    fun show(activity: Activity, config: PanelConfig, onKeep: (GlassStyle) -> Unit) {
        var style = config.glassStyle ?: GlassStyle.DEFAULT
        val d = activity.resources.displayMetrics.density
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        val lab = LabView(activity, config)
        sheet.addView(lab)
        fun choose(next: GlassStyle, keep: Boolean) {
            style = next
            lab.style = next
            if (keep) onKeep(next)
        }
        lab.style = style

        val modes = GlassReactivity.entries
        SettingsRows.note(sheet, "Luminance reactivity")
        sheet.addView(
            dev.kb896.aiusage.ui.glass.GlassSegmented(activity, listOf("None", "Global", "Shard"), style.reactivity.ordinal).apply {
                onChange = { i -> choose(style.copy(reactivity = modes[i]), keep = true) }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * d).toInt() },
        )
        SettingsRows.colorRow(sheet, "Tint", style.color) { c -> choose(style.copy(color = c or (0xFF shl 24)), keep = true) }
        SettingsRows.slider(sheet, 100, Math.round(style.intensity * 100),
            text = { "Tint intensity  $it%" },
            onMove = { p -> choose(style.copy(intensity = p / 100f), keep = false) },
        ) { p -> choose(style.copy(intensity = p / 100f), keep = true) }

        Modal.show(Modal.builder(activity)
            .setTitle("Glass settings")
            .setView(android.widget.ScrollView(activity).apply { addView(sheet) })
            .setPositiveButton("Done", null)
            .setOnDismissListener { lab.release() })
    }

    /**
     * The back's own panel view in its lab mode, scaled to this width, with the piece of
     * glass under the finger. See RearDialView.Lab.
     */
    private class LabView(context: Context, config: PanelConfig) : FrameLayout(context) {
        private val dial = RearDialView(context)
        private val base = config.copy(crt = false, parallaxTilt = false, parallaxScroll = false)
        /** The piece to drag starts on the background, clear of the three on the strip. */
        private val lab = RearDialView.Lab(Panel.WIDTH / 2f, Panel.HEIGHT / 2f)

        var style: GlassStyle = GlassStyle.DEFAULT
            set(value) { field = value; dial.config = base.copy(glassStyle = value) }

        init {
            dial.interactive = false
            dial.lab = lab
            addView(dial, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            // Rounded as the back is, so the top reads as the panel.
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height,
                        view.width * dev.kb896.aiusage.ui.video.RearSize.cornerShare(view.context))
                }
            }
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            val h = (w * RearDialView.Lab.HEIGHT / Panel.WIDTH).toInt()
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }

        override fun onInterceptTouchEvent(ev: MotionEvent) = true

        @Suppress("ClickableViewAccessibility")   // a piece of glass to drag, not a button
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
            val unit = width / Panel.WIDTH.toFloat()
            if (unit <= 0f) return true
            val r = RearDialView.Lab.RADIUS
            lab.cx = (e.x / unit).coerceIn(r, Panel.WIDTH - r)
            lab.cy = (e.y / unit).coerceIn(r, RearDialView.Lab.HEIGHT - r)
            dial.invalidate()
            return true
        }

        fun release() {
            dial.lab = null
        }
    }
}
