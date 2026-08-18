package dev.kb896.aiusage.rear

import android.graphics.RectF

/**
 * Where a cropped image sits inside its region.
 *
 * Extracted because two places need it and they must not disagree: the panel draws the
 * result, and the cropper shows what the result will be. A cropper working from its own
 * copy of this arithmetic is a cropper that eventually lies, and the whole point of it is
 * to be believed.
 */
object ArtPlacement {

    const val MIN_ZOOM = 1f

    /** Beyond this there is no detail left to reveal, only pixels to magnify. */
    const val MAX_ZOOM = 4f

    /**
     * @param panX pan as a fraction of the slack at this zoom, -1..1
     * @param zoom relative to "just covers the region", so 1 is sensible for any image
     * @return where to draw the bitmap so it covers [region] and is panned as asked
     */
    fun destination(
        region: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int,
        panX: Float,
        panY: Float,
        zoom: Float,
    ): RectF {
        // Cover, not fit: the region must never show through, so the larger ratio wins
        // and the overflow on the other axis is what panning moves through.
        val cover = maxOf(region.width() / bitmapWidth, region.height() / bitmapHeight)
        val scale = cover * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val w = bitmapWidth * scale
        val h = bitmapHeight * scale
        val slackX = (w - region.width()) / 2f
        val slackY = (h - region.height()) / 2f
        val left = region.centerX() - w / 2f + slackX * panX.coerceIn(-1f, 1f)
        val top = region.centerY() - h / 2f + slackY * panY.coerceIn(-1f, 1f)
        return RectF(left, top, left + w, top + h)
    }

    /** How far the image can move on each axis, in pixels — what a drag is a fraction of. */
    fun slack(region: RectF, bitmapWidth: Int, bitmapHeight: Int, zoom: Float): Pair<Float, Float> {
        val cover = maxOf(region.width() / bitmapWidth, region.height() / bitmapHeight)
        val scale = cover * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        return ((bitmapWidth * scale - region.width()) / 2f) to
            ((bitmapHeight * scale - region.height()) / 2f)
    }
}
