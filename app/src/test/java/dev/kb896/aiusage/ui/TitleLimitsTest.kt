package dev.kb896.aiusage.ui

import dev.kb896.aiusage.data.PanelConfig
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caps have to admit the app's own defaults.
 *
 * This exists because the first version did not. Measuring the bar column the masthead
 * is CENTRED OVER — rather than the space it can actually occupy — gave ten characters,
 * two short of CLAUDE USAGE, so the shipped default would have been truncated the moment
 * anyone opened the field. A limit that rejects the shipped value is not a limit, it is
 * a bug wearing one's clothes.
 *
 * Pure arithmetic, no Paint: this asserts the CONSTANTS are sane, using the font's known
 * advance ratio. The runtime measurement is the same sum with the real typeface.
 */
class TitleLimitsTest {

    /** Departure Mono: 1274/2002 units per em, read off the shipped OTF. */
    private val advance = 0.6364f

    private fun fits(text: String, size: Float, width: Float) =
        text.length * size * advance <= width

    /** Centred at 581, bounded left by the content edge and right by the extras column. */
    private val mastheadWidth = 407f
    private val headingWidth = 402f

    @Test fun `the default title fits the masthead`() {
        assertTrue(
            "the shipped default would be truncated",
            fits(PanelConfig.DEFAULT.title, 40f, mastheadWidth),
        )
    }

    @Test fun `the default account names fit their heading`() {
        for (name in listOf("PERSONAL", "WORK")) {
            assertTrue("$name would be truncated", fits(name, 22.5f, headingWidth))
        }
    }

    /** And the cap is not so generous that it stops being one. */
    @Test fun `a title twice the default does not fit`() {
        assertTrue(fits(PanelConfig.DEFAULT.title.repeat(2), 40f, mastheadWidth).not())
    }
}
