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
import dev.kb896.aiusage.ui.glass.GlassSegmented
import dev.kb896.aiusage.ui.glass.GlassSwitch
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

    @Test fun `ORIGINAL builds the platform widgets`() {
        ConfigStore(context).save(PanelConfig())
        val parent = LinearLayout(context)
        SettingsRows.toggle(parent, "T", true) {}
        SettingsRows.choice(parent, "C", listOf("a", "b"), 0) {}
        val nav = SettingsRows.navigationButton(parent, "N") {}
        SettingsRows.colorRow(parent, "K", 0xFF112233.toInt()) {}
        assertNotNull(find(parent, Switch::class.java))
        assertNotNull(find(parent, Spinner::class.java))
        assertEquals(Button::class.java, nav.javaClass)
        assertNull(find(parent, GlassSwitch::class.java))
        assertNull(find(parent, GlassSegmented::class.java))
        assertNull(find(parent, GlassChip::class.java))
    }

    @Test fun `GLASS builds the glass controls and they report through the same callbacks`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        val parent = LinearLayout(context)
        var toggled: Boolean? = null
        var chosen: Int? = null
        SettingsRows.toggle(parent, "T", false) { toggled = it }
        SettingsRows.choice(parent, "C", listOf("a", "b", "c"), 1) { chosen = it }
        val nav = SettingsRows.navigationButton(parent, "N") {}
        SettingsRows.colorRow(parent, "K", 0xFF112233.toInt()) {}
        val switch = find(parent, GlassSwitch::class.java) as GlassSwitch
        val seg = find(parent, GlassSegmented::class.java) as GlassSegmented
        assertNull(find(parent, Switch::class.java))
        assertNull(find(parent, Spinner::class.java))
        assertTrue(nav is GlassButton)
        assertEquals(0xFF112233.toInt(), (find(parent, GlassChip::class.java) as GlassChip).colour)
        switch.performClick()
        assertEquals(true, toggled)
        assertEquals(1, seg.selected)
        seg.select(2)
        assertEquals(2, chosen)
    }
}
