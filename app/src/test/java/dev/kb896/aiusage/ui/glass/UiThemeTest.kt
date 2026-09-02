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
}
