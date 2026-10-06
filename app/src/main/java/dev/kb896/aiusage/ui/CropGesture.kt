package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import dev.kb896.aiusage.rear.ArtPlacement

/**
 * Drag to move, pinch to zoom, as fractions ArtPlacement understands — the one gesture
 * both croppers use, so a picture and a video move the same under a finger.
 */
class CropGesture(
    context: Context,
    private val region: () -> RectF,
    private val content: () -> Pair<Int, Int>?,
    private val changed: () -> Unit,
) {
    var panX = 0f
    var panY = 0f
    var zoom = 1f

    private val scale = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(ArtPlacement.MIN_ZOOM, ArtPlacement.MAX_ZOOM)
                changed()
                return true
            }
        })
    private var lastX = 0f
    private var lastY = 0f

    fun onTouch(event: MotionEvent) {
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> if (!scale.isInProgress) {
                val size = content() ?: return
                // Pan is a fraction of the slack, so a drag moves further when more is hidden
                // — which is what makes it feel like moving the picture, not scrubbing a value.
                val (slackX, slackY) = ArtPlacement.slack(region(), size.first, size.second, zoom)
                if (slackX > 0) panX = (panX + (event.x - lastX) / slackX).coerceIn(-1f, 1f)
                if (slackY > 0) panY = (panY + (event.y - lastY) / slackY).coerceIn(-1f, 1f)
                lastX = event.x; lastY = event.y
                changed()
            }
        }
    }
}
