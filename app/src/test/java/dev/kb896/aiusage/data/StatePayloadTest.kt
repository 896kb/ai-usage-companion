package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatePayloadTest {

    private val state = DashboardState(
        fetchedAt = 1786693843000L,
        statuses = listOf(
            AccountStatus(Account.PERSONAL, Health.OK, UsageSnapshot(
                windows = listOf(
                    UsageWindow(WindowKind.SESSION, "5H", 0.33f, 1786701839932L),
                    UsageWindow(WindowKind.WEEKLY_ALL, "7D", 0.19f, 1787108399932L),
                    UsageWindow(WindowKind.WEEKLY_SCOPED, "FABLE", 0.01f, null),
                ),
                fetchedAt = 1786693843000L)),
            AccountStatus(Account.WORK, Health.STALE, null),
        ),
    )

    @Test fun `round-trips without loss`() {
        assertEquals(state, StatePayload.decode(StatePayload.encode(state)).state)
    }

    @Test fun `omits the work account when showWork is off`() {
        val json = StatePayload.encode(state, PanelConfig.DEFAULT.copy(showWork = false))
        val accounts = StatePayload.decode(json).state.statuses
        assertEquals(1, accounts.size)
        assertEquals(Account.PERSONAL, accounts[0].account)
    }

    @Test fun `keeps the work account when showWork is on`() {
        val json = StatePayload.encode(state, PanelConfig.DEFAULT.copy(showWork = true))
        assertEquals(2, StatePayload.decode(json).state.statuses.size)
    }

    @Test fun `preserves a null resetsAt`() {
        val back = StatePayload.decode(StatePayload.encode(state))
        assertNull(back.state.statuses[0].snapshot!!.windows[2].resetsAt)
    }

    @Test fun `preserves an account with no snapshot`() {
        val back = StatePayload.decode(StatePayload.encode(state))
        assertNull(back.state.statuses[1].snapshot)
        assertEquals(Health.STALE, back.state.statuses[1].health)
    }

    @Test fun `decoding garbage yields an empty state rather than throwing`() {
        assertEquals(0, StatePayload.decode("nonsense").state.statuses.size)
        assertEquals(0, StatePayload.decode("").state.statuses.size)
    }

    @Test fun `carries the config alongside the state`() {
        val config = PanelConfig.DEFAULT.copy(
            title = "PANEL",
            showClock = true,
            ringLayout = RingLayout(cx = 161, cyFirst = 150, cySecond = 430, radius = 120, stroke = 20),
        )
        val back = StatePayload.decode(StatePayload.encode(state, config))
        assertEquals(config, back.config)
        assertEquals(state, back.state)
    }

    /** A payload written before configs existed must still decode, with defaults. */
    @Test fun `a payload with no config yields defaults`() {
        assertEquals(PanelConfig.DEFAULT, StatePayload.decode(StatePayload.encode(state)).config)
    }

    @Test fun `garbage yields an empty state AND a default config`() {
        val back = StatePayload.decode("nonsense")
        assertEquals(0, back.state.statuses.size)
        assertEquals(PanelConfig.DEFAULT, back.config)
    }

    @Test fun `payload contains no token-like material`() {
        val json = StatePayload.encode(state)
        assert(!json.contains("sk-ant")) { "payload leaked a token" }
        assert(!json.contains("Token", ignoreCase = true)) { "payload mentions tokens" }
    }
}
