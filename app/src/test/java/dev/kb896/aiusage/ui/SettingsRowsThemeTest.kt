package dev.kb896.aiusage.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PanelTheme
import dev.kb896.aiusage.ui.glass.GlassButton
import dev.kb896.aiusage.ui.glass.GlassChip
import dev.kb896.aiusage.ui.glass.GlassIconButton
import dev.kb896.aiusage.ui.glass.GlassSegmented
import dev.kb896.aiusage.ui.liquid.LiquidToggle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRowsThemeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @After fun reset() { ConfigStore(context).save(PanelConfig()) }

    private fun find(root: View, type: Class<*>): View? {
        if (type.isInstance(root)) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), type)?.let { return it }
        return null
    }

    @Test fun `GLASS builds the glass controls and they report through the same callbacks`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        val parent = LinearLayout(context)
        var toggled: Boolean? = null
        SettingsRows.toggle(parent, "T", false) { toggled = it }
        SettingsRows.choice(parent, "C", listOf("a", "b", "c"), 1) {}
        val nav = SettingsRows.navigationButton(parent, "N") {}
        SettingsRows.colorRow(parent, "K", 0xFF112233.toInt()) {}
        val switch = find(parent, LiquidToggle::class.java) as LiquidToggle
        assertNull(find(parent, Switch::class.java))
        assertNull(find(parent, Spinner::class.java))
        assertTrue(nav is GlassButton)
        assertEquals(0xFF112233.toInt(), (find(parent, GlassChip::class.java) as GlassChip).colour)
        switch.performClick()
        assertEquals(true, toggled)
        SettingsRows.reorderable(parent, "R", canMoveUp = true, onMoveUp = {}) {}
        SettingsRows.colorGrid(parent, listOf(SettingsRows.Swatch("S", 0xFF445566.toInt()) {}))
        assertTrue(find(parent, GlassIconButton::class.java) is GlassIconButton)
        val chips = ArrayList<GlassChip>()
        fun chipsIn(v: View) { if (v is GlassChip) chips += v; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) chipsIn(v.getChildAt(i)) }
        chipsIn(parent)
        assertTrue("colorRow and colorGrid chips: ${chips.size}", chips.size >= 2)
        assertTrue(chips.any { it.colour == 0xFF445566.toInt() })
    }
}
