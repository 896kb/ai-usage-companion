package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixtures below are real `dumpsys activity activities` excerpts, captured on a Galaxy Z
 * Flip 6 (Android 16) reproducing the exact failure this parser exists for: Samsung's
 * cover-display home task (SubHomeActivity) in front of a correctly-placed, still-alive
 * RearActivity. See [ActivityDump]'s KDoc.
 */
class ActivityDumpTest {

    private val displaced = """
        Display #1 (activities from top to bottom):
          * Task{c8ddc86 #3 type=home U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=1}
            * Task{c043e61 #4 type=home A=10053:com.android.systemui U=0 rootTaskId=3 visible=true visibleRequested=false mode=fullscreen translucent=false sz=1}
              * Hist  #0: ActivityRecord{17100395 u0 com.android.systemui/.subscreen.SubHomeActivity t4}
          * Task{a367a2d #26304 type=standard A=10617:dev.kb896.aiusage.rear U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
            mLastPausedActivity: ActivityRecord{106637270 u0 dev.kb896.aiusage/.rear.RearActivity t26304}
            * Hist  #0: ActivityRecord{106637270 u0 dev.kb896.aiusage/.rear.RearActivity t26304}
    """.trimIndent()

    private val rear = """
        Display #1 (activities from top to bottom):
          * Task{a367a2d #26304 type=standard A=10617:dev.kb896.aiusage.rear U=0 visible=true visibleRequested=true mode=fullscreen translucent=true sz=1}
            mIsKeepScreenOn=true
            topResumedActivity=ActivityRecord{106637270 u0 dev.kb896.aiusage/.rear.RearActivity t26304}
            * Hist  #0: ActivityRecord{106637270 u0 dev.kb896.aiusage/.rear.RearActivity t26304}
    """.trimIndent()

    private val main = """
        Display #0 (activities from top to bottom):
          * Task{f1a2b3c #99 type=standard A=10617:dev.kb896.aiusage.rear U=0 visible=true visibleRequested=true mode=fullscreen translucent=true sz=1}
            topResumedActivity=ActivityRecord{55 u0 dev.kb896.aiusage/.rear.RearActivity t99}
            * Hist  #0: ActivityRecord{55 u0 dev.kb896.aiusage/.rear.RearActivity t99}
        Display #1 (activities from top to bottom):
          * Task{c8ddc86 #3 type=home U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=1}
            * Hist  #0: ActivityRecord{17100395 u0 com.android.systemui/.subscreen.SubHomeActivity t4}
    """.trimIndent()

    @Test fun `alive but behind the home task reads as displaced`() {
        assertEquals(
            TaskDisplay.DISPLACED,
            ActivityDump.taskDisplay(displaced, "RearActivity", mainDisplayId = 0, rearDisplayId = 1),
        )
    }

    @Test fun `on top of its own display reads as rear`() {
        assertEquals(
            TaskDisplay.REAR,
            ActivityDump.taskDisplay(rear, "RearActivity", mainDisplayId = 0, rearDisplayId = 1),
        )
    }

    @Test fun `stranded on the main display wins even when also listed on the rear one`() {
        assertEquals(
            TaskDisplay.MAIN,
            ActivityDump.taskDisplay(main, "RearActivity", mainDisplayId = 0, rearDisplayId = 1),
        )
    }

    @Test fun `absent entirely reads as nowhere`() {
        assertEquals(
            TaskDisplay.NOWHERE,
            ActivityDump.taskDisplay(rear, "CalibrationActivity", mainDisplayId = 0, rearDisplayId = 1),
        )
    }

    @Test fun `empty dump reads as nowhere`() {
        assertEquals(
            TaskDisplay.NOWHERE,
            ActivityDump.taskDisplay("", "RearActivity", mainDisplayId = 0, rearDisplayId = 1),
        )
    }

    @Test fun `a mention with no preceding task line is ignored, not misread`() {
        val stray = "  mFocusedApp=RearActivity"
        assertEquals(
            TaskDisplay.NOWHERE,
            ActivityDump.taskDisplay(stray, "RearActivity", mainDisplayId = 0, rearDisplayId = 1),
        )
    }
}
