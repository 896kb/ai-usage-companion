package dev.kb896.aiusage.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.TextView
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PanelTheme
import dev.kb896.aiusage.ui.glass.GlassSheet
import dev.kb896.aiusage.ui.glass.GlassSheetDrawable
import dev.kb896.aiusage.ui.glass.Ink
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test fun `under GLASS the confirm keeps its colour after the sheet is painted`() {
        ConfigStore(activity).save(PanelConfig(theme = PanelTheme.GLASS))
        val dialog = Modal.show(
            Modal.builder(activity).setTitle("t").setMessage("m")
                .setPositiveButton("Sign out", null).setNegativeButton("Keep", null),
            danger = true,
        )
        val panel = panel(dialog)
        GlassSheet.paint(dialog, panel, lum = 0.2f, danger = true)
        assertEquals(Panel.Palette.CRIT, dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
        assertEquals(Ink.secondary(0.2f), dialog.getButton(AlertDialog.BUTTON_NEGATIVE).currentTextColor)
        assertEquals(Ink.primary(0.2f), dialog.findViewById<TextView>(android.R.id.message)!!.currentTextColor)
    }
}
