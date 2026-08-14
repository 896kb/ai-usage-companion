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
}
