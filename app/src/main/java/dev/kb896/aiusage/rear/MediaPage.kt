package dev.kb896.aiusage.rear

/**
 * Where everything sits on the media page — the second page, reached by swiping up.
 *
 * A page of its own rather than an expansion of the row, because the two are different
 * things: the row is a strip at the bottom of a readout, and this is the panel being
 * about the music instead. What travels between them is the record, which is why its
 * position here is expressed as a destination for the same disc rather than as a new one.
 *
 * Under either cover style. It was the record's page at first, on the reasoning that a
 * square cover gives nothing to carry across the transition — which was wrong: the row's
 * thumbnail is an object too, it simply travels and resizes rather than spinning.
 *
 * Pure, like MediaLayout, and for the same reason: this is arithmetic on a 904x572 panel
 * that nobody is looking at directly, where a number being wrong shows up as something
 * slightly off rather than as a crash.
 */
object MediaPage {

    /**
     * The record, once it has arrived here.
     *
     * Smaller and higher than on the readout page, where it is panel-height and mostly
     * off the bottom. Here the whole disc is on screen with the controls under it, which
     * is the shape of every record player ever made and the reason the disc had to shrink
     * to get here.
     *
     * Its size is DERIVED rather than fixed, from the space between whatever is above it
     * and the stack of type and buttons below — see [disc]. At rest that works out to the
     * numbers this used to hardcode.
     */
    const val DISC_MARGIN = 50f

    /** The top of the type stack: title, artist, transport, bar. Fixed. */
    const val STACK_TOP = 360f

    /** Between the bottom of the record and the top of that stack. */
    const val DISC_GAP = 18f

    /** Small enough to be a token, big enough to still read as a record. */
    const val DISC_MIN_RADIUS = 34f

    /**
     * The record's centre and radius, given whatever is occupying the top of the panel.
     *
     * [topLimit] is where this page may start drawing — normally 0, and the height of a
     * notification strip while one is up.
     *
     * THE RECORD SHRINKS; the controls do not move. A notification used to push this
     * whole page down, which is right on the readout page — everything there is a list
     * and a list can ride down — and wrong here, because the bottom of this page is the
     * transport buttons and pushing them down puts them off the panel. You cannot press
     * what has left the screen, so the one flexible thing on the page gives up the room
     * instead.
     */
    fun disc(topLimit: Float): Pair<Float, Float> {
        val top = topLimit + DISC_MARGIN
        val bottom = STACK_TOP - DISC_GAP
        val radius = ((bottom - top) / 2f).coerceAtLeast(DISC_MIN_RADIUS)
        return (top + radius) to radius
    }

    /** Where the record sits with nothing above it. */
    val DISC_CY: Float get() = disc(0f).first
    val DISC_RADIUS: Float get() = disc(0f).second

    /** Type and controls, stacked under the disc. Baselines and centre lines. */
    const val TITLE_BASELINE = 386f
    const val ARTIST_BASELINE = 414f
    const val TITLE_SIZE = 26f
    const val ARTIST_SIZE = 20f

    /** The transport row, centred under the type. Bigger than the readout row's: this
     *  page is the one you came to on purpose, and there is room. */
    const val TRANSPORT_CY = 468f
    const val TRANSPORT_H = 46f
    const val TRANSPORT_GAP = 58f

    /** The progress bar and its two clocks, along the bottom. */
    const val BAR_CY = 534f
    const val BAR_H = 5f
    const val CLOCK_SIZE = 17f

    /** Between a clock and the end of the bar it labels. */
    const val CLOCK_GAP = 12f

    /**
     * The two drag controls, and why they sit where they do.
     *
     * The volume used to have the whole area above the transport row, dragged sideways,
     * and the playback position had nothing. Both are now the other way round, which is
     * the arrangement that matches what the hand expects:
     *
     *   VOLUME  — a narrow strip down the RIGHT EDGE, dragged vertically. That is where
     *             the volume keys are on the front of the phone and which way they run.
     *   SCRUB   — the wide area above the buttons, dragged horizontally. A timeline is a
     *             horizontal thing everywhere else, and it is the gesture that wants room.
     *
     * They cannot be confused: they occupy different regions AND different axes, so
     * neither a stray sideways wobble in the strip nor a vertical one in the open area
     * can start the wrong control.
     */
    const val VOLUME_AREA_GAP = 18f

    /** The bottom of the open drag area: clear of the transport buttons. */
    fun dragAreaBottom(): Float = TRANSPORT_CY - TRANSPORT_H / 2f - VOLUME_AREA_GAP

    /** Kept under its old name for the tests and callers that already say it. */
    fun volumeAreaBottom(): Float = dragAreaBottom()

    /** The volume strip's width, as a fraction of the panel's. */
    const val VOLUME_STRIP = 1f / 6f

    /** Where that strip begins. */
    fun volumeStripLeft(panelWidth: Float): Float = panelWidth * (1f - VOLUME_STRIP)

    /**
     * How much of the range a full sweep of the strip's height covers.
     *
     * RELATIVE, not absolute, and this was tried both ways round. An absolute strip has
     * ends — silence and maximum are places rather than distances — which sounds better
     * than it feels: it makes every touch a commitment to a level, on a surface nobody is
     * looking at, and it moves those ends whenever anything else on the page does. The
     * gesture wanted is "louder" and "quieter" from wherever the volume already is, which
     * is relative, and a stray tap then does nothing at all.
     */
    const val VOLUME_TRAVEL = 1f

    /**
     * How far a drag has moved the volume, from where the finger went down.
     *
     * Up is louder, which is the one thing about a vertical volume control that is not a
     * choice — and it is the same direction the arc now fills. See [VOLUME_ARC_START].
     */
    fun volumeTravel(y: Float, downY: Float, panelHeight: Float): Float =
        if (panelHeight <= 0f) 0f else (downY - y) / panelHeight * VOLUME_TRAVEL

    /** The scrub area's reading at an x. Absolute: it is a timeline, not a nudge. */
    fun scrubFraction(x: Float, left: Float, right: Float): Float =
        if (right <= left) 0f else ((x - left) / (right - left)).coerceIn(0f, 1f)

    /** How much thicker an engaged ring gets. Doubled — see the note in drawRing. */
    const val ENGAGED_STROKE = 2f

    /** How long the ring takes to thicken and grey, and to come back. */
    const val ENGAGED_MS = 160f

    /** The mute button, beside the ring that now reports the volume. */
    const val MUTE_H = 27f

    /**
     * How far CLEAR of the ring's outer edge the speaker sits.
     *
     * True clearance, which it was not: the glyph is centred on its orbit, so adding the
     * gap to the ring's outer radius put the glyph's inner half back over the ring and
     * ten units of "gap" read as touching it. Half the glyph's height has to come out of
     * the orbit as well — see RearDialView, where the orbit is computed.
     */
    const val MUTE_GAP = 30f

    /**
     * The mute target: a patch of panel beside the speaker, at the speaker's height.
     *
     * Not the speaker itself, which is the frustrating part. The lower ring is drawn over
     * the camera glass and there is no digitizer behind it, so wherever the arc carries
     * the glyph is somewhere a finger cannot reach — the button has to live just clear of
     * the cutout and track the glyph's height instead.
     *
     * And not the whole volume area either, which is what it was: tapping anywhere above
     * the transport row muted the music, so every stray touch on two thirds of the page
     * silenced it.
     */
    const val MUTE_TARGET_W = 104f
    const val MUTE_TARGET_H = 92f

    /**
     * How far inside the panel's bottom edge the speaker must stay.
     *
     * Not a hairline. At two units the glyph is technically on the panel and visibly
     * touching the edge, which reads as clipped whether or not it is — on a screen with
     * rounded corners, a shape against the boundary looks like a shape running out of it.
     */
    const val SPEAKER_MARGIN = 12f

    /**
     * The furthest round the arc the speaker can go before it leaves the panel.
     *
     * Derived, not chosen. At double size and double spacing the glyph orbits 178 units
     * out, and the lower ring is centred 420 down a 572 panel — so at the arc's own end,
     * five o'clock, the glyph's bottom lands twenty units past the edge. It fits out to
     * about twenty to five and no further.
     *
     * So the arc still runs its full length and the speaker rides it as far as it can,
     * then holds. Above roughly four fifths of the range the glyph stops marking the
     * exact head. That is the trade a bigger glyph buys, and it is stated here rather
     * than by a hardcoded angle so it corrects itself if any of the sizes move.
     */
    fun speakerLimit(ringCy: Float, orbit: Float, panelHeight: Float): Float {
        val room = (panelHeight - SPEAKER_MARGIN - MUTE_H / 2f - ringCy) / orbit
        // The arc's LOWEST point, whichever end of it that is. It used to be the far end
        // by construction; the arc now fills upward, so the bottom is where it starts.
        if (room >= 1f) return maxOf(VOLUME_ARC_START, VOLUME_ARC_START + VOLUME_ARC_SWEEP)
        return Math.toDegrees(Math.asin(room.toDouble())).toFloat()
    }

    /**
     * How far OUTSIDE a ring its labels sit, and how big they are.
     *
     * Outside is not a preference, it is the only place they can be seen. The rings are
     * drawn concentric with the phone's camera lenses, and the lens is what fills the
     * hole in the middle — there is no display inside a ring. Everything put there was
     * rendered perfectly into the framebuffer and painted onto a camera.
     *
     * Small, because the labels no longer have to share space with the end marks: the arc
     * is turned half an hour so its marks sit clear of them. There is not much room to
     * spare either way — the two rings are 276 apart with a radius of 128 and a stroke
     * of 25 each, which leaves about twenty units of panel between them. Labels reach
     * into that gap and out toward the main display, and the angles they sit at are
     * chosen so they land where there is space.
     */
    const val LABEL_CLEAR = 3f
    const val LABEL_SIZE = 15f

    /**
     * How much the curved labels are thickened.
     *
     * A stroke over the fill rather than a bold weight, because there is no bold weight:
     * Departure Mono ships one face, and Paint's synthetic bold does the same thing with
     * less control. These labels are 15 units, bent, anti-aliased and sitting over the
     * camera glass at the edge of the panel — the thinnest type on the display in the
     * least favourable place for it, which is why they alone get this.
     */
    const val LABEL_WEIGHT = 1.3f

    /**
     * The label radius for a ring of this size.
     *
     * Text stands perpendicular to its path, so which way up it is decides how much room
     * it needs beyond the arc: [outward] glyphs grow away from the ring and only the
     * baseline has to clear it, while inward-facing ones grow back toward it and have to
     * start a whole line-height further out.
     */
    fun labelRadius(ringRadius: Float, stroke: Float, outward: Boolean): Float {
        val edge = ringRadius + stroke / 2f + LABEL_CLEAR
        return if (outward) edge else edge + LABEL_SIZE
    }


    /** What the volume ring is called. Named, because a bare arc around a lens is not
     *  self-describing the way a bar under a track name is. */
    const val VOLUME_LABEL = "VOLUME"

    /**
     * The volume arc: two o'clock to five o'clock on the ring's face.
     *
     * A SEGMENT rather than a whole circle, and it earns the difference. Playback fills a
     * ring because a track has a beginning and an end and going all the way round means
     * finished; volume has neither, and a full circle for it invited the same reading —
     * a ring at three quarters looked like three quarters of something completed.
     *
     * Down the right-hand side, where it lands beside the mute button rather than
     * opposite it. In Android's angles zero is three o'clock and they run clockwise, so
     * an hour on the clock face is thirty degrees and two o'clock is minus thirty.
     */
    /**
     * How far the volume arc is turned back from two o'clock.
     *
     * Anticlockwise, because five o'clock is the bottom-right corner of a panel that is
     * 572 tall with this ring centred 420 down it — the arc's far end and the speaker
     * riding it were pressed against the edge. Turned back a half hour the same segment
     * runs half past one to half past four, which is clear of it.
     *
     * It also buys back the speaker's full travel: at half past four the glyph fits
     * outside the ring without the panel edge clamping it, so it marks the true head of
     * the arc across the whole range rather than stopping short of it.
     */
    const val VOLUME_ROTATE = -15f

    /** How much of the ring the volume segment occupies: half past one to half past four. */
    const val VOLUME_ARC_SPAN = 90f

    /**
     * The volume arc fills UPWARD: silent at the bottom of the segment, loudest at the top.
     *
     * So it begins at the segment's lower end and sweeps anticlockwise, which is why the
     * sweep is negative. It ran the other way first and it was wrong for a reason that is
     * obvious once seen — the speaker glyph rides the head of this arc, and the head is
     * where the reading IS. A volume that fills downward sends the speaker to the floor as
     * it gets louder, which is backwards in every place a volume has ever been drawn, and
     * backwards against the drag that sets it: up is louder there too. See [volumeTravel].
     */
    const val VOLUME_ARC_START = -30f + VOLUME_ROTATE + VOLUME_ARC_SPAN
    const val VOLUME_ARC_SWEEP = -VOLUME_ARC_SPAN

    /**
     * The playback arc: half past ten to five o'clock, clockwise over the top.
     *
     * A gauge rather than a full circle, and it leaves its gap at the BOTTOM — which is
     * where the ring's own label sits, and where the volume's segment is on the ring
     * below. Two dials with their openings pointing at each other read as a pair; two
     * closed circles read as two lenses with rings drawn on them.
     *
     * Six and a half hours of the clock face is 195 degrees. In Android's angles zero is
     * three o'clock, so half past ten is 225.
     */
    /**
     * How far round the playback arc is turned to make room for its labels.
     *
     * Half an hour on the clock face. The arc runs the same length and simply starts and
     * ends later — half past ten becomes eleven, five becomes half past five — while the
     * two labels stay where they were. What that opens is the angular gap between where
     * a label sits and where the arc now begins, which is the room the label needed.
     *
     * Turning the dial is the right axis for this. Sliding the whole ring sideways was
     * the first attempt: it made room, and it moved a ring that is drawn concentric with
     * a camera lens, which is the one thing about these that must not move.
     */
    const val PLAYBACK_ROTATE = 15f

    const val PLAYBACK_ARC_START = 225f + PLAYBACK_ROTATE
    const val PLAYBACK_ARC_SWEEP = 195f

    /**
     * Where the two labels sit: the arc's ORIGINAL ends, before it was turned.
     *
     * They stay put and the arc moves out from under them — which is the whole point of
     * turning it rather than moving the labels, since outside the top ring there is
     * nowhere for a label to move to.
     */
    const val ELAPSED_AT = 225f
    const val TOTAL_AT = 60f

    /**
     * The record changer: how long the outgoing disc takes to leave, and the next to
     * arrive, and how much smaller than its ring a disc is when it is in one.
     *
     * The rings are drawn around the camera lenses, and there is no display inside a
     * ring — so a disc shrunk to that size is a disc that has gone. That is what makes
     * them usable as the two ends of this: the record does not fade out, it leaves
     * through the top lens, and the next one comes up out of the bottom one.
     *
     * The exit is quicker than the entrance. Something leaving needs only to be seen
     * going; something arriving is what you are meant to look at.
     */
    const val SWAP_EXIT_MS = 380f
    const val SWAP_ENTER_MS = 520f
    const val SWAP_RING_INSET = 5f

    /**
     * The stubs marking where each arc begins and ends.
     *
     * The arcs have no groove behind them, which is right — a full grey circle behind a
     * volume was saying "this much of a whole" about a value with no whole. But it left
     * a silent volume showing nothing at all, and a control you cannot find at zero is a
     * control you cannot turn up. Two short marks say where the range is without drawing
     * the whole of it.
     */
    /**
     * The marks at each end of an arc: short lines pointing straight OUT of the ring.
     *
     * Radial, not tangential. Drawn along the ring they are arcs of the same circle as
     * the reading — parallel to it, in its colour, and at a glance indistinguishable from
     * a bit of arc already filled in. Perpendicular, they cross the scale instead of
     * lying on it, which is what a mark on a dial has always looked like.
     */
    const val TICK_GAP = 6f
    const val TICK_LEN = 14f
    const val TICK_STROKE = 2f

    /** Where that arc ends: half past five, once it has been turned. */
    val PLAYBACK_ARC_END: Float get() = PLAYBACK_ARC_START + PLAYBACK_ARC_SWEEP - 360f

    /**
     * Where the mute glyph stops showing waves.
     *
     * One threshold, not a scale. The icon is a three-state readable-from-across-a-room
     * summary — silent, some, plenty — and a fourth state would be a distinction nobody
     * can make out at 26 units.
     */
    const val VOLUME_LOUD = 0.5f

    /**
     * The horizontal centre of this page, and the margins its full-width parts keep.
     *
     * Centred on the USABLE half of the panel for the same reason the record is: the left
     * 296 units are camera glass with no display behind them.
     */
    fun centreX(panelWidth: Float, cutoutRight: Float): Float =
        (cutoutRight + panelWidth) / 2f

    /** The progress bar's span, inside the margins, leaving room for both clocks. */
    fun barLeft(cutoutRight: Float, clockWidth: Float): Float =
        cutoutRight + MARGIN + clockWidth + CLOCK_GAP

    fun barRight(panelWidth: Float, clockWidth: Float): Float =
        panelWidth - MARGIN - clockWidth - CLOCK_GAP


    /**
     * A duration as a clock, dropping the hours when there are none.
     *
     * "3:45" rather than "0:03:45". A leading zero-hour is noise on the overwhelming
     * majority of tracks, and the field is not fixed-width — it is right- or left-aligned
     * against a bar, not tabulated against anything.
     */
    fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val seconds = total % 60L
        val minutes = (total / 60L) % 60L
        val hours = total / 3600L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /** The widest clock a track of this length can produce, in characters. */
    fun clockWidth(durationMs: Long): Int = clock(durationMs).length

    const val MARGIN = 34f
}
