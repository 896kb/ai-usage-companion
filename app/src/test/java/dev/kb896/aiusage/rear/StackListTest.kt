package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class StackListTest {

    private val real = """
        Stack id=0 bounds=[0,0][1220,2656]
          taskId=62161: dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity bounds=[0,0][1220,2656] userId=0 visible=true topActivity=ComponentInfo{dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity}
          taskId=101: com.android.settings/.Settings bounds=[0,0][1220,2656] userId=0 visible=false
    """.trimIndent()

    @Test fun `extracts the task id for the matching activity`() {
        assertEquals(62161, StackList.parseTaskId(real, "RearActivity"))
    }

    @Test fun `returns minus one when the activity is absent`() {
        assertEquals(-1, StackList.parseTaskId(real, "CalibrationActivity"))
    }

    @Test fun `returns minus one for empty output`() {
        assertEquals(-1, StackList.parseTaskId("", "RearActivity"))
    }

    @Test fun `ignores a line that names the activity but has no task id`() {
        assertEquals(-1, StackList.parseTaskId("  mFocusedApp=RearActivity", "RearActivity"))
    }

    @Test fun `takes the first match when several exist`() {
        val two = "taskId=7: x/.RearActivity\ntaskId=9: x/.RearActivity"
        assertEquals(7, StackList.parseTaskId(two, "RearActivity"))
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
}
