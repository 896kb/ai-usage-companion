package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun `a release is newer only when its numbers are, and a dev build counts by its base`() {
        assertTrue(UpdateCheck.newer("v0.7.4", "0.7.3"))
        assertFalse(UpdateCheck.newer("v0.7.3", "0.7.3"))
        assertFalse(UpdateCheck.newer("v0.7.4", "0.7.4-dev"))
        assertTrue(UpdateCheck.newer("v0.7.5", "0.7.4-dev"))
        assertTrue(UpdateCheck.newer("v1.0.0", "0.9.12"))
        assertFalse(UpdateCheck.newer("v0.7", "0.7.1"))
    }

    @Test
    fun `release notes lose their markdown`() {
        val p = UpdateCheck.plain("## What\n\n- The **panel** dims, see `pm` and [the site](https://rear.896.ro).\n* Two")
        assertEquals("What\n\n• The panel dims, see pm and the site.\n• Two", p)
    }
}
