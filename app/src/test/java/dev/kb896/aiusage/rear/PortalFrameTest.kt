package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
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
}
