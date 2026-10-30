package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLockDumpTest {

    private fun dump(vararg locks: String) = (listOf("Wake Locks: size=${locks.size}") + locks + listOf("", "Wake Lock Log:", "  acquire 'WindowManager/displayId:6' earlier")).joinToString("\n")

    private val six = "  SCREEN_BRIGHT_WAKE_LOCK    'WindowManager/displayId:6' ON_AFTER_RELEASE ACQ=-2s409ms (uid=1000 pid=3968 ws=WorkSource{10388 com.brave.browser})"

    @Test fun `the window manager's lock for that display is its app keeping the screen on`() {
        assertTrue(WakeLockDump.keepsScreenOn(dump(six), 6))
    }

    @Test fun `another display's is not, nor one whose id starts the same`() {
        assertFalse(WakeLockDump.keepsScreenOn(dump(six), 1))
        assertFalse(WakeLockDump.keepsScreenOn(dump(six.replace("displayId:6", "displayId:61")), 6))
    }

    @Test fun `only the live list counts, not a log of past locks`() {
        assertFalse(WakeLockDump.keepsScreenOn(dump(), 6))
        assertFalse(WakeLockDump.keepsScreenOn("", 6))
    }

    @Test fun `a disabled lock holds nothing`() {
        assertFalse(WakeLockDump.keepsScreenOn(dump(six.replace("ON_AFTER_RELEASE", "ON_AFTER_RELEASE DISABLED")), 6))
    }
}
