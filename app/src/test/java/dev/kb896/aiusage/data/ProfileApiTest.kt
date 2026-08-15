package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileApiTest {

    /** The exact shape observed live on 2026-08-15. */
    @Test fun `shortens the live rate_limit_tier`() {
        val body = """{"organization":{"rate_limit_tier":"default_claude_max_5x"}}"""
        assertEquals("MAX 5X", ProfileApi.parseTier(body))
    }

    /**
     * The point of the generic transformation: an unrecognised future tier must still
     * render as something, not vanish. A lookup table would return null here.
     */
    @Test fun `an unknown tier still yields a readable label`() {
        val body = """{"organization":{"rate_limit_tier":"default_claude_wombat_9x"}}"""
        assertEquals("WOMBAT 9X", ProfileApi.parseTier(body))
    }

    @Test fun `missing organization yields null rather than throwing`() {
        assertNull(ProfileApi.parseTier("""{"account":{"full_name":"x"}}"""))
    }

    @Test fun `blank tier yields null rather than an empty tag`() {
        assertNull(ProfileApi.parseTier("""{"organization":{"rate_limit_tier":"  "}}"""))
    }

    @Test fun `non-JSON yields null rather than throwing`() {
        assertNull(ProfileApi.parseTier("<html>503</html>"))
        assertNull(ProfileApi.parseTier(""))
    }
}
