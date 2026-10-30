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

    @Test fun `a task line with no top activity yet is still the task`() {
        val d = "  configuration={x display=0 y}\n  taskId=7: a.b/a.b.Main bounds=[0,0][1,1] userId=0 visible=true"
        val e = GuestTask.byId(d, 7)!!
        assertEquals("a.b", e.pkg)
        assertEquals("", e.top)
    }

    @Test fun `a task line before any configuration line has no display`() {
        val d = "  taskId=7: a.b/a.b.Main bounds=[0,0][1,1] topActivity=ComponentInfo{a.b/a.b.Main}"
        assertEquals(-1, GuestTask.byId(d, 7)!!.display)
    }

    @Test fun `an activity name with a dollar sign is read`() {
        val d = "  taskId=7: a.b/a.b.Main bounds=[0,0][1,1] topActivity=ComponentInfo{a.b/a.b.Main\$Inner}"
        assertEquals("a.b/a.b.Main\$Inner", GuestTask.byId(d, 7)!!.top)
    }

    @Test fun `no top activity is never settled`() {
        val s = GuestTask.Settle()
        assertFalse(s.feed("", 0))
        assertFalse(s.feed("", 5000))
    }
    @Test fun `a task's bounds are read with it`() {
        val d = "  taskId=7: a.b/a.b.Main bounds=[296,0][904,572] userId=0 visible=true topActivity=ComponentInfo{a.b/a.b.Main}"
        assertEquals(GuestShape.Bounds(296, 0, 904, 572), GuestTask.byId(d, 7)!!.bounds)
        assertNull(GuestTask.byId("  taskId=7: a.b/a.b.Main userId=0", 7)!!.bounds)
    }

    @Test fun `a root task's displayId is believed over its configuration's display`() {
        val d = """
            RootTask id=9 bounds=[0,0][608,572] displayId=12 userId=0
             configuration={x as.4 display=0 y}
              taskId=9: a.b/a.b.Main bounds=[0,0][608,572] userId=0 visible=true topActivity=ComponentInfo{a.b/a.b.Main}
            RootTask id=3 bounds=[0,0][904,572] displayId=1 userId=0
             configuration={x as.4 display=1 y}
              taskId=3: c.d/c.d.Main bounds=[0,0][904,572] userId=0 visible=false
        """.trimIndent()
        assertEquals(12, GuestTask.byId(d, 9)!!.display)
        assertEquals(1, GuestTask.byId(d, 3)!!.display)
    }

    @Test fun `whether a task is visible is read with it`() {
        assertEquals(true, GuestTask.byId(dump, 75311)!!.visible)
        assertEquals(false, GuestTask.byId("  taskId=7: a.b/a.b.Main visible=false", 7)!!.visible)
        assertNull(GuestTask.byId("  taskId=7: a.b/a.b.Main userId=0", 7)!!.visible)
    }
}
