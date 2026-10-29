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

    @Test fun `the real list has blank lines inside each layer's entry, and goes on past them`() {
        // As the phone prints it (23:17): a geometry block, then a blank line, inside every
        // entry — the guest is the fourth layer, after three blank lines.
        val real = """
            Composition list (top to bottom)
            Current time: 10-28-23:17:38.969
            LayerStack=1
              Layer [34833] [BBQ] portal-frame#34832#34833
                visible reason= buffer=30039001268278 frame=43 alpha=0.5 contentDirty
                bounds={0,0,572,904}
                  isSecure=false privateLayer=0

                  geomBufferSize=[0 0 904 572]
              Layer [34835] [BBQ] portal-strip#34834#34835
                bounds={0,0,572,296}

                  geomBufferSize=[0 0 296 572]
              Layer [30529] VRI-SubScreenGestureHome#30529
                bounds={0,479,572,904} toDisplayTransform={ tx=0.0000 ty=479.0000 }

                  geomBufferSize=[0 0 904 93]
              Layer [34830] VRI-com.google.android.googlequicksearchbox/com.google.android.googlequicksearchbox.SearchActivity#34830
                bounds={0,0,572,608}

            LayerStack=0
              Layer [7] VRI-com.google.android.googlequicksearchbox/x.Main#7
                bounds={300,0,2656,1220}

            Input list
            LayerStack=1
              Layer [34830] VRI-com.google.android.googlequicksearchbox/com.google.android.googlequicksearchbox.SearchActivity#34830
                bounds={296,0,572,904}
        """.trimIndent()
        assertEquals(0, SurfacePlace.left(real, 1, "com.google.android.googlequicksearchbox"))
    }

    @Test fun `misplaced only when a confined guest is read left of its shape`() {
        val confined = GuestShape.of(false, RearGeometry.PRO17)
        // The failure seen on the phone: its task right of the cameras, its surface at x 0.
        assertTrue(SurfacePlace.misplaced(0, confined))
        assertTrue(SurfacePlace.misplaced(295, confined))
        assertFalse(SurfacePlace.misplaced(296, confined))
        assertFalse(SurfacePlace.misplaced(null, confined))
    }

    @Test fun `a guest that does not fill its shape is not misplaced`() {
        // Letterboxed (portrait only, size compat after a DPI change): drawn inside its shape,
        // right of where it starts. Nudging it never changes that.
        assertFalse(SurfacePlace.misplaced(400, GuestShape.of(false, RearGeometry.PRO17)))
        assertFalse(SurfacePlace.misplaced(291, GuestShape.of(true, RearGeometry.PRO17)))
        // A fullscreen guest has nowhere left of its shape to be.
        assertFalse(SurfacePlace.misplaced(0, GuestShape.of(true, RearGeometry.PRO17)))
    }

    @Test fun `put right a few times, then left alone until the key changes`() {
        val g = SurfacePlace.GiveUp<String>(tries = 3)
        assertTrue(g.attempt("a"))
        assertTrue(g.attempt("a"))
        assertTrue(g.attempt("a"))
        // Three goes and still not right: no more.
        assertFalse(g.attempt("a"))
        assertTrue(g.givenUp("a"))
        assertFalse(g.attempt("a"))
        // Another task, shape or density: counted afresh.
        assertTrue(g.attempt("b"))
        assertFalse(g.givenUp("b"))
    }

    @Test fun `once right, its count starts again`() {
        val g = SurfacePlace.GiveUp<String>(tries = 2)
        g.attempt("a"); g.attempt("a")
        g.placed("a")
        assertTrue(g.attempt("a"))
        assertTrue(g.attempt("a"))
        assertFalse(g.attempt("a"))
        // Being found right later counts too: it may be tried again when it next goes wrong.
        g.placed("a")
        assertFalse(g.givenUp("a"))
        // Another key's right says nothing of this one.
        g.attempt("a"); g.attempt("a"); g.placed("b")
        assertTrue(g.givenUp("a"))
    }

    @Test fun `giving up is said once per key`() {
        val g = SurfacePlace.GiveUp<String>(tries = 1)
        g.attempt("a")
        assertTrue(g.announce("a"))
        assertFalse(g.announce("a"))
        g.attempt("b")
        assertFalse("not given up yet", g.announce("c"))
        assertTrue(g.announce("b"))
    }
}
