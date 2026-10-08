package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the storm's lightning lights the glass, and how: -0.35 a little darker, 1 doubled. */
class LightningTest {

    private val tenMinutes = (0L until 600_000L step 10L).map { Lightning.flash(it) }

    /** Strikes: from quiet to a flash past half. */
    private fun strikes(values: List<Float>): Int {
        var n = 0
        var lit = false
        for (f in values) {
            if (!lit && f > 0.5f) { n++; lit = true }
            if (Math.abs(f) < 0.005f) lit = false
        }
        return n
    }

    @Test fun `it is quiet most of the time`() {
        assertTrue(tenMinutes.count { Math.abs(it) > 0.01f } < tenMinutes.size / 4)
    }

    @Test fun `a strike about every seven seconds`() {
        val n = strikes(tenMinutes)
        assertTrue("strikes $n in ten minutes", n in 65..110)
    }

    @Test fun `the gaps are irregular`() {
        val at = mutableListOf<Int>()
        var lit = false
        tenMinutes.forEachIndexed { i, f ->
            if (!lit && f > 0.5f) { at += i; lit = true }
            if (Math.abs(f) < 0.005f) lit = false
        }
        val gaps = at.zipWithNext { a, b -> (b - a) * 10 }
        assertTrue(gaps.max() - gaps.min() > 5_000)
    }

    @Test fun `it stays between a little darker and doubled`() {
        assertTrue(tenMinutes.all { it in -0.35f..1f })
        assertTrue(tenMinutes.any { it < -0.05f })
    }

    @Test fun `the same moment is always the same flash`() {
        assertEquals(Lightning.flash(123_456L), Lightning.flash(123_456L), 0f)
    }

    @Test fun `a strike flickers — it dips and comes back before it is over`() {
        var dipped = 0
        for (i in 2 until tenMinutes.size) {
            val a = tenMinutes[i - 2]; val b = tenMinutes[i - 1]; val c = tenMinutes[i]
            if (b < a && c > b && a > 0.2f && c > 0.2f) dipped++
        }
        assertTrue(dipped > 20)
    }
}
