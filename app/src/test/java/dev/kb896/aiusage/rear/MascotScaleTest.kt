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

    /** An eye is one pixel. Null when a clip has none this can pick out. */
    private fun eyePixel(clip: MascotFrames.Clip) =
        clip.frames.flatten().firstOrNull { MascotFrames.isEye(it.color) }?.w

    /**
     * The commonest body rectangle is one pixel — a second ruler, for clips whose eyes
     * cannot be identified.
     *
     * Commonest among BODY-COLOURED rects specifically. Over all colours it finds the
     * confetti's 8-unit scraps and the flag's 5-unit checkers instead, which is how this
     * looked like a dead end at first.
     */
    private fun bodyPixel(clip: MascotFrames.Clip) = clip.frames.flatten()
        .filter { it.color == MascotRenderer.BODY_GREEN }
        .groupingBy { it.w }.eachCount()
        .maxByOrNull { it.value }?.key

    /**
     * Every clip really is on the 11-unit grid the derivation assumes, measured against
     * something in its own artwork rather than against the number it declares.
     *
     * Two rulers, because neither covers everything and between them they cover all of
     * it. The flag's eyes are pure black — the same colour as its checkered cloth — so
     * nothing can pick them out, and it is verified by its body pixel. The sleeping and
     * ghost poses were written by hand and their bodies are not on the pixel grid at all
     * (the ghost has no body-green rect whatsoever), so they are verified by their eyes.
     * The other three satisfy both.
     *
     * If a future clip is drawn at some other pixel size, its declared box height stops
     * meaning what the derivation thinks it means — and every other test here would keep
     * passing while the mascot rendered at the wrong size.
     */
    @Test fun `every clip is drawn on the same grid`() {
        for (clip in MascotFrames.CLIPS) {
            val boxWidth = clip.boxHeight * clip.aspect
            val rulers = listOfNotNull(
                eyePixel(clip)?.let { "eye" to it * boxWidth },
                bodyPixel(clip)?.let { "body" to it * boxWidth },
            )
            val agreeing = rulers.filter { kotlin.math.abs(it.second - MascotFrames.UNIT) < 0.3f }
            assertTrue(
                "${clip.name}: nothing in its artwork measures one ${MascotFrames.UNIT}-unit " +
                    "pixel against a box of ${clip.boxHeight} — rulers read $rulers",
                agreeing.isNotEmpty(),
            )
        }
    }

    /**
     * The walk borrows the standing clip's scale, so it has to be the standing box.
     *
     * WalkRig is procedural and carries no Clip of its own — it is the same rig, drawn
     * from a timeline instead of from frames. Sharing the box is what makes borrowing
     * the scale correct rather than a coincidence that happens to look right.
     */
    @Test fun `the walk rig shares the standing clip's box`() {
        val stand = MascotFrames.CLIPS.first { it.name == "stand" }
        assertEquals("the walk is not drawn in the standing clip's box",
            stand.aspect, WalkRig.ASPECT, 1e-3f)
    }
}
