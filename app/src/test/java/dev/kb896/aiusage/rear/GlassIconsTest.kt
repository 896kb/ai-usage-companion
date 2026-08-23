package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.PanelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassIconsTest {

    private val used = listOf(
        PanelIcons.CLOCK, PanelIcons.CALENDAR, PanelIcons.BATTERY, PanelIcons.CHARGING,
        PanelIcons.CAMERA, PanelIcons.PLAY, PanelIcons.PAUSE, PanelIcons.PREV,
        PanelIcons.NEXT, PanelIcons.VOLUME_OFF,
    )

    /** Every glyph the panel actually draws has a glass version to switch to. */
    @Test fun `the glyphs the panel uses are all paired`() {
        for (icon in used) {
            assertNotNull("a glyph the panel draws has no glass counterpart",
                GlassIcons.of(icon))
        }
    }

    /**
     * The path data has to survive the trip from Lucide's SVG. It is generated, so what
     * is worth checking is not the shapes but that nothing arrived empty or truncated —
     * a path that fails to parse draws nothing at all, silently.
     */
    @Test fun `every glyph carries real path data`() {
        for (icon in used) {
            val d = GlassIcons.of(icon)!!.data
            assertTrue("empty path data", d.length > 20)
            assertTrue("does not begin with a move: $d", d.trimStart().startsWith("M") ||
                d.trimStart().startsWith("m"))
        }
    }

    // -- footprints ------------------------------------------------------------------

    /**
     * THE TEST THAT EARNS ITS KEEP. The panel lays out against glyph widths in half a
     * dozen places — the transport row measures three to centre the group, the camera is
     * positioned by its own width, the media row's right edge is derived from it. Ask the
     * icon directly and you get ORIGINAL's answer in both themes, which is wrong in one
     * of them and wrong quietly.
     */
    @Test fun `the footprint follows the theme, not the glyph`() {
        for (icon in used) {
            assertEquals("ORIGINAL should get the traced proportions",
                icon.aspect, Glyphs.aspect(icon, PanelTheme.ORIGINAL), 0.00001f)
            assertEquals("GLASS should get Lucide's square grid",
                GlassIcons.ASPECT, Glyphs.aspect(icon, PanelTheme.GLASS), 0.00001f)
        }
    }

    /** A glyph with no glass version keeps its own box rather than being squared off. */
    @Test fun `an unpaired glyph is unchanged in either theme`() {
        val unpaired = PanelIcons.VOLUME_HIGH
        assertTrue("this test needs a genuinely unpaired glyph",
            GlassIcons.of(unpaired) == null)
        assertEquals(unpaired.aspect, Glyphs.aspect(unpaired, PanelTheme.GLASS), 0.00001f)
    }

    /** The two sets genuinely disagree, or the plumbing above is untested. */
    @Test fun `the traced set is not already square`() {
        assertTrue("ORIGINAL's battery should be tall and narrow",
            PanelIcons.BATTERY.aspect < 0.7f)
        assertTrue("ORIGINAL's camera should be wide",
            PanelIcons.CAMERA.aspect > 1.05f)
    }

    // -- ink -------------------------------------------------------------------------

    /** The transport stays solid; it is the only thing here meant to be pressed. */
    @Test fun `the things you press are solid and the labels are line work`() {
        for (icon in listOf(PanelIcons.PLAY, PanelIcons.PAUSE, PanelIcons.PREV,
            PanelIcons.NEXT, PanelIcons.VOLUME_OFF)) {
            assertEquals("a target glyph is a hairline",
                GlassIcons.Ink.SOLID, GlassIcons.of(icon)!!.ink)
        }
        for (icon in listOf(PanelIcons.CLOCK, PanelIcons.CALENDAR, PanelIcons.BATTERY,
            PanelIcons.CHARGING, PanelIcons.CAMERA)) {
            assertEquals("a label glyph is filled in",
                GlassIcons.Ink.LINE, GlassIcons.of(icon)!!.ink)
        }
    }

    /** Weight is in the icon's own units, so a glyph reads the same at any size. */
    @Test fun `the stroke is stated on Lucide's grid`() {
        assertEquals(24f, GlassIcons.VIEWPORT, 0f)
        assertTrue("too fine to see", GlassIcons.STROKE > 1f)
        assertTrue("too heavy to read", GlassIcons.STROKE < 4f)
    }
}
