package dev.kb896.aiusage.rear

import kotlin.math.PI
import kotlin.math.sin

/**
 * Mascot clips written here rather than traced from the source SVGs.
 *
 * [MascotFrames] is generated and must not be hand-edited; these two poses have no
 * source to generate from, so they live in their own file and are appended to that
 * object's clip list. Keeping them apart is the point — a hand-authored frame sitting in
 * a generated file survives exactly until the next regeneration.
 *
 * Built by function rather than baked as coordinate tables. The generated clips are
 * tables because a tracer produced them; these were designed, and a table of 10 frames
 * times 15 rectangles cannot be adjusted afterwards — moving the eyes down two pixels
 * would mean editing 20 numbers by hand and hoping. Written as geometry, "the ears
 * droop lower" is one constant.
 *
 * The anatomy below is the standing pose's, measured off `stand` in MascotFrames and
 * converted back out of its normalised form: a body block, two side nubs, four legs,
 * two eyes, in a 107x86 box. Both poses here are deformations of that, so the character
 * stays recognisably the same animal.
 *
 * Colour rules are the renderer's, and both are load-bearing:
 *  * body parts take [MascotRenderer.BODY_GREEN] exactly, or the user's chosen mascot
 *    colour will not reach them
 *  * eyes take Palette.BG exactly, which is what marks them as holes to punch rather
 *    than shapes to paint — see MascotRenderer.draw
 */
internal object MascotPoses {

    // ---- the standing anatomy, in its own 107x86 pixel space -------------------
    private const val BODY_L = 11f
    private const val BODY_W = 85f
    private const val LEG_W = 11f
    private val LEG_X = floatArrayOf(11f, 32f, 64f, 85f)
    private const val NUB_W = 22f
    private const val EYE_W = 11f
    private val EYE_X = floatArrayOf(21f, 75f)

    private val BODY = MascotRenderer.BODY_GREEN
    private val EYE = dev.kb896.aiusage.Panel.Palette.BG
    private val ACCENT = MascotRenderer.ACCENT_ORANGE

    val CLIPS: List<MascotFrames.Clip> = listOf(
        // Slow. A sleeping figure that breathes at the pace the others move at is not
        // asleep, it is panting — this runs one breath per 2.2 seconds.
        MascotFrames.Clip("sleep", SLEEP_W / SLEEP_H, sleepScale(), 220L, sleepFrames()),
        MascotFrames.Clip("ghost", GHOST_W / GHOST_H, ghostScale(), 125L, ghostFrames()),
    )

    // ---------------------------------------------------------------- sleep ----
    //
    // Curled on the ground with its eyes shut and Zs drifting off the top. The box is
    // taller than the character because the Zs are part of the clip and need somewhere
    // to go; the panel bottom-aligns a mascot on its bar, so the empty space sits above
    // the head where it belongs.

    private const val SLEEP_W = 107f
    private const val SLEEP_H = 130f
    private const val SLEEP_FRAMES = 10

    /** Where the character alone ends up in the box — see [sleepScale]. */
    private const val SLEEP_CHAR_TOP = 70f

    private fun sleepFrames(): List<List<MascotFrames.Rect>> =
        List(SLEEP_FRAMES) { i -> sleepFrame(i.toFloat() / SLEEP_FRAMES) }

    /** Curled width, narrower than the standing body — see [sleepFrame]. */
    private const val SLEEP_L = 16f
    private const val SLEEP_BODY_W = 75f

    private fun sleepFrame(phase: Float): List<MascotFrames.Rect> {
        val out = mutableListOf<MascotFrames.Rect>()
        // One breath per cycle, and small: two pixels of a 120-pixel box. Any more and
        // the body pumps rather than breathes.
        val breath = 2f * sin(2.0 * PI * phase).toFloat()
        val top = 72f - breath
        val floor = 120f

        fun add(x: Float, y: Float, w: Float, h: Float, c: Int) =
            out.add(MascotFrames.Rect(x / SLEEP_W, y / SLEEP_H, w / SLEEP_W, h / SLEEP_H, c))

        // Two legs, not four, and short. Folded under a sleeping animal is what they
        // should look like; four evenly spaced stubs under a wide body read as teeth.
        // Drawn before the body so it overlaps them rather than sitting on them.
        add(SLEEP_L + 8f, floor, LEG_W, 128f - floor, BODY)
        add(SLEEP_L + 30f, floor, LEG_W, 128f - floor, BODY)
        // Curled: narrower than standing, not merely shorter. Squashing the body at its
        // full standing width produced a slab with eyes — an animal lying down draws its
        // sides IN, and without that the pose read as the standing mascot pushed down
        // the frame rather than as one lying on the ground.
        add(SLEEP_L, top, SLEEP_BODY_W, floor - top, BODY)
        // The nubs droop, and are smaller than they stand: low on the body and barely
        // clearing its sides. That, more than the height, is what reads as slumped.
        add(SLEEP_L - 13f, top + 24f, 13f, 15f, BODY)
        add(SLEEP_L + SLEEP_BODY_W, top + 24f, 13f, 15f, BODY)
        // Eyes shut: the same eyes, five pixels tall instead of thirteen. Not omitted —
        // a face with no eyes at all reads as the back of the animal's head.
        add(SLEEP_L + 12f, top + 17f, EYE_W, 5f, EYE)
        add(SLEEP_L + 52f, top + 17f, EYE_W, 5f, EYE)

        // Two Zs on one path, half a cycle apart, each rising and shrinking as it
        // goes. Staggering rather than emitting one at a time keeps something in the air
        // at every frame, which is what makes a ten-frame loop read as continuous drift.
        //
        // Two and not three. A Z is four cells tall, and three of them spaced along this
        // path sat closer together than they are tall — they overlapped into a single
        // ragged column instead of reading as three separate puffs. The path also starts
        // clear of the head: begun any lower and the first Z is drawn over the body,
        // which looks like a rendering fault rather than like sleep.
        for (k in 0 until 2) {
            val p = (phase + k / 2f) % 1f
            val cell = 4.5f - 2.6f * p
            if (cell < 1.5f) continue
            zed(out, 62f + 26f * p, 50f - 48f * p, cell)
        }
        return out
    }

    /**
     * One Z, four cells wide, in the accent colour.
     *
     * Four and not three. A three-wide Z is a full top row, one centre cell and a full
     * bottom row — which is a capital I, and rendered as one. The letter only becomes a
     * Z when the middle actually travels from one end to the other, and travelling needs
     * two offset cells, which needs a fourth column to travel across.
     *
     * Accent-coloured so they follow the account's kit, like the flag's pole and the
     * dumbbells. They are the one part of this pose that is not the animal.
     */
    private fun zed(out: MutableList<MascotFrames.Rect>, x: Float, y: Float, cell: Float) {
        fun add(cx: Float, cy: Float, cells: Float) = out.add(MascotFrames.Rect(
            (x + cx * cell) / SLEEP_W, (y + cy * cell) / SLEEP_H,
            cells * cell / SLEEP_W, cell / SLEEP_H, ACCENT))
        add(0f, 0f, 4f)
        add(2f, 1f, 1f)
        add(1f, 2f, 1f)
        add(0f, 3f, 4f)
    }

    // ---------------------------------------------------------------- ghost ----
    //
    // No legs, a hem that ripples, and the whole figure bobbing. Disconnected rather
    // than dead: DEAD is the mascot on its side with crossed eyes and it means the
    // week is gone, which is a fact about usage. This one means there is nobody home —
    // the account is not answering, and the panel has no reading to show at all.

    private const val GHOST_W = 107f
    private const val GHOST_H = 100f
    private const val GHOST_FRAMES = 10
    private const val GHOST_LOBES = 6

    /** Rest position of the body's top edge; the bob is measured from here. */
    private const val GHOST_TOP = 8f

    /** Narrower than the standing body, for the same reason the sleeping one is: at full
     *  width a legless figure with a frilly bottom is a blob, not a ghost. */
    private const val GHOST_L = 16f
    private const val GHOST_BODY_W = 75f
    private const val GHOST_BODY_H = 58f
    private const val GHOST_BOB = 3f
    private const val GHOST_HEM = 26f

    private fun ghostFrames(): List<List<MascotFrames.Rect>> =
        List(GHOST_FRAMES) { i -> ghostFrame(i.toFloat() / GHOST_FRAMES) }

    private fun ghostFrame(phase: Float): List<MascotFrames.Rect> {
        val out = mutableListOf<MascotFrames.Rect>()
        val bob = GHOST_BOB * sin(2.0 * PI * phase).toFloat()
        val top = GHOST_TOP + bob

        fun add(x: Float, y: Float, w: Float, h: Float, c: Int) =
            out.add(MascotFrames.Rect(x / GHOST_W, y / GHOST_H, w / GHOST_W, h / GHOST_H, c))

        add(GHOST_L, top, GHOST_BODY_W, GHOST_BODY_H, BODY)
        // Nubs raised above where they stand, which is the whole "boo" of it.
        add(GHOST_L - 16f, top + 8f, 16f, 24f, BODY)
        add(GHOST_L + GHOST_BODY_W, top + 8f, 16f, 24f, BODY)
        add(GHOST_L + 12f, top + 16f, EYE_W, 13f, EYE)
        add(GHOST_L + 52f, top + 16f, EYE_W, 13f, EYE)

        // The hem. Six narrow lobes rather than four wide ones, each a fixed fraction of
        // a turn behind the last, so the wave TRAVELS sideways along the bottom. Move
        // them together and it is only a second bob; make them too wide and the edge
        // steps rather than ripples.
        val lobe = GHOST_BODY_W / GHOST_LOBES
        for (i in 0 until GHOST_LOBES) {
            val wave = sin(2.0 * PI * phase + i * 2.0 * PI / GHOST_LOBES).toFloat()
            add(GHOST_L + i * lobe, top + GHOST_BODY_H, lobe,
                GHOST_HEM * (0.25f + 0.75f * (wave + 1f) / 2f), BODY)
        }
        return out
    }

    // ---------------------------------------------------------------- sizing ---
    //
    // `scale` exists so every clip's CHARACTER comes out the same size whatever its box
    // contains. The generated clips carry values a tool worked out; these two derive
    // theirs from the same relation, which is stand's scale times how much bigger this
    // box is than the character inside it.

    /** stand: a 107x86 box that is character edge to edge, drawn at this fraction. */
    private const val STAND_SCALE = 0.7107f

    private fun sleepScale() = STAND_SCALE * SLEEP_H / (SLEEP_H - SLEEP_CHAR_TOP)

    private fun ghostScale() =
        STAND_SCALE * GHOST_H / (GHOST_BODY_H + GHOST_HEM + GHOST_BOB)
}
