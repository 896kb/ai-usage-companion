package dev.kb896.aiusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Locks in the two device profiles' load-bearing numbers, and the rule that an
 * off-device build (the unit-test JVM, where Build.MODEL is null) resolves to the Xiaomi
 * profile — which is exactly why every other geometry test can go on asserting 904x572.
 */
class DeviceProfileTest {

    @Test fun xiaomiRearGeometry() {
        with(XiaomiRearProfile) {
            assertEquals(1, displayId)
            assertEquals(904, width)
            assertEquals(572, height)
            assertEquals(296, cutoutRight)
            assertEquals("332", physicalIdSuffix)
            assertEquals(true, hasRings)
        }
    }

    @Test fun flip6CoverGeometry() {
        with(Flip6CoverProfile) {
            assertEquals(1, displayId)
            assertEquals(748, width)
            assertEquals(720, height)
            // The Flip's cutout is a bottom-right corner, not a left strip — 0 until the
            // layout work models it properly.
            assertEquals(0, cutoutRight)
            assertEquals("900", physicalIdSuffix)
            assertEquals(false, hasRings)
        }
    }

    @Test fun defaultsToXiaomiOffDevice() {
        assertSame(XiaomiRearProfile, DeviceProfiles.current)
        // Panel reads through to the selected profile.
        assertEquals(904, Panel.WIDTH)
        assertEquals(572, Panel.HEIGHT)
        assertEquals("332", Panel.PHYSICAL_ID_SUFFIX)
    }
}
