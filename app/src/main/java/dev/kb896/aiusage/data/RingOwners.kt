package dev.kb896.aiusage.data

/**
 * Which account each ring reports on, and with which settings.
 *
 * Ring N belongs to the Nth account in display order, with the Nth [RingSpec] (see
 * RingSpec). The one exception is a single connected account: it takes the first ring with
 * its own settings — the spec of its own position, the one its Rings card edits — and the
 * second ring with [PanelConfig.pairedRing]. Its own spec rather than the second account's,
 * so connecting that account later finds its ring as it left it. No switch: a second ring
 * that is not wanted is set to show None.
 *
 * Only while exactly one account is connected: a second one takes the second ring back.
 */
object RingOwners {

    data class Owner(val account: Int, val spec: RingSpec)

    fun assign(
        order: List<Int>,
        connected: (Int) -> Boolean,
        rings: List<RingSpec>,
        paired: RingSpec,
    ): List<Owner> {
        val plain = order.mapIndexed { position, account -> Owner(account, rings.getOrElse(position) { RingSpec() }) }
        if (plain.size < 2) return plain
        val live = order.filter(connected)
        if (live.size != 1) return plain
        val alone = live.single()
        val own = rings.getOrElse(order.indexOf(alone)) { RingSpec() }
        return listOf(Owner(alone, own), Owner(alone, paired)) + plain.drop(2)
    }

    /** Whether [account] has both rings: it is the one connected account. */
    fun canPair(account: Int, order: List<Int>, connected: (Int) -> Boolean): Boolean =
        order.filter(connected) == listOf(account)
}
