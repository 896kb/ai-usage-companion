package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.RingLayout
import org.junit.Assert.assertEquals
import org.junit.Test

// Plain JVM, no Robolectric. This class used to need it for a real android.os.Bundle,
// because ring geometry arrived as intent extras from adb. It arrives in the payload's
// config now, so there is no Android type left in the path and nothing to emulate.
class RingGeometryTest {

    @Test fun `the stored layout becomes drawable geometry`() {
        val g = PanelGeometry.of(RingLayout(cx = 150, cyFirst = 144, cySecond = 420,
            radius = 128, stroke = 25))
        assertEquals(150f, g.personal.cx, 0.001f)
        assertEquals(144f, g.personal.cy, 0.001f)
        assertEquals(420f, g.work.cy, 0.001f)
        assertEquals(128f, g.personal.radius, 0.001f)
        assertEquals(25f, g.work.stroke, 0.001f)
    }

    /**
     * The lenses sit in one vertical column, so a per-ring X or radius could only ever
     * make the two disagree — and two rings that do not match read as a rendering fault
     * rather than a setting. Only the Y differs.
     */
    @Test fun `both rings share x, radius and stroke`() {
        val g = PanelGeometry.of(RingLayout(cx = 161, radius = 120, stroke = 18))
        assertEquals(g.personal.cx, g.work.cx, 0.001f)
        assertEquals(g.personal.radius, g.work.radius, 0.001f)
        assertEquals(g.personal.stroke, g.work.stroke, 0.001f)
    }

    @Test fun `the default layout matches the compiled-in geometry`() {
        assertEquals(PanelGeometry.DEFAULT, PanelGeometry.of(RingLayout()))
    }

    @Test fun `defaults sit inside the cutout column`() {
        val g = PanelGeometry.DEFAULT
        for (r in listOf(g.personal, g.work)) {
            assert(r.cx - r.radius >= 0f) { "ring escapes the left edge" }
            assert(r.cx + r.radius <= Panel.CUTOUT_RIGHT.toFloat()) {
                "ring escapes the camera column"
            }
        }
    }

    /** A slider cannot produce a value that will not draw. */
    @Test fun `the widest allowed ring still fits the panel`() {
        val widest = RingLayout(
            cx = RingLayout.CX_RANGE.last,
            cySecond = RingLayout.CY_SECOND_RANGE.last,
            radius = RingLayout.RADIUS_RANGE.last,
            stroke = RingLayout.STROKE_RANGE.last,
        )
        val g = PanelGeometry.of(widest)
        assert(g.work.cx + g.work.radius <= Panel.WIDTH.toFloat()) { "ring leaves the panel" }
    }
}
