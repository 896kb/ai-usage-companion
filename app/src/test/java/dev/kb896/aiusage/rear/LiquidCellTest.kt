package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The liquid, checked for the things that would make it stop looking like one.
 *
 * Not checked for physical accuracy — it is not a solver and does not claim to be. What
 * these pin down is that drops stay in the glass, settle rather than jitter forever, and
 * pile toward whichever wall gravity points at, including the ones that are only "down"
 * when the phone is upside down.
 */
class LiquidCellTest {

    private val w = 100f
    private val h = 400f

    private fun settled(gx: Float, gy: Float, n: Int = 60, steps: Int = 400): LiquidCell {
        val cell = LiquidCell()
        cell.fillTo(n, w, h, seed = 7)
        repeat(steps) { cell.step(1f / 60f, gx, gy, w, h) }
        return cell
    }

    @Test fun `it fills to the number asked for, and no further`() {
        val cell = LiquidCell(capacity = 20)
        cell.fillTo(12, w, h, seed = 1)
        assertEquals(12, cell.size)
        cell.fillTo(500, w, h, seed = 1)
        assertEquals("went past its capacity", 20, cell.size)
        cell.fillTo(3, w, h, seed = 1)
        assertEquals("did not shed", 3, cell.size)
    }

    /** The one thing that must never happen: liquid outside the glass. */
    @Test fun `no drop ever leaves the cell`() {
        for ((gx, gy) in listOf(0f to 900f, 900f to 0f, -900f to 0f, 0f to -900f,
                                640f to 640f, -640f to -640f)) {
            val cell = settled(gx, gy)
            cell.forEach { d ->
                assertTrue("left the left wall at g=($gx,$gy): ${d.x}",
                    d.x >= LiquidCell.RADIUS - 0.5f)
                assertTrue("left the right wall at g=($gx,$gy): ${d.x}",
                    d.x <= w - LiquidCell.RADIUS + 0.5f)
                assertTrue("fell through the floor at g=($gx,$gy): ${d.y}",
                    d.y <= h - LiquidCell.RADIUS + 0.5f)
                assertTrue("climbed out of the top at g=($gx,$gy): ${d.y}",
                    d.y >= -LiquidCell.RADIUS * LiquidCell.HEADROOM - 0.5f)
            }
        }
    }

    /**
     * Turn the phone and the liquid goes with it. This is the whole reason the surface
     * stopped being a line: a line can only be level, and level is only meaningful while
     * one particular wall is the floor.
     */
    @Test fun `it pools toward whichever way gravity points`() {
        fun centre(cell: LiquidCell): Pair<Float, Float> {
            var sx = 0f; var sy = 0f; var n = 0
            cell.forEach { sx += it.x; sy += it.y; n++ }
            return sx / n to sy / n
        }
        val (_, downY) = centre(settled(0f, 900f))
        val (_, upY) = centre(settled(0f, -900f))
        assertTrue("did not fall to the floor: $downY", downY > h * 0.5f)
        assertTrue("did not rise to the ceiling when inverted: $upY", upY < h * 0.5f)

        val (leftX, _) = centre(settled(-900f, 0f))
        val (rightX, _) = centre(settled(900f, 0f))
        assertTrue("did not pool left: $leftX", leftX < w * 0.5f)
        assertTrue("did not pool right: $rightX", rightX > w * 0.5f)
    }

    /** Drops that never come to rest read as static, not as liquid. */
    @Test fun `it settles`() {
        val cell = settled(0f, 900f, steps = 900)
        var fastest = 0f
        cell.forEach { fastest = maxOf(fastest, Math.hypot(it.vx.toDouble(), it.vy.toDouble()).toFloat()) }
        assertTrue("still sloshing after fifteen seconds: $fastest", fastest < 30f)
    }

    /**
     * Without separation every drop falls to the same lowest point and the cell shows one
     * dense dot. Spread across the floor is what makes a heap of circles read as a body.
     */
    @Test fun `drops spread across the floor rather than stacking in a column`() {
        val cell = settled(0f, 900f)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        cell.forEach { lo = minOf(lo, it.x); hi = maxOf(hi, it.x) }
        assertTrue("piled into a column: $lo..$hi", hi - lo > w * 0.5f)
    }

    /**
     * A frame that arrives late must not teleport anything. On an always-on panel that
     * has just been woken, one will.
     */
    @Test fun `an enormous timestep does not fling drops through the walls`() {
        val cell = LiquidCell()
        cell.fillTo(40, w, h, seed = 3)
        cell.step(5f, 0f, 2_000f, w, h)
        cell.forEach {
            assertTrue("escaped on a 5s step: ${it.y}", it.y <= h - LiquidCell.RADIUS + 0.5f)
        }
    }

    @Test fun `an empty cell steps without complaint`() {
        LiquidCell().step(1f / 60f, 0f, 900f, w, h)
    }
}
