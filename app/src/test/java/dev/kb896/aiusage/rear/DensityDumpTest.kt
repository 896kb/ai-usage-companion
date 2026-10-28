package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DensityDumpTest {
    @Test fun `the display's own density alone`() {
        val d = DensityDump.parse("Physical density: 450")
        assertEquals(450, d.physical)
        assertNull(d.override)
    }

    @Test fun `with an override set`() {
        val d = DensityDump.parse("Physical density: 450\nOverride density: 380")
        assertEquals(450, d.physical)
        assertEquals(380, d.override)
    }

    @Test fun `trailing whitespace and CRLF are tolerated`() {
        val d = DensityDump.parse("Physical density: 450\r\nOverride density: 380\r\n")
        assertEquals(450, d.physical)
        assertEquals(380, d.override)
    }

    @Test fun `nothing readable is nothing known`() {
        for (out in listOf("", "Error: invalid display", "java.lang.SecurityException: nope")) {
            val d = DensityDump.parse(out)
            assertNull(d.physical)
            assertNull(d.override)
        }
    }
}
