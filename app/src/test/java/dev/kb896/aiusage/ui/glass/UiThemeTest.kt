package dev.kb896.aiusage.ui.glass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PanelTheme
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

    @Test fun `the default theme is not glass`() {
        ConfigStore(context).save(PanelConfig())
        assertFalse(UiTheme.isGlass(context))
    }

    @Test fun `glass is read from the stored config`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        assertTrue(UiTheme.isGlass(context))
    }

    /**
     * The answer is cached on the stored blob, so the cache has to follow the store both
     * ways: a stale `true` after a switch back to ORIGINAL would leave the whole screen
     * drawing glass over a background nothing built.
     */
    @Test fun `the cached answer follows the store in both directions`() {
        val store = ConfigStore(context)
        store.save(PanelConfig())
        assertFalse(UiTheme.isGlass(context))
        store.save(PanelConfig(theme = PanelTheme.GLASS))
        assertTrue(UiTheme.isGlass(context))
        store.save(PanelConfig())
        assertFalse(UiTheme.isGlass(context))
    }
}
