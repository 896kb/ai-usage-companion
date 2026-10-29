package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandTapTest {

    // The 17 Pro's rear touchscreen: 904x572 pixels at 100 raw units each.
    private fun tap() = BandTap(90399, 57199)

    private fun hex(v: Int) = "%08x".format(v)

    /** One contact's lines, as `getevent /dev/input/event7` prints them. */
    private fun down(x: Int, y: Int, id: Int = 1) = listOf(
        "0003 0039 ${hex(id)}", "0003 0035 ${hex(x)}", "0003 0036 ${hex(y)}", "0001 014a 00000001", "0000 0000 00000000",
    )
    private fun move(x: Int, y: Int) = listOf("0003 0035 ${hex(x)}", "0003 0036 ${hex(y)}", "0000 0000 00000000")
    private val up = listOf("0003 0039 ffffffff", "0001 014a 00000000", "0000 0000 00000000")

    private fun BandTap.run(lines: List<String>, at: Long): Boolean = lines.map { feed(it, at) }.any { it }

    @Test fun `a tap in the grey band counts`() {
        val t = tap()
        assertFalse(t.run(down(18000, 30000), 0))
        assertTrue(t.run(up, 120))
    }

    @Test fun `a tap on the viewfinder does not`() {
        val t = tap()
        t.run(down(60000, 30000), 0)
        assertFalse(t.run(up, 120))
    }

    @Test fun `a long press does not`() {
        val t = tap()
        t.run(down(18000, 30000), 0)
        assertFalse(t.run(up, 900))
    }

    @Test fun `a swipe does not`() {
        val t = tap()
        t.run(down(18000, 30000), 0)
        t.run(move(18000, 45000), 60)
        assertFalse(t.run(up, 120))
    }

    @Test fun `two fingers do not`() {
        val t = tap()
        t.run(down(18000, 30000), 0)
        t.run(listOf("0003 002f 00000001") + down(20000, 10000, id = 2), 20)
        t.run(listOf("0003 0039 ffffffff", "0000 0000 00000000"), 60)
        assertFalse(t.run(listOf("0003 002f 00000000") + up, 100))
    }

    @Test fun `a prefixed line still parses`() {
        val t = tap()
        down(18000, 30000).forEach { t.feed("/dev/input/event7: $it", 0) }
        assertTrue(up.map { t.feed("/dev/input/event7: $it", 100) }.any { it })
    }

    @Test fun `the next tap after a rejected one counts`() {
        val t = tap()
        t.run(down(60000, 30000), 0)
        t.run(up, 100)
        t.run(down(10000, 5000), 1000)
        assertTrue(t.run(up, 1100))
    }

    @Test fun `finds the landscape touchscreen`() {
        val dump = """
            add device 3: /dev/input/event8
              name:     "Xiaomi_Touch_Input_0"
                ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 121999, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 265599, fuzz 0, flat 0, resolution 0
            add device 4: /dev/input/event7
              name:     "Xiaomi_Touch_Input_1"
                ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_X     : value 0, min 0, max 90399, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 57199, fuzz 0, flat 0, resolution 0
            add device 5: /dev/input/event6
              name:     "qcom-hv-haptics"
        """.trimIndent()
        assertEquals(Triple("/dev/input/event7", 90399, 57199), BandTap.rearDevice(dump))
    }

    @Test fun `no landscape touchscreen, no device`() {
        assertNull(BandTap.rearDevice("add device 1: /dev/input/event0\n  name:     \"gpio-keys\"\n"))
    }

    @Test fun `the band from the rear's geometry - the 17 Pro's is the constant, a Pro Max's its own lenses`() {
        assertEquals(BandTap.BAND, BandTap.band(RearGeometry.PRO17), 1e-6f)
        assertEquals(296f / 976f + 48f / 904f, BandTap.band(RearGeometry(976, 596, 296, 110, 450)), 1e-6f)
    }
}
