package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which account, with which settings, each ring reports on. See RingOwners. */
class RingOwnersTest {

    private val first = RingSpec(RingWindow.SESSION, RingMetric.TIME_TO_RESET)
    private val second = RingSpec(RingWindow.WEEKLY_ALL, RingMetric.TIME_TO_RESET)
    private val paired = RingSpec(RingWindow.SESSION, RingMetric.UTILIZATION)
    private val rings = listOf(first, second)

    @Test fun `each ring belongs to the account in its position`() {
        val owners = RingOwners.assign(listOf(0, 1), { true }, rings, paired)
        assertEquals(listOf(RingOwners.Owner(0, first), RingOwners.Owner(1, second)), owners)
    }

    @Test fun `the order moves the rings with the accounts`() {
        val owners = RingOwners.assign(listOf(1, 0), { true }, rings, paired)
        assertEquals(listOf(RingOwners.Owner(1, first), RingOwners.Owner(0, second)), owners)
    }

    @Test fun `one account alone takes both rings, no switch needed`() {
        val owners = RingOwners.assign(listOf(0, 1), { it == 0 }, rings, paired)
        assertEquals(listOf(RingOwners.Owner(0, first), RingOwners.Owner(0, paired)), owners)
    }

    @Test fun `the lone account keeps its own ring settings on the first ring whatever its place`() {
        // Account 0 second in the order: its own ring is rings[1].
        val owners = RingOwners.assign(listOf(1, 0), { it == 0 }, rings, paired)
        assertEquals(listOf(RingOwners.Owner(0, second), RingOwners.Owner(0, paired)), owners)
    }

    @Test fun `a second account connecting takes the second ring back`() {
        val owners = RingOwners.assign(listOf(0, 1), { true }, rings, paired)
        assertEquals(RingOwners.Owner(1, second), owners[1])
    }

    @Test fun `no account connected, nothing to pair`() {
        val owners = RingOwners.assign(listOf(0, 1), { false }, rings, paired)
        assertEquals(listOf(RingOwners.Owner(0, first), RingOwners.Owner(1, second)), owners)
    }

    @Test fun `a short ring list is filled with defaults`() {
        val owners = RingOwners.assign(listOf(0, 1), { true }, listOf(first), paired)
        assertEquals(RingOwners.Owner(1, RingSpec()), owners[1])
    }

    @Test fun `only the one connected account has both rings`() {
        assertTrue(RingOwners.canPair(0, listOf(0, 1)) { it == 0 })
        assertFalse(RingOwners.canPair(1, listOf(0, 1)) { it == 0 })
        assertFalse(RingOwners.canPair(0, listOf(0, 1)) { true })
    }
}
