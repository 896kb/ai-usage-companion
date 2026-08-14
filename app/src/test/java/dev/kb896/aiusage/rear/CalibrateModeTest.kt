package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrateModeTest {

    @Test fun `empty payload is not calibrate mode`() {
        assertFalse(CalibrateMode.isCalibrate(""))
    }

    @Test fun `a bare non-JSON status string is not calibrate mode`() {
        // Exactly the payload Task 4's own verification step broadcasts — a
        // geometry-only nudge or a plain status string must never be misread as
        // calibrate mode.
        assertFalse(CalibrateMode.isCalibrate("hello-from-adb"))
    }

    @Test fun `the calibrate mode payload is recognised`() {
        assertTrue(CalibrateMode.isCalibrate("""{"mode":"calibrate"}"""))
    }
}
