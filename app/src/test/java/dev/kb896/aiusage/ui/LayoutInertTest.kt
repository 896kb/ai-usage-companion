package dev.kb896.aiusage.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import android.widget.ImageButton
import android.widget.LinearLayout
import dev.kb896.aiusage.ui.glass.GlassButton
import dev.kb896.aiusage.ui.glass.GlassCard
import dev.kb896.aiusage.ui.glass.GlassChip
import dev.kb896.aiusage.ui.glass.GlassIconButton
import dev.kb896.aiusage.ui.glass.GlassSlider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // Measuring the orphan card directly (no parent) does NOT reproduce this: View.measure()
    // runs a view's own onMeasure regardless of that view's own visibility — GONE is only
    // honoured by a PARENT deciding whether to measure/lay out a child at all (LinearLayout
    // skips a GONE child when summing its own total length, but still adds ITS OWN padding
    // either way). Measuring the orphan card gave 32 (its GLASS padding), not 0 — confirmed
    // by running it. What collapseEmptyCards() actually relies on is settings_layout (a
    // LinearLayout, same as this parent) skipping a GONE child entirely, which is what this
    // measures instead.
    @Test fun `a card whose children are all gone has no height in either theme`() {
        context.setTheme(android.R.style.Theme_Material_NoActionBar)
        for (theme in listOf(PanelConfig(), PanelConfig(theme = dev.kb896.aiusage.data.PanelTheme.GLASS))) {
            ConfigStore(context).save(theme)
            val card = dev.kb896.aiusage.ui.glass.GlassCard(context)
            card.addView(View(context).apply { visibility = View.GONE })
            card.visibility = View.GONE
            val parent = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
            parent.addView(card, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            parent.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            assertEquals(0, parent.measuredHeight)
        }
        ConfigStore(context).save(PanelConfig())
    }
}
