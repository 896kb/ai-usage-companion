package dev.kb896.aiusage.ui.glass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PanelTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiThemeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @After fun reset() { ConfigStore(context).save(PanelConfig()) }

    @Test fun `a new install starts on glass`() {
        assertEquals(PanelTheme.GLASS, PanelConfig().theme)
    }

    @Test fun `the front is glass whatever the back is`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.ORIGINAL))
        assertTrue(UiTheme.isGlass(context))
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        assertTrue(UiTheme.isGlass(context))
    }

    /** The cache behind the back's answer follows the store both ways. */
    @Test fun `the back's theme follows the store in both directions`() {
        val store = ConfigStore(context)
        store.save(PanelConfig(theme = PanelTheme.ORIGINAL))
        assertFalse(UiTheme.panelIsGlass(context))
        store.save(PanelConfig(theme = PanelTheme.GLASS))
        assertTrue(UiTheme.panelIsGlass(context))
        store.save(PanelConfig(theme = PanelTheme.ORIGINAL))
        assertFalse(UiTheme.panelIsGlass(context))
    }

    @Test fun `a config saved with ORIGINAL keeps it`() {
        val saved = PanelConfig.encode(PanelConfig(theme = PanelTheme.ORIGINAL))
        assertEquals(PanelTheme.ORIGINAL, PanelConfig.decode(saved).theme)
    }
}
