package dev.kb896.aiusage.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.ui.glass.GlassButton
import dev.kb896.aiusage.ui.glass.GlassCard
import dev.kb896.aiusage.ui.glass.GlassSlider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayoutInertTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun all(root: View, out: MutableList<View> = ArrayList()): List<View> {
        out += root
        if (root is ViewGroup) for (i in 0 until root.childCount) all(root.getChildAt(i), out)
        return out
    }

    @Test fun `under ORIGINAL every twin in the layout is inert`() {
        ConfigStore(context).save(PanelConfig())
        context.setTheme(android.R.style.Theme_Material_NoActionBar)
        val root = LayoutInflater.from(context).inflate(R.layout.activity_main, null)
        val views = all(root)
        val cards = views.filterIsInstance<GlassCard>()
        assertTrue("sections declared as cards", cards.size >= 5)
        cards.forEach { assertEquals(0, it.paddingLeft + it.paddingTop + it.paddingRight + it.paddingBottom) }
        assertTrue(views.filterIsInstance<GlassButton>().size >= 6)
        assertEquals(5, views.filterIsInstance<GlassSlider>().size)
        views.filterIsInstance<GlassSlider>().forEach { assertTrue(it.thumb != null) }
    }
}
