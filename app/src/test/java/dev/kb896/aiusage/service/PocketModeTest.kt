package dev.kb896.aiusage.service

import dev.kb896.aiusage.service.PocketMode.Act
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pocket mode: covered while lit switches the back off, uncovered brings it back — but only
 * a back pocket mode itself switched off. The cases that matter are the ones where it must
 * do nothing: a finger passing over the sensor, and a back already dark when it was covered.
 */
class PocketModeTest {
    private val d = 400L

    private fun litAndCovered(at: Long = 0L) = PocketMode(d).apply {
        lit(true, at)
        assertEquals(Act.NONE, reading(covered = true, now = at))
    }

    @Test fun `covered while lit for the debounce switches the back off`() {
        val p = litAndCovered()
        assertEquals(d, p.dueAt())
        assertEquals(Act.NONE, p.due(d - 1, lettingGo = false))
        assertFalse(p.pocketed)
        assertEquals(Act.SLEEP, p.due(d, lettingGo = false))
        assertTrue(p.pocketed)
    }

    @Test fun `a finger passing over the sensor does nothing`() {
        val p = litAndCovered()
        assertEquals(Act.NONE, p.reading(covered = false, now = 200))
        assertEquals(-1L, p.dueAt())
        assertEquals(Act.NONE, p.due(d, lettingGo = false))
        assertFalse(p.pocketed)
    }

    @Test fun `covered again after a gap counts from the new cover`() {
        val p = litAndCovered()
        p.reading(covered = false, now = 300)
        p.reading(covered = true, now = 350)
        assertEquals(350 + d, p.dueAt())
        assertEquals(Act.NONE, p.due(d, lettingGo = false))
        assertEquals(Act.SLEEP, p.due(350 + d, lettingGo = false))
    }

    @Test fun `a repeated covered reading does not restart the count`() {
        val p = litAndCovered()
        p.reading(covered = true, now = 300)
        assertEquals(d, p.dueAt())
    }

    @Test fun `uncovered after the switch-off restores it`() {
        val p = litAndCovered()
        p.due(d, lettingGo = false)
        p.lit(false, d + 50) // the back going dark is the switch-off itself
        assertTrue(p.pocketed)
        assertEquals(Act.RESTORE, p.reading(covered = false, now = 9_000))
        assertFalse(p.pocketed)
    }

    @Test fun `still in the pocket, covered again, is nothing`() {
        val p = litAndCovered()
        p.due(d, lettingGo = false)
        p.lit(false, d + 50)
        assertEquals(Act.NONE, p.reading(covered = true, now = 5_000))
        assertEquals(-1L, p.dueAt())
        assertTrue(p.pocketed)
    }

    @Test fun `a back already dark when covered stays dark, and uncovering does not light it`() {
        val p = PocketMode(d)
        p.lit(false, 0)
        assertEquals(Act.NONE, p.reading(covered = true, now = 0))
        assertEquals(-1L, p.dueAt())
        assertEquals(Act.NONE, p.due(10_000, lettingGo = false))
        assertEquals(Act.NONE, p.reading(covered = false, now = 20_000))
        assertFalse(p.pocketed)
    }

    @Test fun `gone dark for another reason during the count is not a pocket`() {
        val p = litAndCovered()
        p.lit(false, 200) // the double tap, the timeout, the power button
        assertEquals(Act.NONE, p.due(d, lettingGo = false))
        assertEquals(Act.NONE, p.reading(covered = false, now = 5_000))
        assertFalse(p.pocketed)
    }

    @Test fun `a back already letting go goes off now, and the way out leaves it off`() {
        val p = litAndCovered()
        assertEquals(Act.SWITCH_OFF, p.due(d, lettingGo = true))
        assertFalse(p.pocketed)
        p.lit(false, d + 50)
        assertEquals(Act.NONE, p.reading(covered = false, now = d + 2_000))
    }

    @Test fun `lit by something else while still in the pocket goes off again after the debounce`() {
        val p = litAndCovered()
        p.due(d, lettingGo = false)
        p.lit(false, d + 50)
        p.lit(true, 10_000) // asked for on the front, say: the cover counts from the light
        assertEquals(10_000 + d, p.dueAt())
        assertEquals(Act.NONE, p.due(10_000 + d - 1, lettingGo = false))
        assertEquals(Act.SLEEP, p.due(10_000 + d, lettingGo = false))
        assertTrue(p.pocketed)
        assertEquals(Act.RESTORE, p.reading(covered = false, now = 20_000))
    }

    @Test fun `a switch-off the shell refused is no pocket to come out of`() {
        val p = litAndCovered()
        p.due(d, lettingGo = false)
        p.refused()
        assertFalse(p.pocketed)
        assertEquals(Act.NONE, p.reading(covered = false, now = 1_000))
    }

    @Test fun `out of the pocket the moment it switched off still restores`() {
        val p = litAndCovered()
        assertEquals(Act.SLEEP, p.due(d, lettingGo = false))
        assertEquals(Act.RESTORE, p.reading(covered = false, now = d + 10))
    }

    @Test fun `watched while lit or pocketed, and not otherwise`() {
        val p = PocketMode(d)
        assertFalse(p.watching)
        p.lit(true, 0)
        assertTrue(p.watching)
        p.reading(covered = true, now = 0)
        p.due(d, lettingGo = false)
        p.lit(false, d + 50)
        assertTrue(p.watching)
        p.reading(covered = false, now = 1_000)
        assertFalse(p.watching)
    }

    @Test fun `ended, it forgets the pocket and the cover`() {
        val p = litAndCovered()
        p.due(d, lettingGo = false)
        p.end()
        assertFalse(p.pocketed)
        assertFalse(p.covered)
        assertEquals(Act.NONE, p.reading(covered = false, now = 1_000))
    }
}
