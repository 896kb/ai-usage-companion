package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.RearGeometry.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearGeometryTest {

    // The Pro Max's rear is 976x596 (the release notes); its lens column and corner are not
    // measured — these are stand-ins of the right shape, not the phone's numbers.
    private val proMax = RearGeometry(976, 596, 296, 110, 450)

    @Test fun `the 17 Pro's rear, as measured, is the fallback`() {
        assertEquals(RearGeometry(904, 572, 296, 97, 450), RearGeometry.PRO17)
    }

    @Test fun `on the 17 Pro panel units are its pixels`() {
        val g = RearGeometry.PRO17
        assertEquals(1f, g.fitX, 0f)
        assertEquals(1f, g.fitY, 0f)
        assertEquals(Panel.CUTOUT_RIGHT.toFloat(), g.cutoutUnits, 0f)
        assertEquals(g, g.inPanelUnits())
    }

    @Test fun `on a Pro Max the panel is stretched to the display, per axis`() {
        assertEquals(976f / 904f, proMax.fitX, 1e-6f)
        assertEquals(596f / 572f, proMax.fitY, 1e-6f)
        // The lenses keep their pixels, so in panel units the usable area starts further left.
        assertEquals(296f * 904f / 976f, proMax.cutoutUnits, 1e-3f)
    }

    @Test fun `in panel units, a Pro Max's rear is the panel's size with its lenses and corner scaled`() {
        val u = proMax.inPanelUnits()
        assertEquals(Panel.WIDTH, u.widthPx)
        assertEquals(Panel.HEIGHT, u.heightPx)
        assertEquals(274, u.cutoutPx)
        assertEquals(102, u.cornerPx)
        assertEquals(450, u.dpi)
    }

    @Test fun `the display's own word wins, the dump fills what it does not say`() {
        val display = Reading(width = 976, height = 596, cutout = 296, corner = 110, dpi = null)
        val dump = Reading(width = 976, height = 596, cutout = 290, corner = 100, dpi = 450)
        assertEquals(proMax, RearGeometry.resolve(display, dump))
        assertEquals(RearGeometry(976, 596, 290, 100, 450), RearGeometry.resolve(display.copy(cutout = null, corner = null), dump))
    }

    @Test fun `the dump alone is enough`() {
        assertEquals(RearGeometry.PRO17, RearGeometry.resolve(null, Reading(904, 572, 296, 97, 450)))
        assertEquals(proMax, RearGeometry.resolve(null, Reading(976, 596, 296, 110, 450)))
    }

    @Test fun `nothing said is the 17 Pro`() {
        assertEquals(RearGeometry.PRO17, RearGeometry.resolve(null, null))
        assertEquals(RearGeometry.PRO17, RearGeometry.resolve(Reading(0, 0, null, null, null), Reading(null, 572, 296, 97, 450)))
    }

    @Test fun `a size without the rest takes the 17 Pro's lenses, corner and density`() {
        assertEquals(RearGeometry(976, 596, 296, 97, 450), RearGeometry.resolve(Reading(976, 596, null, null, null), null))
        // Zero is not saying: the panel reads a missing inset the same way.
        assertEquals(RearGeometry(976, 596, 296, 97, 450), RearGeometry.resolve(Reading(976, 596, 0, 0, 0), null))
    }

    @Test fun `a dump of another size does not lend its lenses or corner, only its density`() {
        // Another frame (the device's natural orientation, say): its insets are not the display's.
        val display = Reading(976, 596, null, null, null)
        val dump = Reading(596, 976, 40, 100, 440)
        assertEquals(RearGeometry(976, 596, 296, 97, 440), RearGeometry.resolve(display, dump))
    }

    @Test fun `only a rear one of the sources gave a size is known - the 17 Pro's fallback is asked again`() {
        assertTrue(RearGeometry.known(Reading(976, 596, null, null, null), null))
        assertTrue(RearGeometry.known(null, Reading(904, 572, 296, 97, 450)))
        assertFalse(RearGeometry.known(null, null))
        // A density alone, or half a size, is still the 17 Pro's whole geometry.
        assertFalse(RearGeometry.known(Reading(0, 0, null, null, null), Reading(null, 572, 296, 97, 450)))
        assertFalse(RearGeometry.known(null, Reading(null, null, null, null, 440)))
    }
}
