package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestTaskTest {

    // Trimmed from the phone, 2026-10-28.
    private val dump = """
         configuration={0.9 226mcc5mnc [en_US] winConfig={ mBounds=Rect(0, 0 - 904, 572) } as.4 display=1 extraData = Bundle[{}] screenType=0/o}
          taskId=75311: org.mozilla.firefox/org.mozilla.firefox.App bounds=[0,0][904,572] userId=0 visible=true topActivity=ComponentInfo{org.mozilla.firefox/org.mozilla.fenix.HomeActivity}
         configuration={0.9 226mcc5mnc [en_US] winConfig={ mBounds=Rect(0, 0 - 1220, 2656) } as.4 display=0 extraData = Bundle[{}] screenType=0/o}
          taskId=75369: com.shazam.android/com.shazam.android.activities.SplashActivity bounds=[0,0][1220,2656] userId=0 visible=true topActivity=ComponentInfo{com.shazam.android/com.shazam.android.activities.ConfigurationActivity}
    """.trimIndent()

    @Test fun `reads each task with its package, display and top activity`() {
        val ff = GuestTask.taskOf(dump, "org.mozilla.firefox")!!
        assertEquals(75311, ff.task)
        assertEquals(1, ff.display)
        assertEquals("org.mozilla.firefox/org.mozilla.fenix.HomeActivity", ff.top)
        assertEquals(0, GuestTask.taskOf(dump, "com.shazam.android")!!.display)
    }

    @Test fun `a task by id, and none for a gone one`() {
        assertEquals("com.shazam.android", GuestTask.byId(dump, 75369)?.pkg)
        assertNull(GuestTask.byId(dump, 1))
    }

    @Test fun `settled once the top activity has stayed the same for a second`() {
        val s = GuestTask.Settle()
        assertFalse(s.feed("a/.Splash", 0))
        assertFalse(s.feed("a/.Config", 300))
        assertFalse(s.feed("a/.Main", 600))
        assertFalse(s.feed("a/.Main", 1500))
        assertTrue(s.feed("a/.Main", 1600))
    }

    @Test fun `the launcher, the shade and ourselves are not sent to the back`() {
        assertFalse(GuestTask.sendable("com.miui.home", "dev.kb896.aiusage"))
        assertFalse(GuestTask.sendable("com.android.systemui", "dev.kb896.aiusage"))
        assertFalse(GuestTask.sendable("dev.kb896.aiusage", "dev.kb896.aiusage"))
        assertTrue(GuestTask.sendable("org.mozilla.firefox", "dev.kb896.aiusage"))
    }
}
