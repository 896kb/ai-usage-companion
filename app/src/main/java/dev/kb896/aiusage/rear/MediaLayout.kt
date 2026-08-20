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

    /** The transport glyphs: how tall they are, and how far apart. */
    const val GLYPH_H = 24f

    /**
     * Between glyphs — and therefore how wide each one's touch band is.
     *
     * The glyphs are ~20 units wide, so the gap is most of the target. Widened from 20
     * after the first build: the three sat close enough that the bands had to be
     * generous to be hittable, and generous bands on 35-unit centres overlapped. This is
     * the honest fix — space them so each one can own its column outright.
     *
     * Every unit spent here comes out of the track title, which is why it is not wider
     * still. MediaLayoutTest holds the floor on what the text has left.
     */
    const val GLYPH_GAP = 24f

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
