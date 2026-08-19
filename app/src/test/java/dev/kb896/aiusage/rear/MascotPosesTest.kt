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

    /** The body is the first rectangle each ghost frame emits; the tail follows it. */
    private fun tail(frame: List<MascotFrames.Rect>) =
        frame.drop(1).filter { it.color == MascotRenderer.BODY_GREEN }

    /**
     * Every slice overlaps the one above it.
     *
     * Not a stylistic check. Adjacent rectangles that merely share an edge share it at a
     * fractional coordinate, and once scaled to the panel that edge lands mid-pixel: the
     * rasteriser rounds one side up and the other down, and a hairline of background
     * shows straight through the figure. It read as the tail being a separate object
     * floating under the ghost, which is the one thing it must not look like.
     */
    @Test fun `the ghost's tail is continuous`() {
        for ((i, frame) in ghost.frames.withIndex()) {
            var above = frame.first()
            for (r in tail(frame)) {
                assertTrue(
                    "frame $i: slice starts at ${r.y}, the one above ends at ${above.y + above.h}",
                    r.y < above.y + above.h,
                )
                assertTrue("frame $i: slice does not reach the one above horizontally",
                    r.x < above.x + above.w && r.x + r.w > above.x)
                above = r
            }
        }
    }

    /** It narrows the whole way down. A tail that widens again is not a tail. */
    @Test fun `the ghost's tail only ever narrows`() {
        for ((i, frame) in ghost.frames.withIndex()) {
            var previous = frame.first().w
            for (r in tail(frame)) {
                assertTrue("frame $i: ${r.w} is wider than the ${previous} above it",
                    r.w <= previous + 1e-4f)
                previous = r.w
            }
        }
    }

    /**
     * And it actually moves. The taper alone would pass every check above while sitting
     * perfectly still — this is what fails if the wave is ever wired to a constant.
     */
    @Test fun `the ghost's tip travels across the frames`() {
        val tips = ghost.frames.map { tail(it).last().let { r -> r.x + r.w / 2f } }
        assertTrue("the tail tip never moves", tips.max() - tips.min() > 0.05f)
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
