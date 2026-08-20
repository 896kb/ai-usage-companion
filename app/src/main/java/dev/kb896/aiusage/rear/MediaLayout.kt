package dev.kb896.aiusage.rear

/**
 * Where the media row's parts go along the panel's bottom band.
 *
 * Pure, and separate from RearDialView for the same reason ArtPlacement is: this is
 * arithmetic against a band 398 units wide holding a cover, two lines of text and three
 * buttons, and the one thing that can go wrong with it — the text column being squeezed
 * to nothing — is invisible on the back of a phone and provable on the JVM.
 *
 * Laid out from the RIGHT. The three buttons are fixed in size and position and the text
 * takes whatever is left, rather than the other way round: a target you press without
 * looking must not move because a track with a longer name came on.
 */
object MediaLayout {

    /**
     * The transport glyphs: how tall they are, and how far apart.
     *
     * 38, up from 24. At 24 they were about 20 units wide — roughly 1.2mm on a 400dpi
     * panel — which is a label, not a button you press without looking. The band is 70
     * tall, so there is room; what this costs is track title, and MediaLayoutTest holds
     * the floor on how much of that is left.
     */
    const val GLYPH_H = 38f

    /**
     * Between glyphs — and therefore how wide each one's touch band is.
     *
     * Widened from 20 after the first build: the three sat close enough that the bands
     * had to be generous to be hittable, and generous bands on 35-unit centres
     * overlapped. This is the honest fix — space them so each one can own its column
     * outright. Trimmed back to 22 when the glyphs themselves grew, since the glyph is
     * now most of the target rather than the gap.
     *
     * Every unit spent here comes out of the track title, which is why it is not wider
     * still. MediaLayoutTest holds the floor on what the text has left.
     */
    const val GLYPH_GAP = 22f

    /**
     * Between the text column and the first glyph.
     *
     * Wider than the gaps BETWEEN the glyphs, so the three read as one group rather
     * than as four things spaced evenly across a row.
     */
    const val TEXT_GAP = 16f

    /** Between the cover and the track title. */
    const val COVER_GAP = 18f

    const val TEXT_SIZE = 18f

    /** Baseline to baseline, title to artist. */
    const val LINE = 24f

    /** The artist line, against the title's full-strength tint. */
    const val ARTIST_ALPHA = 0x99

    /**
     * How far apart the two copies of a scrolling line run.
     *
     * Must be NARROWER than the column, and the first attempt had that backwards. The
     * reasoning was that a gap wider than the column keeps the copies from ever being on
     * screen together — which is true, and is the bug: it also guarantees a stretch with
     * NEITHER of them on screen. At 340 against a 155-unit column that was four and a
     * half seconds of blank line every lap, and it looked like the title had gone away.
     *
     * Both copies visible at once is not a fault, it is what a ticker is. What must never
     * happen is the line going empty, and a gap under the column width is exactly the
     * condition that prevents it. Six characters is enough to read as a separation.
     */
    const val MARQUEE_GAP = 68f

    /**
     * The progress bar under the row: how thick, and how far below the band it sits.
     *
     * Thin on purpose. It is the one thing here that is neither read nor pressed — a
     * glance tells you roughly where in the track you are, and anything thicker would
     * read as a fourth usage bar, which are actual readings.
     *
     * Underneath rather than above. Above put it directly beneath the last usage bar,
     * where a thin horizontal line in the same column is exactly what those are; below,
     * it sits in the panel's bottom margin with nothing to be confused with.
     */
    const val PROGRESS_H = 4f
    const val PROGRESS_GAP = 8f

    /** What the row needs BELOW the content band for its progress bar. */
    const val PROGRESS_TOTAL = PROGRESS_H + PROGRESS_GAP

    /** The left edge of each glyph, the top they share, and what the text has left. */
    class Row(
        val prev: Float,
        val play: Float,
        val next: Float,
        val glyphTop: Float,
        val textWidth: Float,
    ) {
        /**
         * Each glyph's horizontal centre, in the order the buttons are drawn.
         *
         * The hit test picks the NEAREST of these rather than the first rectangle a
         * touch falls inside, which is what the first build did — and it was wrong in a
         * way that only showed up on the device. Every target on this panel is padded by
         * CAMERA_SLOP, because a finger on the back of a phone is not aimed; three
         * padded rectangles on 35-unit centres overlap almost completely, so the first
         * one in the list swallowed the other two. Pressing pause called
         * skipToPrevious(), which on a track a few seconds in restarts it — a bug that
         * looked like a player quirk rather than a hit test.
         *
         * Nearest-centre cannot overlap. There is no ordering to get wrong, no padding
         * to tune, and the boundary between two buttons is exactly halfway between them
         * wherever they are moved to.
         */
        val centres: List<Float>
            get() = listOf(
                prev + GLYPH_H * PanelIcons.PREV.aspect / 2f,
                play + GLYPH_H * PanelIcons.PAUSE.aspect / 2f,
                next + GLYPH_H * PanelIcons.NEXT.aspect / 2f,
            )
    }

    fun of(left: Float, right: Float, centreY: Float): Row {
        // Each glyph is placed by its own width, not by a shared cell. They are traced
        // from a proportional-ish set — play is 16 wide where pause is 13 — and a fixed
        // cell would leave the gaps visibly uneven at 24 units.
        val next = right - GLYPH_H * PanelIcons.NEXT.aspect
        val play = next - GLYPH_GAP - GLYPH_H * PanelIcons.PAUSE.aspect
        val prev = play - GLYPH_GAP - GLYPH_H * PanelIcons.PREV.aspect
        return Row(
            prev = prev,
            play = play,
            next = next,
            glyphTop = centreY - GLYPH_H / 2f,
            textWidth = prev - TEXT_GAP - left,
        )
    }

    /**
     * How far through a track we are NOW, carried forward from the last report.
     *
     * The player speaks only when something changes; the panel draws on the frame clock.
     * Without this the bar would sit still for a whole track and then jump when the next
     * one started. Both processes read the same system elapsed-time clock, so this is
     * exact rather than an estimate.
     *
     * Only while PLAYING. A paused track's position does not advance, and carrying one
     * forward would walk the marker along a track that is not moving — which is worse
     * than not moving it, because it looks like it is working.
     *
     * Speed is respected because it is free to: a podcast at 1.5x reaches the end in two
     * thirds of the time, and a bar that ignored that would run visibly behind by the
     * end of an episode.
     */
    fun elapsed(
        positionMs: Long,
        positionAtMs: Long,
        speed: Float,
        playing: Boolean,
        durationMs: Long,
        nowMs: Long,
    ): Long {
        if (!playing) return positionMs.coerceAtLeast(0L)
        val carried = positionMs + ((nowMs - positionAtMs) * speed).toLong()
        val ceiling = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return carried.coerceIn(0L, ceiling)
    }

    /**
     * That position as a fraction of the track, or null when there is no track to be a
     * fraction of.
     *
     * Null rather than zero, and the caller draws nothing for it. A live stream or a
     * player that does not report a duration would otherwise get an empty bar above
     * every one of them — a reading that is never true, rather than one that happens to
     * be at the start.
     */
    fun progress(elapsedMs: Long, durationMs: Long): Float? {
        if (durationMs <= 0L) return null
        return (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /**
     * The first baseline, for a row with or without an artist line.
     *
     * The two lines are centred AS A PAIR rather than each in its own half of the slot:
     * a track with no artist would otherwise sit high with nothing under it.
     */
    fun firstBaseline(centreY: Float, hasArtist: Boolean): Float {
        // What is centred is the INK, which for two lines runs from the top of the first
        // to the baseline of the second: one cap height plus one line, not two lines.
        // Measuring it as two lines put the pair three units high — small enough to look
        // like nothing and exactly the kind of thing that reads as "slightly wrong" on a
        // panel without ever being identified.
        //
        // Cap height is taken as TEXT_SIZE rather than from Paint's ascent. This is a
        // pixel font drawn on a fixed grid and the two agree closely; the approximation
        // is what keeps this class free of Android types and therefore testable.
        val block = if (hasArtist) TEXT_SIZE + LINE else TEXT_SIZE
        return centreY - block / 2f + TEXT_SIZE
    }
}
