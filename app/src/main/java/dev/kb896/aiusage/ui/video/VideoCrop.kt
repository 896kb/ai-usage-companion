package dev.kb896.aiusage.ui.video

import android.graphics.RectF
import dev.kb896.aiusage.rear.ArtPlacement

/**
 * The editor's frame, as the crop Media3 applies. The editor places the video with the
 * panel's own ArtPlacement, so the clip is cut from exactly what the frame showed: the
 * region's corners, as fractions of where the video was drawn, in Media3's normalised
 * device coordinates (x right, y UP, -1..1).
 */
object VideoCrop {
    /** A video's size as it is shown: a phone's portrait clip is stored sideways with a rotation. */
    fun displaySize(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
        if (rotation % 180 != 0) height to width else width to height

    /** left, right, bottom, top — the order androidx.media3.effect.Crop takes. */
    fun ndc(region: RectF, dest: RectF): FloatArray {
        fun x(v: Float) = ((v - dest.left) / dest.width() * 2f - 1f).coerceIn(-1f, 1f)
        fun y(v: Float) = (1f - (v - dest.top) / dest.height() * 2f).coerceIn(-1f, 1f)
        return floatArrayOf(x(region.left), x(region.right), y(region.bottom), y(region.top))
    }

    fun forPan(region: RectF, videoW: Int, videoH: Int, panX: Float, panY: Float, zoom: Float): FloatArray =
        ndc(region, ArtPlacement.destination(region, videoW, videoH, panX, panY, zoom))
}
