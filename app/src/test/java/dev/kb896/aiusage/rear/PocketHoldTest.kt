package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Always-on's pocket mode. The cases: taken out of the pocket in the ten seconds between
 * letting go and HyperOS powering the rear down, it went dark in the hand — the sensor had
 * stopped being watched the moment the hold was let go.
 */
class PocketHoldTest {

    @Test fun `out of the pocket before the display went dark takes the hold back`() {
        val p = PocketHold()
        assertFalse(p.reading(covered = true))
        p.letGo()
        assertTrue(p.released)
        assertTrue(p.reading(covered = false))
        assertFalse(p.released)
    }

    @Test fun `uncovered without having let go is nothing to take back`() {
        val p = PocketHold()
        assertFalse(p.reading(covered = false))
        assertFalse(p.reading(covered = true))
        assertFalse(p.reading(covered = false))
    }

    @Test fun `still covered after letting go keeps letting go`() {
        val p = PocketHold()
        p.reading(covered = true)
        p.letGo()
        assertFalse(p.reading(covered = true))
        assertTrue(p.released)
    }

    @Test fun `taken back once, the next uncovered reading takes nothing`() {
        val p = PocketHold()
        p.letGo()
        assertTrue(p.reading(covered = false))
        assertFalse(p.reading(covered = false))
    }

    @Test fun `something else taking the hold back - a notification - ends the release, and remembers the cover`() {
        val p = PocketHold()
        p.reading(covered = true)
        p.letGo()
        p.heldAgain()
        assertFalse(p.released)
        assertTrue(p.covered)
    }

    @Test fun `stopping the watch forgets both`() {
        val p = PocketHold()
        p.reading(covered = true)
        p.letGo()
        p.reset()
        assertFalse(p.released)
        assertFalse(p.covered)
    }
}
