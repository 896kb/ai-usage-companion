package dev.kb896.aiusage.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.ViewGroup
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PanelTheme
import dev.kb896.aiusage.ui.glass.GlassSheetDrawable
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModalGlassTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    @After fun reset() { ConfigStore(activity).save(PanelConfig()) }

    private fun panel(dialog: AlertDialog): android.view.View {
        val content = dialog.window!!.decorView.findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(0)
    }

    @Test fun `under ORIGINAL the dialog keeps the panel background`() {
        ConfigStore(activity).save(PanelConfig())
        val dialog = Modal.show(Modal.builder(activity).setTitle("t").setMessage("m"))
        assertFalse(panel(dialog).background is GlassSheetDrawable)
    }

    @Test fun `under GLASS the dialog's panel is a glass sheet`() {
        ConfigStore(activity).save(PanelConfig(theme = PanelTheme.GLASS))
        val dialog = Modal.show(Modal.builder(activity).setTitle("t").setMessage("m"))
        assertTrue(panel(dialog).background is GlassSheetDrawable)
    }
}
