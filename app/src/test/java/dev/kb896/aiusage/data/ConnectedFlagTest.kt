package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedFlagTest {
    @Test
    fun `an account with no credential crosses to the panel as not connected, the rest as connected`() {
        val state = DashboardState(
            fetchedAt = 5L,
            statuses = listOf(
                AccountStatus(Account.PERSONAL, Health.AUTH, null, null, MascotMood.GHOST, connected = false),
                AccountStatus(Account.WORK, Health.AUTH, null, null, MascotMood.GHOST),
            ),
        )
        val back = StatePayload.decode(StatePayload.encode(state, PanelConfig(), null)).state
        assertEquals(2, back.statuses.size)
        assertFalse(back.statuses.first { it.account == Account.PERSONAL }.connected)
        assertTrue(back.statuses.first { it.account == Account.WORK }.connected)
    }
}
