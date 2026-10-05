package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayDumpTest {

    // The shape of `dumpsys display`'s logical displays on Android 16
    // (LogicalDisplay.dumpLocked). Ids invented, Pro Max-shaped.
    private val full = """
        Logical Displays: size=2
          Display 0:
            mDisplayId=0
            mPrimaryDisplayDevice=Built-in Screen(local:4619827260271452928)
            mIsEnabled=true
            mIsInTransition=false
            mLayerStack=0
          Display 1:
            mDisplayId=1
            mPrimaryDisplayDevice=Built-in Screen(local:4630947037102180609)
            mIsEnabled=true
            mIsInTransition=false
            mLayerStack=1
    """.trimIndent()

    // What the service's grep actually returns on the 17 Pro (HyperOS OS3.0.319.0,
    // Android 16), captured off the phone: the logical displays first, then every other
    // section's mDisplayId, some spelled "=: 1" and "= 1".
    private val pro = """
            mDisplayId=0
            mPrimaryDisplayDevice=Built-in Screen(local:4630946949513469331)
            mDisplayId=1
            mPrimaryDisplayDevice=Built-in Screen(local:4630946949513469332)
          mDisplayId=0
          mDisplayId=: 0
          mDisplayId= 0
          mDisplayId=0
          mDisplayId=1
          mDisplayId=: 1
          mDisplayId= 1
          mDisplayId=1
    """.trimIndent()

    @Test fun `reads the 17 Pro's rear display, the one the old constant named`() {
        assertEquals("4630946949513469332", DisplayDump.physicalId(pro, 1))
        assertEquals("4630946949513469331", DisplayDump.physicalId(pro, 0))
    }

    @Test fun `reads the rear display's physical id`() {
        assertEquals("4630947037102180609", DisplayDump.physicalId(full, 1))
    }

    @Test fun `reads the main display's physical id`() {
        assertEquals("4619827260271452928", DisplayDump.physicalId(full, 0))
    }

    @Test fun `reads the grepped form the service asks for`() {
        val grepped = full.lineSequence()
            .filter { it.contains("mDisplayId=") || it.contains("mPrimaryDisplayDevice=") }
            .joinToString("\n")
        assertEquals("4630947037102180609", DisplayDump.physicalId(grepped, 1))
    }

    @Test fun `display 10 is not display 1`() {
        val ten = """
            mDisplayId=10
            mPrimaryDisplayDevice=Overlay(local:111)
            mDisplayId=1
            mPrimaryDisplayDevice=Built-in Screen(local:222)
        """.trimIndent()
        assertEquals("222", DisplayDump.physicalId(ten, 1))
    }

    @Test fun `a stray mDisplayId does not take the next display's device`() {
        val stray = """
            mDisplayId=1
            mDisplayId=0
            mPrimaryDisplayDevice=Built-in Screen(local:111)
            mDisplayId=1
            mPrimaryDisplayDevice=Built-in Screen(local:222)
        """.trimIndent()
        assertEquals("222", DisplayDump.physicalId(stray, 1))
    }

    @Test fun `a device name with brackets of its own`() {
        val named = "mDisplayId=1\nmPrimaryDisplayDevice=Rear (sub) Screen(local:333)"
        assertEquals("333", DisplayDump.physicalId(named, 1))
    }

    @Test fun `no id for a display that is not there, or not a local one`() {
        assertNull(DisplayDump.physicalId(full, 2))
        assertNull(DisplayDump.physicalId("", 1))
        assertNull(DisplayDump.physicalId("mDisplayId=1\nmPrimaryDisplayDevice=null", 1))
        assertNull(DisplayDump.physicalId("mDisplayId=1\nmPrimaryDisplayDevice=Cast(virtual:x)", 1))
    }

    // The two DisplayDeviceInfo lines of the 17 Pro, cut down to what is read.
    private val devices = """
        DisplayDeviceInfo{"Built-in Screen": uniqueId="local:4630946949513469331", 1220 x 2656, modeId 2, density 520, cutout DisplayCutout{insets=Rect(0, 150 - 0, 0) waterfall=Insets{left=0, top=0, right=0, bottom=0}}, touch INTERNAL}
        DisplayDeviceInfo{"Built-in Screen": uniqueId="local:4630946949513469332", 904 x 572, modeId 7, density 450, cutout DisplayCutout{insets=Rect(296, 0 - 0, 0) waterfall=Insets{left=0, top=0, right=0, bottom=0}}, touch INTERNAL}
    """.trimIndent()

    @Test fun `the rear display's left inset, as a fraction of its width`() {
        assertEquals(296f / 904f, DisplayDump.leftInsetFraction(devices, "4630946949513469332")!!, 1e-6f)
    }

    @Test fun `no left inset on the front display`() {
        assertNull(DisplayDump.leftInsetFraction(devices, "4630946949513469331"))
    }

    @Test fun `no line for the display, no inset`() {
        assertNull(DisplayDump.leftInsetFraction(devices, "1"))
    }
}
