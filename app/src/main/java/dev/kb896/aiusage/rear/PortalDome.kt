package dev.kb896.aiusage.rear

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The App Portal's sheet as a dome (PanelConfig.portalDome; the owner, 2026-11-10, after the
 * web's "dome gallery"): the pinned apps as tiles over a bulge towards you, moved by a finger
 * in any direction with no end to it either way — big and square in the middle, and towards
 * the rim smaller, turned away (squashed along the way out, as a tile on a ball is seen) and
 * blurring away into the edges of their part of the sheet. Pure, as PortalSheet's grid is;
 * PortalSheetPainter draws it, DomeMotion moves it.
 *
 * A SHEET OF TILES LAID OVER A BALL, NOT A BALL OF TILES. It was a ball first, tiles by
 * longitude and latitude: they crowded and overlapped towards its poles, and it could not turn
 * up and down for ever — past a pole it is upside down (the owner, 2026-11-10: "apps should not
 * overlap", "even up down should be infinite"). So the tiles stand on a flat sheet with no end,
 * evenly, every other row half a step along, and the sheet is what a finger moves; each tile
 * is then shown where the ball would carry it — as far round the ball as it is from the middle
 * of the sheet. Evenly spread tiles stay apart all the way out, and there are no poles.
 *
 * THE SHEET HAS NO END BUT THE APPS DO, so they repeat over it, each row starting further along
 * the list than the last ([ROW_SHIFT]) so an app does not stand over itself.
 *
 * THE PORTAL'S SHEET STILL NEEDS ITS OWN DRAGS — down to shut, up for the sliders — and a dome
 * that goes every way would take them all. So a finger does not move the dome from everywhere
 * ([turns]): a drag begun on the buttons' row (a strip of [TOP_STRIP] where there are none) or
 * on a strip of [BOTTOM_STRIP] at the bottom moves the sheet as ever. THE DOME ITSELF IS DRAWN
 * OVER ALL OF IT ([area], the owner, 2026-11-10: "as big as the usable area of the screen"):
 * the strips are where a drag goes, not somewhere the dome is not.
 */
object PortalDome {

    /** How far the sheet of tiles has been moved, in pixels: [x] along, [y] down. */
    data class Pose(val x: Float, val y: Float)

    /**
     * One app's tile as it shows: app [app], its middle at ([cx], [cy]), [half] its half side
     * before the ball turns it; turned, it is [along] of that along the way out from the dome's
     * middle (the direction [turn], in radians) and [across] of it across; [alpha] how much there.
     */
    class Tile(
        val app: Int, val cx: Float, val cy: Float, val half: Float,
        val turn: Float, val along: Float, val across: Float, val alpha: Float, val depth: Float,
    )

    private const val ROW_SHIFT = 29
    const val TOP_STRIP = 60f
    const val BOTTOM_STRIP = 84f
    /** Over this much in from each edge of its [area] the dome blurs and fades away (PortalSheetPainter). */
    const val MARGIN = 64f
    /** On the left, towards the lenses, it does so over this much: longer, and eased. */
    const val MARGIN_LEFT = 120f
    /** From one tile to the next on the sheet, in the width of the dome's [area]. */
    private const val STEP = 0.27f
    /** A tile's side, in that step. */
    private const val TILE = 0.76f
    /** The ball's radius, in the longer side of its [area]: smaller, it curves away sooner. */
    private const val RADIUS = 0.7f
    /** A tile this far round the ball (radians) is not shown. */
    private const val ROUND_MAX = 1.35f

    /**
     * Where the dome is drawn on the sheet laid out as [l]: top to bottom, and out to the
     * panel's right edge — past where the grid stops short of it — and as far past the grid's
     * left as that is past its right (the owner, 2026-11-10: "to the whole right … and add the
     * same distance to the left"), so the dome's middle is the grid's.
     */
    fun area(l: PortalSheet.Layout): PortalSheet.Box {
        val right = dev.kb896.aiusage.Panel.WIDTH.toFloat()
        val more = right - (PortalSheet.PAGES_RIGHT - EdgeFade.BAND)
        return PortalSheet.Box(l.left - more, 0f, right, PortalSheet.FULL)
    }

    /**
     * Where a drag moves the dome: its [area] but the buttons' row or the top strip — and, with
     * an app on the back, the bottom strip: that one is for pulling the app's sliders up, and
     * with no app there is nothing under the sheet to pull (the owner, 2026-11-10), so the dome
     * has the bottom too.
     */
    fun turns(l: PortalSheet.Layout): PortalSheet.Box {
        val a = area(l)
        val top = l.buttons.maxOfOrNull { it.bottom }?.plus(8f) ?: TOP_STRIP
        return PortalSheet.Box(a.left, top, a.right, a.bottom - if (pullsUp(l)) BOTTOM_STRIP else 0f)
    }

    /** Whether the sheet laid out as [l] has anything to pull up from its bottom: an app's sliders. */
    fun pullsUp(l: PortalSheet.Layout): Boolean = l.slider != null

    fun step(area: PortalSheet.Box): Float = area.width * STEP

    /**
     * The tiles that show for [count] apps with the sheet moved to [pose], in [area], the
     * furthest round the ball first (so the nearer are drawn over them). None for no apps.
     */
    fun tiles(area: PortalSheet.Box, count: Int, pose: Pose): List<Tile> {
        if (count <= 0) return emptyList()
        val r = max(area.width, area.height) * RADIUS
        val step = step(area)
        val half = step * TILE / 2f
        val reach = r * ROUND_MAX
        val out = ArrayList<Tile>(40)
        val rowFrom = floor((pose.y - reach) / step).toInt()
        val rowTo = ceil((pose.y + reach) / step).toInt()
        for (row in rowFrom..rowTo) {
            // Every other row half a step along, as bricks are laid.
            val off = if (Math.floorMod(row, 2) != 0) step / 2f else 0f
            val py = row * step - pose.y
            val colFrom = floor((pose.x - reach - off) / step).toInt()
            val colTo = ceil((pose.x + reach - off) / step).toInt()
            for (col in colFrom..colTo) {
                val px = col * step + off - pose.x
                val d = sqrt(px * px + py * py)
                val round = d / r
                if (round > ROUND_MAX) continue
                // As far out on the glass as the ball carries it, and turned away by as much.
                val k = if (d < 0.001f) 1f else r * sin(round) / d
                val cx = area.centerX + px * k
                val cy = area.centerY + py * k
                val along = cos(round)
                val across = if (round < 0.001f) 1f else sin(round) / round
                if (cx + half < area.left || cx - half > area.right || cy + half < area.top || cy - half > area.bottom) continue
                // Fading into the ball's rim; the edges of its part of the sheet blur and fade
                // the whole picture ([MARGIN]), not tile by tile.
                val alpha = smooth((ROUND_MAX - round) / 0.35f)
                if (alpha <= 0.01f) continue
                out += Tile(
                    Math.floorMod(row * ROW_SHIFT + col, count), cx, cy, half,
                    atan2(py, px), along, across, alpha, along,
                )
            }
        }
        out.sortBy { it.depth }
        return out
    }

    /** The app whose tile a finger at [x], [y] is on, the nearest tile first; null for none. */
    fun hit(tiles: List<Tile>, x: Float, y: Float): Int? =
        tiles.asReversed().firstOrNull { it.alpha > 0.35f && covers(it, x, y) }?.app

    /**
     * Whether [x], [y] is on [t] as it is drawn: its square narrowed along the way out and
     * across it (not turned — the square's sides stay upright and lean).
     */
    fun covers(t: Tile, x: Float, y: Float): Boolean {
        val dx = x - t.cx
        val dy = y - t.cy
        val c = cos(t.turn); val s = sin(t.turn)
        // Along the way out and across it, each undone, and back to the square's own sides.
        val a = (dx * c + dy * s) / t.along.coerceAtLeast(0.001f)
        val b = (-dx * s + dy * c) / t.across.coerceAtLeast(0.001f)
        return abs(a * c - b * s) <= t.half && abs(a * s + b * c) <= t.half
    }

    private fun smooth(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
