package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacePlaceTest {

    /** `dumpsys SurfaceFlinger`'s composition list, trimmed from the phone (2026-10-28 23:05); bounds are {left,top,bottom,right}. */
    private fun dump(storeBounds: String) = """
        Composition list (top to bottom)
        Current time: 10-28-23:05:54.559
        LayerStack=1
          Layer [30528] VRI-SubScreenGestureBack#30528
            visible reason= buffer=121259811667970 frame=1289
            bounds={842,0,572,904} toDisplayTransform={ tx=842.0000 ty=0.0000 }
          Layer [34001] VRI-dev.kb896.store/dev.kb896.store.MainActivity#34001
            visible reason= buffer=1 frame=12
            bounds=$storeBounds
          Layer [34002] VRI-dev.kb896.store/dev.kb896.store.MainActivity#34002
            visible reason= buffer=1 frame=3
            bounds={400,100,200,500} toDisplayTransform={ tx=400.0000 ty=100.0000 }
          Layer [33983] VRI-dev.kb896.aiusage.dev/dev.kb896.aiusage.rear.RearActivity#33983
            bounds={0,0,572,904}
        LayerStack=0
          Layer [7] VRI-dev.kb896.store/dev.kb896.store.MainActivity#7
            bounds={0,0,2656,1220}

        Input list
        LayerStack=1
          Layer [34001] VRI-dev.kb896.store/dev.kb896.store.MainActivity#34001
            bounds={13,0,572,621}
    """.trimIndent()

    @Test fun `the app's main window's left edge on the rear, from the composition list`() {
        assertEquals(296, SurfacePlace.left(dump("{296,0,572,904} toDisplayTransform={ tx=296.0000 ty=0.0000 }"), 1, "dev.kb896.store"))
    }

    @Test fun `drawn at the left though its task is right of the cameras`() {
        // Seen on the phone: WM had the task at 296..904, its surface at 0..608.
        assertEquals(0, SurfacePlace.left(dump("{0,0,572,608}"), 1, "dev.kb896.store"))
    }

    @Test fun `only that layer stack, only the composition list, and nothing for an app not there`() {
        assertEquals(0, SurfacePlace.left(dump("{0,0,572,608}"), 1, "dev.kb896.store"))
        assertNull(SurfacePlace.left(dump("{296,0,572,904}"), 1, "org.mozilla.firefox"))
        assertNull(SurfacePlace.left(dump("{296,0,572,904}"), 3, "dev.kb896.store"))
        assertNull(SurfacePlace.left("", 1, "dev.kb896.store"))
    }

    @Test fun `misplaced only when read and not at the shape's left`() {
        val confined = GuestShape.of(false)
        assertTrue(SurfacePlace.misplaced(0, confined))
        assertFalse(SurfacePlace.misplaced(296, confined))
        assertFalse(SurfacePlace.misplaced(null, confined))
        assertFalse(SurfacePlace.misplaced(0, GuestShape.of(true)))
    }
}
