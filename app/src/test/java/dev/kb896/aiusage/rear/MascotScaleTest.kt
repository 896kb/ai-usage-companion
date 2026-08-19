package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every mascot must come out the same size as every other one.
 *
 * This is the check that was missing while six clips each carried a hand-set scale. The
 * rule — one source unit is the same size on screen in every clip — was written in a
 * comment, so nothing noticed when the flag drifted 1.6% off it and the two hand-authored
 * poses landed 43% too big and 15% too small. A reader comparing two mascots on the panel
 * could see it; the build could not.
 */
class MascotScaleTest {

    /** Drawn at a common nominal height, as the panel and both previews all do. */
    private val base = 100f

    private fun drawnHeight(clip: MascotFrames.Clip) = base * clip.scale
    private fun drawnWidth(clip: MascotFrames.Clip) = drawnHeight(clip) * clip.aspect

    /** One artwork pixel, on screen, for this clip. */
    private fun unitSize(clip: MascotFrames.Clip) =
        drawnHeight(clip) * MascotFrames.UNIT / clip.boxHeight

    @Test fun `one source unit is the same size in every clip`() {
        val sizes = MascotFrames.CLIPS.associate { it.name to unitSize(it) }
        val first = sizes.values.first()
        for ((name, size) in sizes) {
            assertEquals("$name draws a source unit at a different size: $sizes",
                first, size, 1e-3f)
        }
    }

    /**
     * The same thing measured on the artwork rather than on the declared box — an eye is
     * one pixel, so every eye must draw at one identical width.
     *
     * The flag is excluded, and the exclusion is itself a finding: its eyes are pure
     * black, the same colour as the checkered cloth, so nothing can tell them apart. That
     * also means they are never punched out as holes, which is a real problem in the
     * island but a separate one.
     */
    @Test fun `every eye draws at the same width`() {
        val widths = mutableMapOf<String, Float>()
        for (clip in MascotFrames.CLIPS) {
            val eye = clip.frames.flatten().firstOrNull { MascotFrames.isEye(it.color) }
                ?: continue
            widths[clip.name] = eye.w * drawnWidth(clip)
        }
        assertTrue("no clip exposed an eye to measure", widths.size >= 5)
        val first = widths.values.first()
        for ((name, w) in widths) {
            assertEquals("$name draws its eye at a different width: $widths",
                first, w, 0.05f)
        }
    }

    /**
     * Every rig really is on the 11-unit grid the derivation assumes. If a future clip
     * is drawn at some other pixel size, its box height stops meaning what this file
     * thinks it means, and everything above would keep passing while looking wrong.
     */
    @Test fun `every clip is drawn on the same grid`() {
        for (clip in MascotFrames.CLIPS) {
            val eye = clip.frames.flatten().firstOrNull { MascotFrames.isEye(it.color) }
                ?: continue
            val boxWidth = MascotFrames.UNIT / eye.w
            assertEquals("${clip.name}: eye is not one 11-unit pixel wide",
                boxWidth / clip.aspect, clip.boxHeight, 0.5f)
        }
    }
}
