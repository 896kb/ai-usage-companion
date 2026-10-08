package dev.kb896.aiusage.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the poll service asks Android to bind the notification relay again. Measured on the
 * Xiaomi 17 Pro: the main process restarted and the relay stayed unbound for sixteen hours
 * with its access still granted, so no notification reached the back.
 */
class RelayWatchTest {

    @Test fun `an unbound relay with access is rebound`() {
        assertTrue(RelayWatch.shouldRebind(granted = true, connected = false, sinceLastAskMs = null))
    }

    @Test fun `a connected relay is left alone`() {
        assertFalse(RelayWatch.shouldRebind(granted = true, connected = true, sinceLastAskMs = null))
    }

    @Test fun `no access, no rebind`() {
        assertFalse(RelayWatch.shouldRebind(granted = false, connected = false, sinceLastAskMs = null))
    }

    @Test fun `a bind already asked for is given time before it is asked again`() {
        assertFalse(RelayWatch.shouldRebind(true, false, sinceLastAskMs = RelayWatch.RETRY_MS - 1))
        assertTrue(RelayWatch.shouldRebind(true, false, sinceLastAskMs = RelayWatch.RETRY_MS))
    }
}
