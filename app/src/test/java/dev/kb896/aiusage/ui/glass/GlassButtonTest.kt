package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.widget.Button
import android.widget.ImageButton
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PanelTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassButtonTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @After fun reset() { ConfigStore(context).save(PanelConfig()) }

    @Test fun `under ORIGINAL a glass button is the platform button`() {
        ConfigStore(context).save(PanelConfig())
        val plain = Button(context)
        val ours = GlassButton(context)
        assertFalse(ours.glass)
        assertNotNull(ours.background)
        assertEquals(plain.paddingLeft, ours.paddingLeft)
        assertEquals(plain.minHeight, ours.minHeight)
        assertEquals(plain.isAllCaps, ours.isAllCaps)
        val icon = GlassIconButton(context)
        assertFalse(icon.glass)
        assertEquals(ImageButton(context).paddingLeft, icon.paddingLeft)
    }

    @Test fun `under GLASS it sheds the platform chrome and takes the capsule`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        val ours = GlassButton(context)
        assertTrue(ours.glass)
        assertNull(ours.background)
        assertNull(ours.stateListAnimator)
        val d = context.resources.displayMetrics.density
        assertEquals((16 * d).toInt(), ours.paddingLeft)
        assertEquals((48 * d).toInt(), ours.minHeight)
        assertFalse(ours.isAllCaps)
    }
}
