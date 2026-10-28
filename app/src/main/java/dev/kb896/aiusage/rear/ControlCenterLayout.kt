package dev.kb896.aiusage.rear

import android.graphics.RectF
import dev.kb896.aiusage.Panel

/**
 * Where the control centre's pieces stand, in panel units, at a pull of [progress]: the
 * brightness cell, the automatic-brightness button under it, and the four tiles. Moved out
 * of RearDialView (brightCellRect, autoButtonRect, quickTileRect, grown, ccShift) so the
 * panel and the shell's control centre lay out, and hit-test, alike.
 *
 * Each piece arrives as Arrival has it: slid down into place from [shift] above it, and
 * grown about its own middle ([grown]) — not the lot from one point.
 */
object ControlCenterLayout {

    /** Between the brightness cell and the round button under it. */
    const val BUTTON_GAP = 14f

    /** What [hit] found: a tile is its index, 0 to 3 (silent, Bluetooth, mobile data, Wi-Fi). */
    const val NONE = -1
    const val AUTO = 4
    const val CELL = 5

    /**
     * How far above its place the contents are: from a quarter of the panel — half of the
     * half of the pull they are shown for — to nothing as they arrive.
     */
    fun shift(progress: Float): Float = -Arrival.offset(progress, Panel.HEIGHT.toFloat())

    /**
     * [r] grown into its place about its own middle: from Arrival.GROW_FROM of its size as
     * the contents start to arrive, to its whole size when they have.
     */
    fun grown(r: RectF, progress: Float): RectF {
        val g = Arrival.scale(progress)
        if (g >= 0.9999f) return r
        val dx = r.width() * (1f - g) / 2f
        val dy = r.height() * (1f - g) / 2f
        return RectF(r.left + dx, r.top + dy, r.right - dx, r.bottom - dy)
    }

    /**
     * The brightness cell where it stands, slid down by [progress]: the charging cell's
     * width, short of the bottom by the round button under it.
     */
    fun cellRect(progress: Float): RectF {
        val w = ChargeOverlay.width(Panel.WIDTH.toFloat())
        val left = Panel.CUTOUT_RIGHT + ChargeOverlay.MARGIN
        val shift = shift(progress)
        val bottom = Panel.HEIGHT - ChargeOverlay.MARGIN - w - BUTTON_GAP
        return grown(RectF(left, ChargeOverlay.MARGIN + shift, left + w, bottom + shift), progress)
    }

    /** The automatic-brightness button under the cell: a disc as wide as the cell. */
    fun autoRect(progress: Float): RectF {
        val w = ChargeOverlay.width(Panel.WIDTH.toFloat())
        val left = Panel.CUTOUT_RIGHT + ChargeOverlay.MARGIN
        val top = Panel.HEIGHT - ChargeOverlay.MARGIN - w + shift(progress)
        return grown(RectF(left, top, left + w, top + w), progress)
    }

    /**
     * The four tiles, two by two in the room right of the brightness: one gap between them,
     * the same to the brightness, the panel's top, right and bottom.
     */
    fun tileRect(i: Int, progress: Float): RectF {
        val g = ChargeOverlay.MARGIN
        val left = Panel.CUTOUT_RIGHT + ChargeOverlay.MARGIN + ChargeOverlay.width(Panel.WIDTH.toFloat()) + g
        val right = Panel.WIDTH - g
        val w = (right - left - g) / 2f
        val h = (Panel.HEIGHT - 3f * g) / 2f
        val col = i % 2
        val row = i / 2
        val shift = shift(progress)
        val x = left + col * (w + g)
        val y = g + row * (h + g) + shift
        return grown(RectF(x, y, x + w, y + h), progress)
    }

    /** What a finger at [x], [y] lands on, the centre open: [CELL], [AUTO], a tile's index, or [NONE]. */
    fun hit(x: Float, y: Float): Int = when {
        cellRect(1f).contains(x, y) -> CELL
        autoRect(1f).contains(x, y) -> AUTO
        else -> (0 until 4).firstOrNull { tileRect(it, 1f).contains(x, y) } ?: NONE
    }
}
