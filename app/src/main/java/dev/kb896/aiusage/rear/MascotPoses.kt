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
    private const val EYE_H = 11f
    private val EYE_X = floatArrayOf(21f, 75f)

    // `const`, not `val`, and that is load-bearing. An object's properties initialise in
    // declaration order, and CLIPS below is built during that pass — a plain `val`
    // declared after it reads as 0 while the frames are being generated. The ghost's eye
    // colour was exactly that: every eye came out fully transparent, a face-less figure
    // that no compiler warning and no crash would have mentioned.
    private const val BODY = MascotRenderer.BODY_GREEN
    private const val EYE = dev.kb896.aiusage.Panel.Palette.BG
    private const val ACCENT = MascotRenderer.ACCENT_ORANGE

    val CLIPS: List<MascotFrames.Clip> = listOf(
        // Slow. A sleeping figure that breathes at the pace the others move at is not
        // asleep, it is panting — this runs one breath per 2.2 seconds.
        MascotFrames.Clip("sleep", SLEEP_W / SLEEP_H, SLEEP_H, 220L, sleepFrames()),
        MascotFrames.Clip("ghost", GHOST_W / GHOST_H, GHOST_H, 125L, ghostFrames()),
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
        val floor = 128f

        fun add(x: Float, y: Float, w: Float, h: Float, c: Int) =
            out.add(MascotFrames.Rect(x / SLEEP_W, y / SLEEP_H, w / SLEEP_W, h / SLEEP_H, c))

        // No legs at all. They went from four stubs to two and were still wrong: a
        // sleeping animal's legs are underneath it, which from the side means not
        // visible. Anything drawn below the body reads as standing on them however
        // short they are, and the body meeting the ground is the pose.
        //
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
    // A rounded body drifting sideways, tapering into a tail that snakes below it.
    // Disconnected rather than dead: DEAD is the mascot on its side with crossed eyes
    // and it means the week is gone, which is a fact about usage. This one means there
    // is nobody home — the account is not answering, and there is no reading at all.

    // Wider than the other poses' 107. The arms swing out with the body, and at full
    // sway they need 21 units of clearance each side where the standing box has 16 —
    // the box has to hold the widest excursion, not the resting pose.
    private const val GHOST_W = 120f
    // Holds the body and the tail below it, with a little slack.
    private const val GHOST_H = 117f
    private const val GHOST_FRAMES = 10

    /** Rest position of the body's top edge. Fixed — the drift is sideways. */
    private const val GHOST_TOP = 12f

    /** Narrower than the standing body: at full width a legless figure is a blob. */
    private const val GHOST_L = 22f
    private const val GHOST_BODY_W = 75f
    /**
     * Head plus torso. The extra eye-height sits below the face, not around it — the
     * body was as short as the tail and the whole figure read as a head on a wisp, with
     * no room between the eyes and the point where it starts dissolving. Two was too
     * much and made a slab; one leaves the tail as the longer half, which is what keeps
     * the eye travelling downwards.
     */
    private const val GHOST_BODY_H = 44f + EYE_H

    /**
     * How far the figure drifts to each side.
     *
     * Sideways, not up and down. Bobbing is what a balloon does; drifting across is what
     * makes it a spirit — nothing is holding it in place.
     */
    private const val GHOST_SWAY = 7f

    /**
     * The tail: how many slices it is cut into, and how much of the width is gone by the
     * bottom.
     *
     * A spirit TAPERS, and that is the whole shape. Two earlier attempts made the bottom
     * a fringe instead — lobes whose heights rose and fell, then teeth that scrolled
     * sideways — and both failed the same way: a row of separate things hanging off a
     * straight edge is never a tail, however it moves. One is a squid and the other a
     * comb. What makes a tail is the width running out as it descends, so the eye
     * follows a single shape down to a wisp.
     */
    // Four, and each exactly as tall as the standing mascot's eye. Nine made a striped
    // funnel: at that thickness the slices stop being blocks and become a gradient,
    // which is the one thing this artwork is not. The rest of the mascot is built from a
    // handful of big rectangles, and pinning the slice height to a feature that already
    // exists on the character is what keeps the tail on the same grid as the face.
    private const val GHOST_TAIL_SLICES = 4
    private const val GHOST_TAIL_TAPER = 0.80f

    /** Declared AFTER the slice count it multiplies: a const cannot read one below it. */
    private const val GHOST_TAIL = GHOST_TAIL_SLICES * EYE_H

    /**
     * How far the tail snakes, and how much each slice lags the one above it.
     *
     * A wave travelling DOWN the tail, not the whole thing leaning. Lower slices swing
     * wider — the amplitude scales with depth — and later, because the lag accumulates.
     * That is what makes the tip trail behind the head rather than the figure tipping
     * like a bell.
     */
    private const val GHOST_TAIL_WAVE = 14f
    private const val GHOST_TAIL_LAG = 0.22f

    /**
     * How far each piece is tucked up behind the one above, to kill the seam between
     * them.
     *
     * Two units of a hundred, under a pixel wherever this is drawn — enough that the
     * rectangles still overlap after rounding, small enough to change no shape. Without
     * it, adjacent rectangles sharing an edge at a fractional coordinate round apart
     * once scaled and show a hairline of background straight through the figure.
     */
    private const val SEAM = 2f

    /** Arm block: where it sits on the body, and how big. */
    private const val GHOST_ARM_W = 14f
    private const val GHOST_ARM_TOP = 12f
    private const val GHOST_ARM_H = 20f

    /**
     * The ghost's grey at depth [t] down the tail, 0 at the body and 1 at the tip.
     *
     * Only the alpha changes; the colour underneath is constant, so the tail reads as
     * the same substance thinning rather than as a gradient painted onto it.
     */
    private fun ghost(t: Float): Int {
        val a = ((MascotRenderer.GHOST_BODY ushr 24) * (1f - GHOST_FADE * t)).toInt()
        return (a shl 24) or (MascotRenderer.GHOST_BODY and 0x00FFFFFF)
    }

    /** How much of the body's opacity the tip has lost. */
    private const val GHOST_FADE = 0.66f

    private fun ghostFrames(): List<List<MascotFrames.Rect>> =
        List(GHOST_FRAMES) { i -> ghostFrame(i.toFloat() / GHOST_FRAMES) }

    private fun ghostFrame(phase: Float): List<MascotFrames.Rect> {
        val out = mutableListOf<MascotFrames.Rect>()
        val sway = GHOST_SWAY * sin(2.0 * PI * phase).toFloat()
        val top = GHOST_TOP

        fun add(x: Float, y: Float, w: Float, h: Float, c: Int) =
            out.add(MascotFrames.Rect(x / GHOST_W, y / GHOST_H, w / GHOST_W, h / GHOST_H, c))

        // Arms, back after a spell without them. They were dropped because on a
        // GREEN body they made the standing mascot in fancy dress — the account's own
        // colour with a wavy bottom. In grey, translucent, over a tail that dissipates,
        // they stop being the animal's limbs and become the thing a spirit reaches with.
        // The colour did the work the silhouette could not.
        add(GHOST_L + sway, top, GHOST_BODY_W, GHOST_BODY_H, ghost(0f))
        add(GHOST_L + sway - GHOST_ARM_W, top + GHOST_ARM_TOP, GHOST_ARM_W, GHOST_ARM_H, ghost(0f))
        add(GHOST_L + sway + GHOST_BODY_W, top + GHOST_ARM_TOP, GHOST_ARM_W, GHOST_ARM_H, ghost(0f))
        // Nothing here overlaps anything else, and that is a reversal worth naming.
        // Every other pose tucks its pieces a hair behind their neighbours, because two
        // opaque rectangles sharing a fractional edge can round apart and show a
        // hairline of background. Translucent pieces cannot do that: an overlap
        // composites twice and the shared band comes out brighter than either side, so
        // the fix for a seam becomes a seam. These share exact edges instead — the same
        // float expression for one's bottom and the next one's top — which tiles without
        // a gap under a non-antialiased fill, and the mascot paint has never been
        // antialiased.
        add(GHOST_L + sway + 12f, top + 16f, EYE_W, EYE_H, MascotRenderer.GHOST_EYE)
        add(GHOST_L + sway + 52f, top + 16f, EYE_W, EYE_H, MascotRenderer.GHOST_EYE)

        // The tail, sliced across and narrowing as it falls away.
        //
        // Each slice is centred on its own point rather than on the body's, and that
        // point swings further and later the lower it sits. The body only sways; the
        // tail is what makes the whole thing read as drifting rather than sliding,
        // because the tip is still arriving where the head has already been.
        val centre = GHOST_L + sway + GHOST_BODY_W / 2f
        val sliceH = GHOST_TAIL / GHOST_TAIL_SLICES
        for (i in 0 until GHOST_TAIL_SLICES) {
            val t = (i + 1f) / GHOST_TAIL_SLICES
            val w = GHOST_BODY_W * (1f - GHOST_TAIL_TAPER * t)
            val swing = GHOST_TAIL_WAVE * t *
                sin(2.0 * PI * (phase - GHOST_TAIL_LAG * t)).toFloat()
            // Fading as it goes. A spirit does not end, it stops being there, and the
            // translucency is what lets that be drawn at all — a solid body can only
            // taper to a point, which is a shape, whereas this runs out.
            add(centre + swing - w / 2f, top + GHOST_BODY_H + i * sliceH,
                w, sliceH, ghost(t))
        }
        return out
    }

}
