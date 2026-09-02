package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PanelTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassCardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @After fun reset() { ConfigStore(context).save(PanelConfig()) }

    @Test fun `under ORIGINAL it is a plain LinearLayout`() {
        ConfigStore(context).save(PanelConfig())
        val card = GlassCard(context)
        assertFalse(card.glass)
        assertEquals(0, card.paddingLeft + card.paddingTop + card.paddingRight + card.paddingBottom)
        assertNull(card.background)
        assertTrue(card.willNotDraw())
        val host = LinearLayout(context); host.addView(card)
        host.measure(0, 0); host.layout(0, 0, 100, 100)
        assertEquals(0, (card.layoutParams as LinearLayout.LayoutParams).bottomMargin)
    }

    @Test fun `under GLASS it pads, separates and draws`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        val card = GlassCard(context)
        assertTrue(card.glass)
        val d = context.resources.displayMetrics.density
        assertEquals((16 * d).toInt(), card.paddingLeft)
        assertFalse(card.willNotDraw())
        val host = LinearLayout(context); host.addView(card)
        host.measure(0, 0); host.layout(0, 0, 100, 100)
        assertEquals((12 * d).toInt(), (card.layoutParams as LinearLayout.LayoutParams).bottomMargin)
    }

    @Test fun `ink recolours its text from its cell`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        val card = GlassCard(context)
        val label = TextView(context).apply { textSize = 11f }
        val value = TextView(context).apply { textSize = 14f }
        card.addView(label); card.addView(value)
        card.ink()   // no backdrop: fallback luminance, which is dark, so ink is white
        assertEquals(Ink.secondary(Pane.FALLBACK_LUMINANCE), label.currentTextColor)
        assertEquals(Ink.primary(Pane.FALLBACK_LUMINANCE), value.currentTextColor)
    }
}
