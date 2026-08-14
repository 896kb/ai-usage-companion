package dev.rgarjoaba.claudestatus.data

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
        assertEquals(state, StatePayload.decode(StatePayload.encode(state)))
    }

    @Test fun `preserves a null resetsAt`() {
        val back = StatePayload.decode(StatePayload.encode(state))
        assertNull(back.statuses[0].snapshot!!.windows[2].resetsAt)
    }

    @Test fun `preserves an account with no snapshot`() {
        val back = StatePayload.decode(StatePayload.encode(state))
        assertNull(back.statuses[1].snapshot)
        assertEquals(Health.STALE, back.statuses[1].health)
    }

    @Test fun `decoding garbage yields an empty state rather than throwing`() {
        assertEquals(0, StatePayload.decode("nonsense").statuses.size)
        assertEquals(0, StatePayload.decode("").statuses.size)
    }

    @Test fun `payload contains no token-like material`() {
        val json = StatePayload.encode(state)
        assert(!json.contains("sk-ant")) { "payload leaked a token" }
        assert(!json.contains("Token", ignoreCase = true)) { "payload mentions tokens" }
    }
}
