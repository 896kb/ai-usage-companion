package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants for the hand-authored poses.
 *
 * Artwork is judged by eye, but two things about these are arithmetic, and both are
 * mistakes that were actually made: a hem that swings out past the body, and a hem that
 * fails to reach it. Neither is obvious in a still — the first only happens at the
 * extremes of the swing, and the second is a hairline.
 */
class MascotPosesTest {

    private val ghost = MascotPoses.CLIPS.first { it.name == "ghost" }
    private val sleep = MascotPoses.CLIPS.first { it.name == "sleep" }

    /** The body is the first rectangle each ghost frame emits; the hem follows it. */
    private fun body(frame: List<MascotFrames.Rect>) = frame.first()

    @Test fun `the hem never swings outside the body`() {
        for ((i, frame) in ghost.frames.withIndex()) {
            val b = body(frame)
            for (r in frame.drop(1)) {
                if (r.color != MascotRenderer.BODY_GREEN) continue
                assertTrue("frame $i: hem left ${r.x} outside body ${b.x}", r.x >= b.x - 1e-4f)
                assertTrue(
                    "frame $i: hem right ${r.x + r.w} outside body ${b.x + b.w}",
                    r.x + r.w <= b.x + b.w + 1e-4f,
                )
            }
        }
    }

    /**
     * And it must overlap, not merely touch. Sharing an edge at a fractional coordinate
     * puts the seam mid-pixel once scaled, and the rasteriser rounds the two sides apart
     * — a hairline of background that reads as the hem floating free of the ghost.
     */
    @Test fun `the hem overlaps the body rather than abutting it`() {
        for ((i, frame) in ghost.frames.withIndex()) {
            val b = body(frame)
            for (r in frame.drop(1)) {
                if (r.color != MascotRenderer.BODY_GREEN) continue
                assertTrue("frame $i: hem starts at ${r.y}, body ends at ${b.y + b.h}",
                    r.y < b.y + b.h)
            }
        }
    }

    /** Nothing may leave the clip's own box, or it is cropped on the panel. */
    @Test fun `every pose stays inside its box`() {
        for (clip in listOf(ghost, sleep)) {
            for ((i, frame) in clip.frames.withIndex()) {
                for (r in frame) {
                    assertTrue("${clip.name} frame $i: x ${r.x}", r.x >= -1e-4f)
                    assertTrue("${clip.name} frame $i: y ${r.y}", r.y >= -1e-4f)
                    assertTrue("${clip.name} frame $i: right ${r.x + r.w}", r.x + r.w <= 1f + 1e-4f)
                    assertTrue("${clip.name} frame $i: bottom ${r.y + r.h}", r.y + r.h <= 1f + 1e-4f)
                }
            }
        }
    }

    /**
     * Both eyes, every frame. The renderer treats a background-coloured rectangle as a
     * hole to punch, so losing one is not a missing detail — it is a face with one eye.
     */
    @Test fun `both poses keep two eyes throughout`() {
        for (clip in listOf(ghost, sleep)) {
            for ((i, frame) in clip.frames.withIndex()) {
                assertEquals("${clip.name} frame $i",
                    2, frame.count { it.color == dev.kb896.aiusage.Panel.Palette.BG })
            }
        }
    }
}
