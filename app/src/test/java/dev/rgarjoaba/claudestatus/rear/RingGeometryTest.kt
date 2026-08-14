package dev.rgarjoaba.claudestatus.rear

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A real android.os.Bundle is needed here (put/get/containsKey must actually store and
// retrieve values) — the plain JVM mockable-android.jar throws "Method ... not mocked"
// for it, the same trap org.json falls into (see UsageParser.kt). Robolectric is scoped
// to this one test class rather than adopted project-wide. sdk=34 pins it to the app's
// minSdk: Robolectric 4.13's newest supported SDK (34) is older than this project's
// targetSdk (36), which it would otherwise refuse to configure for.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingGeometryTest {

    @Test fun `null extras yields the fallback unchanged`() {
        val base = PanelGeometry.DEFAULT
        assertEquals(base, PanelGeometry.fromExtras(null, base))
    }

    @Test fun `each field can be overridden independently`() {
        val base = PanelGeometry.DEFAULT
        val b = Bundle().apply { putInt("cx1", 150); putInt("r2", 99) }
        val g = PanelGeometry.fromExtras(b, base)
        assertEquals(150f, g.personal.cx, 0.001f)
        assertEquals(base.personal.cy, g.personal.cy, 0.001f)   // untouched
        assertEquals(99f, g.work.radius, 0.001f)
        assertEquals(base.work.cx, g.work.cx, 0.001f)           // untouched
    }

    @Test fun `stroke applies to both rings`() {
        val g = PanelGeometry.fromExtras(Bundle().apply { putInt("stroke", 21) }, PanelGeometry.DEFAULT)
        assertEquals(21f, g.personal.stroke, 0.001f)
        assertEquals(21f, g.work.stroke, 0.001f)
    }

    @Test fun `defaults sit inside the cutout column`() {
        val g = PanelGeometry.DEFAULT
        for (r in listOf(g.personal, g.work)) {
            assert(r.cx - r.radius >= 0f) { "ring escapes the left edge" }
            assert(r.cx + r.radius <= dev.rgarjoaba.claudestatus.Panel.CUTOUT_RIGHT.toFloat()) {
                "ring escapes the camera column"
            }
        }
    }
}
