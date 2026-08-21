package dev.kb896.aiusage.rear

/**
 * Lyrics curved around the record, on the media page.
 *
 * Two arcs, concentric with the disc. The line being sung sits on the inner one, bright
 * and against the record; the line coming next waits dimmed on the outer one. When the
 * song moves on, the outer line travels INWARD and brightens as the old one fades — so
 * the change has a direction, and the thing you were reading ahead of is the thing that
 * arrives. A cross-fade in place would say the same words with none of that.
 *
 * The record gives up the room. Its size is already derived from whatever is above it —
 * see MediaPage.disc — so lyrics claim their band exactly the way a notification does,
 * and the disc shrinks to fit rather than the text being squeezed into a margin.
 */
object LyricsRing {

    /** Type size for a lyric line, before any shrinking to fit. */
    const val SIZE = 15f

    /** The smallest it may shrink to before the line is cut instead. */
    const val MIN_SIZE = 11f

    /** Between the record's edge and the baseline of the line being sung. */
    const val GAP = 10f

    /** Between the two lines' baselines, beyond the height of the inner one. */
    const val LINE_GAP = 6f

    /**
     * How much vertical room the pair of arcs needs above the record.
     *
     * Handed to MediaPage.disc as though it were a notification, which is the whole trick:
     * the disc already knows how to be smaller when something is above it, so lyrics do
     * not need their own layout pass, and the two of them together simply shrink it twice.
     */
    const val STACK = GAP + SIZE + LINE_GAP + SIZE

    /** Where the sung line's baseline sits, for a record of this radius. */
    fun currentRadius(discRadius: Float): Float = discRadius + GAP

    /** Where the next line waits. */
    fun nextRadius(discRadius: Float): Float =
        currentRadius(discRadius) + SIZE + LINE_GAP

    /** How far the old line sinks toward the record as it goes. */
    const val EXIT_DROP = 14f

    // -- straight lines, for the square cover ------------------------------------------

    /** How close a line may come to the panel's edge, or to the camera cutout. */
    const val SIDE_MARGIN = 14f

    /**
     * How wide a straight line may be, centred on the cover.
     *
     * Symmetric about the cover's centre rather than filling the space to each edge: the
     * lines are centred, so the usable width is twice the SHORTER side. Taking the whole
     * span would let a long line run past one edge while leaving a gap at the other.
     */
    fun straightRoom(cx: Float, leftLimit: Float, rightLimit: Float): Float =
        2f * minOf(cx - (leftLimit + SIDE_MARGIN), (rightLimit - SIDE_MARGIN) - cx)
            .coerceAtLeast(0f)

    /**
     * The baseline of the line being sung, above a cover whose top edge is [coverTop].
     *
     * Text sits above its own baseline, so this is [GAP] clear of the cover and the glyphs
     * run upward from there — which is what makes the stack come out to exactly [STACK],
     * the same band the curved arcs claim. Both styles ask the record for the same room.
     */
    fun rowBaseline(coverTop: Float): Float = coverTop - GAP

    /** The baseline of the line waiting its turn, stacked above the sung one. */
    fun rowNextBaseline(coverTop: Float): Float = rowBaseline(coverTop) - SIZE - LINE_GAP

    /** How long a line takes to come in and the last one to leave. */
    const val PROMOTE_MS = 380f

    /**
     * How long the record takes to give up the band, or take it back.
     *
     * The words do not simply appear around a disc that has silently become smaller. The
     * record is the object this page is built around, and an object changing size between
     * two frames reads as a rendering fault rather than as room being made.
     */
    const val BAND_MS = 420f

    /**
     * How long the pair of lines takes to fade in or out AS A BLOCK.
     *
     * Distinct from [PROMOTE_MS], and they answer different questions. Promotion is a song
     * moving from one line to the next, which has a direction and travels. This is the
     * whole apparatus arriving or leaving — the feature being switched on, the page being
     * scrolled to, a track turning out to have words at all — where there is no direction
     * to it and movement would invent one. Those simply fade.
     */
    const val FADE_MS = 300f

    /**
     * The smallest record worth wrapping words around.
     *
     * Below this the arcs are so tightly curved that a line becomes a ring of separate
     * rotated letters rather than a sentence, and the record itself is a token. It is
     * reached in exactly one situation: a notification with a group card under it, on a
     * page that is already giving room to two arcs. There is not space for both, and the
     * notification wins — it is asking for attention for a few seconds, and the words will
     * still be there afterwards. See RearDialView.lyricsFit, where they fade out for it
     * rather than being cut off mid-thought.
     */
    const val MIN_DISC = 90f

    /** How dim the line that has not been sung yet is. */
    const val NEXT_ALPHA = 0x77

    /**
     * The widest arc a line may occupy, centred on twelve o'clock.
     *
     * Not the whole circle, and not for looks. Text bent much past this reaches the sides
     * of the record, where it runs into the volume strip on one side and toward the camera
     * cutout on the other — and where a reader has to tilt their head to follow it. A line
     * that will not fit in this shrinks, and then is cut.
     */
    const val MAX_SWEEP = 170f

    /** Twelve o'clock. Android's angles run clockwise from three. */
    const val CENTRE_DEG = 270f

    /** A line, sized and possibly shortened to fit the arc it has to live on. */
    class Fitted(val text: String, val size: Float)

    /**
     * Fit a line to the arc.
     *
     * Shrinks first, because a smaller whole line beats a larger truncated one — the words
     * are the point. Only once it is at [MIN_SIZE] and still too long does it get cut, and
     * then at a word boundary with an ellipsis, so what is shown is a phrase rather than a
     * fragment ending mid-word.
     *
     * [advanceAt] is the width of one character at a given size, which the caller measures.
     * Departure Mono is exactly monospaced — verified, every glyph the same advance — so
     * one measurement describes the whole string and the fit is arithmetic rather than a
     * search over renderings.
     */
    fun fit(text: String, radius: Float, advanceAt: (Float) -> Float): Fitted =
        fitTo(text, radius * Math.toRadians(MAX_SWEEP.toDouble()).toFloat(), advanceAt)

    /**
     * The same fitting against a plain length, for lines that are not bent.
     *
     * Under the square cover the words are straight — see [rowBaseline]. A curve is what
     * you do to text when it has to follow the edge of a record; a photograph has no edge
     * to follow, and bending a line around one anyway would be decoration borrowed from a
     * shape that is not there.
     */
    fun fitTo(text: String, room: Float, advanceAt: (Float) -> Float): Fitted {
        if (text.isEmpty()) return Fitted("", SIZE)
        var size = SIZE
        while (size > MIN_SIZE && advanceAt(size) * text.length > room) {
            size -= 1f
        }
        val per = advanceAt(size)
        val fits = if (per <= 0f) text.length else (room / per).toInt()
        if (text.length <= fits) return Fitted(text, size)

        // Cut at a word, not mid-syllable. If the first word alone overruns there is no
        // boundary to use and it is cut where it lies.
        val head = text.take(maxOf(fits - 1, 0))
        val space = head.lastIndexOf(' ')
        val kept = if (space > fits / 2) head.take(space) else head
        return Fitted(kept.trimEnd() + "…", size)
    }

    /**
     * How far through a promotion we are, 0 to 1.
     *
     * 1 means settled. A jump of more than one line is a seek rather than a song moving
     * on, and lands with no animation at all: sliding four lines' worth of promotion past
     * the reader would be four flickers saying nothing about where the music went.
     */
    fun promotion(from: Int, to: Int, elapsedMs: Float): Float =
        if (to != from + 1) 1f
        else (elapsedMs / PROMOTE_MS).coerceIn(0f, 1f)
}
