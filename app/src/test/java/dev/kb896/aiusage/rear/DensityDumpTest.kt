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

class DensityStartTest {
    @Test fun `no marker - the override found is the baseline`() {
        assertEquals(DensityDump.Start(380, false), DensityDump.start(380, null))
        assertEquals(DensityDump.Start(380, false), DensityDump.start(380, "null"))
    }

    @Test fun `no marker and no override - nothing to restore`() {
        assertEquals(DensityDump.Start(null, false), DensityDump.start(null, "null"))
    }

    @Test fun `our override left behind over the display's own 380`() {
        assertEquals(DensityDump.Start(380, true), DensityDump.start(300, "300,380"))
    }

    @Test fun `our override left behind over no override`() {
        assertEquals(DensityDump.Start(null, true), DensityDump.start(300, "300,-"))
    }

    @Test fun `an override changed since the marker is not ours`() {
        assertEquals(DensityDump.Start(380, false), DensityDump.start(380, "300,-"))
    }

    @Test fun `an override reset since the marker leaves nothing to restore`() {
        assertEquals(DensityDump.Start(null, false), DensityDump.start(null, "300,380"))
    }

    @Test fun `a garbled marker is ignored`() {
        for (m in listOf("", "abc", "300", "300,", ",380", "300,abc", "300,380,1")) {
            assertEquals(m, DensityDump.Start(300, false), DensityDump.start(300, m))
        }
    }

    @Test fun `the marker round-trips`() {
        assertEquals(DensityDump.Marker(300, 380), DensityDump.parseMarker(DensityDump.marker(300, 380)))
        assertEquals(DensityDump.Marker(300, null), DensityDump.parseMarker(DensityDump.marker(300, null)))
        assertEquals("300,-", DensityDump.marker(300, null))
    }
}
