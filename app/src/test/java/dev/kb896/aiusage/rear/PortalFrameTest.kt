package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The portal frame's outline: the usable display's own edge, in its real pixels. */
class PortalFrameTest {

    // The Pro Max's size; its lenses and corner stand-ins (unmeasured).
    private val proMax = RearGeometry(976, 596, 296, 110, 450)

    @Test fun `on the 17 Pro it is the edge right of the lenses, rounded as the glass`() {
        assertEquals(
            listOf(ElectricBorder.Shape.Rounded(296f, 0f, 904f, 572f, 97f, leftR = 0f)),
            PortalFrame.shapesFor(RearGeometry.PRO17),
        )
    }

    @Test fun `on a Pro Max it runs along that display's own edges and corners`() {
        assertEquals(
            listOf(ElectricBorder.Shape.Rounded(296f, 0f, 976f, 596f, 110f, leftR = 0f)),
            PortalFrame.shapesFor(proMax),
        )
    }

    @Test fun `the front's preview draws it in panel units, the lenses and corner scaled with them`() {
        assertEquals(
            listOf(ElectricBorder.Shape.Rounded(274f, 0f, 904f, 572f, 102f, leftR = 0f)),
            PortalFrame.shapesFor(proMax.inPanelUnits()),
        )
    }

    @Test fun `its CSS pixel is the rear's density over 160, the 17 Pro's as before`() {
        assertEquals(PortalFrame.D, PortalFrame.cssPx(RearGeometry.PRO17), 0f)
        assertEquals(400f / 160f, PortalFrame.cssPx(RearGeometry.PRO17.copy(dpi = 400)), 1e-6f)
    }

    @Test fun `round a letterboxed app the frame is square on both sides, and its outline is all numbers`() {
        val shape = PortalFrame.shapesFor(RearGeometry.PRO17, GuestShape.Bounds(366, 0, 835, 572)).single()
        assertEquals(ElectricBorder.Shape.Rounded(366f, 0f, 835f, 572f, 0f, leftR = 0f), shape)
        val outline = ElectricBorder.outline(shape)
        assertTrue(outline.isNotEmpty() && outline.none { it.isNaN() })
        for (i in outline.indices step 2) {
            assertTrue(outline[i] in 366f..835f)
            assertTrue(outline[i + 1] in 0f..572f)
        }
    }

    @Test fun `round a full-width app it is as it always was`() {
        assertEquals(PortalFrame.shapesFor(RearGeometry.PRO17), PortalFrame.shapesFor(RearGeometry.PRO17, GuestShape.appArea(RearGeometry.PRO17)))
    }
}
