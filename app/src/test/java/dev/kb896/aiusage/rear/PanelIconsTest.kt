package dev.kb896.aiusage.rear

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The traced glyphs, checked for the one thing that has actually gone wrong with them.
 *
 * The camera shipped drawn battery-shaped — tall and narrow — because the drawing code
 * paired icons with aspects by hand and defaulted to the battery's. Icon now carries its
 * own aspect, which makes that particular bug unrepresentable; this is the backstop for
 * the generator, which is where an aspect could still arrive wrong. A glyph traced with
 * a broken bounding box does not throw, it just looks stretched on a screen nobody is
 * looking at.
 */
class PanelIconsTest {

    private val all = listOf(
        "CLOCK" to PanelIcons.CLOCK,
        "BATTERY" to PanelIcons.BATTERY,
        "CHARGING" to PanelIcons.CHARGING,
        "CAMERA" to PanelIcons.CAMERA,
        "CALENDAR" to PanelIcons.CALENDAR,
        "PLAY" to PanelIcons.PLAY,
        "PAUSE" to PanelIcons.PAUSE,
        "PREV" to PanelIcons.PREV,
        "NEXT" to PanelIcons.NEXT,
    )

    @Test fun `every icon has an aspect that could be a glyph`() {
        for ((name, icon) in all) {
            assertTrue("$name is $${icon.aspect} wide per unit tall",
                icon.aspect > 0.4f && icon.aspect < 2.5f)
        }
    }

    @Test fun `every icon has rectangles`() {
        for ((name, icon) in all) {
            assertTrue("$name traced to nothing", icon.rects.isNotEmpty())
        }
    }

    @Test fun `every rectangle stays inside its own box`() {
        for ((name, icon) in all) {
            for (r in icon.rects) {
                assertTrue("$name has a rect at ${r.x},${r.y}",
                    r.x >= -0.001f && r.y >= -0.001f)
                assertTrue("$name has a rect running to ${r.x + r.w},${r.y + r.h}",
                    r.x + r.w <= 1.001f && r.y + r.h <= 1.001f)
            }
        }
    }

    /**
     * The transport glyphs are the only ones on this panel meant to be PRESSED, and they
     * sit in a row where a width mismatch between them reads as a wobble. They came from
     * one font at one size, so they should be within a hair of each other in height-
     * relative width — this catches a wrong codepoint, which traces successfully and
     * silently gives you the wrong shape.
     */
    @Test fun `the transport glyphs are of a piece`() {
        val transport = listOf(PanelIcons.PLAY, PanelIcons.PAUSE, PanelIcons.PREV,
            PanelIcons.NEXT)
        val widest = transport.maxOf { it.aspect }
        val narrowest = transport.minOf { it.aspect }
        assertTrue("aspects run $narrowest..$widest", widest - narrowest < 0.35f)
    }
}
