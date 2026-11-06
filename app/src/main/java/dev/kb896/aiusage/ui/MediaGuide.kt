package dev.kb896.aiusage.ui

import android.app.Activity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.kb896.aiusage.R

/**
 * How to use the media page, as the portal's guide shows the portal's (PortalGuideCard.showHelp):
 * where its drags and buttons are, on the phone's own back (tools/media-guide-still.py in the
 * notes), and the same in words.
 */
object MediaGuide {

    val GESTURES = listOf(
        "Drag up or down along the right edge: the volume, from wherever it is.",
        "Drag sideways above the buttons: move through the track.",
        "Tap the buttons at the bottom: previous, play or pause, next.",
    )

    fun show(activity: Activity) {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 5)
        }
        val radius = 20 * activity.resources.displayMetrics.density
        column.addView(ImageView(activity).apply {
            setImageResource(R.drawable.media_guide)
            adjustViewBounds = true
            contentDescription = "The back of the phone, with where the media page's drags and buttons are"
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        GESTURES.forEach { SettingsRows.note(column, "·  $it") }
        Modal.show(
            Modal.builder(activity)
                .setTitle("How to use the media page")
                .setView(ScrollView(activity).apply { addView(column) })
                .setPositiveButton("Done", null),
        )
    }
}
