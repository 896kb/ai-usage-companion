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

    /** The body is the first rectangle each ghost frame emits. */
    private fun body(frame: List<MascotFrames.Rect>) = frame.first()

    /**
     * The tail: everything below the body that is not an eye, top to bottom.
     *
     * Selected by POSITION rather than by colour. It was filtered on the shared body
     * green, and when the ghost turned grey these tests did not fail — they matched
     * nothing and passed on an empty list, which is worse than failing.
     */
    private fun tail(frame: List<MascotFrames.Rect>): List<MascotFrames.Rect> {
        val b = body(frame)
        return frame.filter { !MascotRenderer.isEye(it.color) && it.y >= b.y + b.h - 1e-4f }
            .sortedBy { it.y }
    }

    @Test fun `the ghost has a tail at all`() {
        for ((i, frame) in ghost.frames.withIndex()) {
            assertTrue("frame $i has no tail", tail(frame).size >= 3)
        }
    }

    /**
     * Every slice meets the one above it exactly — no gap, and no overlap.
     *
     * Both halves matter and they pull opposite ways. A gap at a fractional shared edge
     * rounds apart once scaled and shows a hairline of background through the figure.
     * An overlap is what the other poses use to prevent that, but these pieces are
     * TRANSLUCENT: an overlap composites twice and the shared band comes out brighter
     * than either side, so the cure becomes the disease. Exact abutment is the only
     * option left, which makes it worth asserting.
     */
    @Test fun `the ghost's tail is continuous and never doubles up`() {
        for ((i, frame) in ghost.frames.withIndex()) {
            var above = body(frame)
            for (r in tail(frame)) {
                assertEquals("frame $i: slice does not meet the one above",
                    above.y + above.h, r.y, 1e-4f)
                assertTrue("frame $i: slice does not reach the one above horizontally",
                    r.x < above.x + above.w && r.x + r.w > above.x)
                above = r
            }
        }
    }

    /** It narrows the whole way down. A tail that widens again is not a tail. */
    @Test fun `the ghost's tail only ever narrows`() {
        for ((i, frame) in ghost.frames.withIndex()) {
            var previous = body(frame).w
            for (r in tail(frame)) {
                assertTrue("frame $i: ${r.w} is wider than the $previous above it",
                    r.w <= previous + 1e-4f)
                previous = r.w
            }
        }
    }

    /** And it fades as it goes, rather than stopping at a hard edge. */
    @Test fun `the ghost's tail thins out toward the tip`() {
        val first = tail(ghost.frames.first())
        val alpha = first.map { (it.color ushr 24) and 0xFF }
        assertTrue("alphas do not descend: $alpha", alpha.zipWithNext().all { (a, b) -> b < a })
    }

    /**
     * And it actually moves. The taper alone would pass every check above while sitting
     * perfectly still — this is what fails if the wave is ever wired to a constant.
     */
    @Test fun `the ghost's tip travels across the frames`() {
        val tips = ghost.frames.map { tail(it).last().let { r -> r.x + r.w / 2f } }
        assertTrue("the tail tip never moves", tips.max() - tips.min() > 0.05f)
    }

    /**
     * Nothing is invisible.
     *
     * This is here because of a bug that shipped past a clean build, a clean test run
     * and a visual check of the wrong render: the ghost's eye colour was held in a `val`
     * declared BELOW the clip list, and an object initialises its properties in order —
     * so while the frames were being generated it was still 0, and every eye came out
     * fully transparent. A face-less figure, with nothing anywhere to say so.
     */
    @Test fun `no pose contains an invisible rectangle`() {
        for (clip in listOf(ghost, sleep)) {
            for ((i, frame) in clip.frames.withIndex()) {
                for (r in frame) {
                    assertTrue("${clip.name} frame $i has a fully transparent rect",
                        (r.color ushr 24) and 0xFF > 0)
                    assertTrue("${clip.name} frame $i has a zero-area rect",
                        r.w > 0f && r.h > 0f)
                }
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
                    2, frame.count { MascotRenderer.isEye(it.color) })
            }
        }
    }
}
