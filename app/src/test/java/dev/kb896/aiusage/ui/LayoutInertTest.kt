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

    @Test fun `under ORIGINAL every twin in the layout is inert`() {
        ConfigStore(context).save(PanelConfig())
        context.setTheme(android.R.style.Theme_Material_NoActionBar)
        val root = LayoutInflater.from(context).inflate(R.layout.activity_main, null)
        // Laid out before anything is read off it: a card takes its bottom margin in
        // setLayoutParams and a slider its drawables on the way through measure, so an
        // unmeasured tree would pass these whether the twins were inert or not.
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, 1080, root.measuredHeight)
        val views = all(root)
        val cards = views.filterIsInstance<GlassCard>()
        assertTrue("sections declared as cards", cards.size >= 5)
        cards.forEach { assertEquals(0, it.paddingLeft + it.paddingTop + it.paddingRight + it.paddingBottom) }
        // The 12dp gap between cards is GLASS's; under ORIGINAL the sections sit where
        // the hairlines put them, so the margin must still be whatever the XML said.
        cards.forEach {
            assertEquals("a card takes no glass margin", 0,
                (it.layoutParams as LinearLayout.LayoutParams).bottomMargin)
        }
        assertTrue(views.filterIsInstance<GlassButton>().size >= 6)
        assertEquals(5, views.filterIsInstance<GlassSlider>().size)
        views.filterIsInstance<GlassSlider>().forEach { assertTrue(it.thumb != null) }
        // GLASS clears these so it can draw the track itself; ORIGINAL keeps the
        // PanelSeekBar style, and a null progressDrawable is a SeekBar drawing nothing.
        views.filterIsInstance<GlassSlider>().forEach { assertTrue(it.progressDrawable != null) }
        // The icon button under GLASS gets minimums and no background, both of which move
        // padding; under ORIGINAL it must sit where a plain ImageButton would.
        val plain = ImageButton(context)
        val icons = views.filterIsInstance<GlassIconButton>()
        assertEquals(1, icons.size)
        icons.forEach {
            assertEquals(plain.paddingLeft, it.paddingLeft)
            assertEquals(plain.paddingTop, it.paddingTop)
            assertEquals(plain.paddingRight, it.paddingRight)
            assertEquals(plain.paddingBottom, it.paddingBottom)
        }
        // The title swatch is a chip now, and under ORIGINAL a chip is a plain View.
        val chip = root.findViewById<View>(R.id.settings_title_color)
        assertTrue("the swatch is declared as a chip", chip is GlassChip)
        assertFalse((chip as GlassChip).glass)
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
