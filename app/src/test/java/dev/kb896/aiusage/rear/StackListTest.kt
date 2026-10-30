package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StackListTest {

    private val release = "dev.kb896.aiusage"
    private val dev = "dev.kb896.aiusage.dev"

    private val real = """
        Stack id=0 bounds=[0,0][1220,2656]
          taskId=62161: dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity bounds=[0,0][1220,2656] userId=0 visible=true topActivity=ComponentInfo{dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity}
          taskId=101: com.android.settings/.Settings bounds=[0,0][1220,2656] userId=0 visible=false
    """.trimIndent()

    @Test fun `extracts the task id for the matching activity`() {
        assertEquals(62161, StackList.parseTaskId(real, release, "RearActivity"))
    }

    @Test fun `returns minus one when the activity is absent`() {
        assertEquals(-1, StackList.parseTaskId(real, release, "CalibrationActivity"))
    }

    @Test fun `returns minus one for empty output`() {
        assertEquals(-1, StackList.parseTaskId("", release, "RearActivity"))
    }

    @Test fun `ignores a line that names the activity but has no task id`() {
        assertEquals(-1, StackList.parseTaskId("  mFocusedApp=RearActivity", release, "RearActivity"))
    }

    @Test fun `takes the first match when several exist`() {
        val two = "taskId=7: x/.RearActivity\ntaskId=9: x/.RearActivity"
        assertEquals(7, StackList.parseTaskId(two, "x", "RearActivity"))
    }

    // Both halves of the camera opened on the back, as `am stack list` printed them on the
    // 17 Pro (OS4.0.5.0): the rear Camera and its front companion, plus an old one from recents.
    private val camera = """
          taskId=75378: com.android.camera/com.android.camera.Camera bounds=[0,0][904,572] userId=0 visible=true topActivity=ComponentInfo{com.android.camera/com.android.camera.Camera}
          taskId=75379: com.android.camera/com.android.camera.fragment.presentation.MainScreenSelfieActivity bounds=[0,0][1220,2656] userId=0 visible=true topActivity=ComponentInfo{com.android.camera/com.android.camera.fragment.presentation.MainScreenSelfieActivity}
          taskId=62161: dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity bounds=[0,0][904,572] userId=0 visible=false
          taskId=75328: com.android.camera/com.android.camera.Camera bounds=[0,0][1220,2656] userId=0 visible=false topActivity=ComponentInfo{com.android.camera/com.android.camera.Camera}
          taskId=101: com.android.cameraextras/.Main bounds=[0,0][1220,2656] userId=0 visible=false
    """.trimIndent()

    @Test fun `every task of a package, the front half included`() {
        assertEquals(listOf(75378, 75379, 75328), StackList.taskIdsOf(camera, "com.android.camera"))
    }

    @Test fun `none for a package with no task`() {
        assertEquals(emptyList<Int>(), StackList.taskIdsOf(real, "com.android.camera"))
    }

    // -- the release and the dev build, both installed -----------------------------------
    //
    // Same activity class (the namespace), two packages (applicationIdSuffix ".dev"). Matching
    // "RearActivity" alone, each install took the other's panel for its own.

    private val both = """
         configuration={0.9 226mcc5mnc [en_US] winConfig={ mBounds=Rect(0, 0 - 904, 572) } as.4 display=1 extraData = Bundle[{}] screenType=0/o}
          taskId=80011: dev.kb896.aiusage.dev/dev.kb896.aiusage.rear.RearActivity bounds=[0,0][904,572] userId=0 visible=true topActivity=ComponentInfo{dev.kb896.aiusage.dev/dev.kb896.aiusage.rear.RearActivity}
         configuration={0.9 226mcc5mnc [en_US] winConfig={ mBounds=Rect(0, 0 - 904, 572) } as.4 display=1 extraData = Bundle[{}] screenType=0/o}
          taskId=62161: dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity bounds=[0,0][904,572] userId=0 visible=false topActivity=ComponentInfo{dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity}
         configuration={0.9 226mcc5mnc [en_US] winConfig={ mBounds=Rect(0, 0 - 1220, 2656) } as.4 display=0 extraData = Bundle[{}] screenType=0/o}
          taskId=101: com.android.settings/.Settings bounds=[0,0][1220,2656] userId=0 visible=false
    """.trimIndent()

    @Test fun `each build finds its own panel, not the other's above it`() {
        assertEquals(62161, StackList.parseTaskId(both, release, "RearActivity"))
        assertEquals(80011, StackList.parseTaskId(both, dev, "RearActivity"))
    }

    @Test fun `the other build's panel alone is no panel of ours`() {
        val devOnly = "  taskId=80011: dev.kb896.aiusage.dev/dev.kb896.aiusage.rear.RearActivity bounds=[0,0][904,572]"
        assertEquals(-1, StackList.parseTaskId(devOnly, release, "RearActivity"))
        val releaseOnly = "  taskId=62161: dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity bounds=[0,0][904,572]"
        assertEquals(-1, StackList.parseTaskId(releaseOnly, dev, "RearActivity"))
    }

    @Test fun `the other build's panel on top of the rear is the other's back`() {
        assertTrue(StackList.otherBuildOnTop(both, release, 1))
        assertFalse(StackList.otherBuildOnTop(both, dev, 1))
    }

    @Test fun `our own panel on top, or nobody's, is not the other build's back`() {
        assertFalse(StackList.otherBuildOnTop(real, release, 1))
        assertFalse(StackList.otherBuildOnTop("", release, 1))
        // The other build on the FRONT display is nothing to the back.
        val front = both.replace("display=1", "display=0")
        assertFalse(StackList.otherBuildOnTop(front, release, 1))
    }

    @Test fun `an unrelated app on top of the rear is not the other build`() {
        val guest = """
             configuration={x display=1 y}
              taskId=75311: org.mozilla.firefox/org.mozilla.firefox.App bounds=[0,0][904,572] userId=0 visible=true topActivity=ComponentInfo{org.mozilla.firefox/org.mozilla.fenix.HomeActivity}
        """.trimIndent() + "\n" + both
        assertFalse(StackList.otherBuildOnTop(guest, release, 1))
    }
}
