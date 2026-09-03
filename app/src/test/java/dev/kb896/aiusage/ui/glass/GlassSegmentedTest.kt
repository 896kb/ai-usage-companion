package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassSegmentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `it starts on the given option and clamps`() {
        val s = GlassSegmented(context, listOf("A", "B", "C"), selected = 7)
        assertEquals(2, s.selected)
    }

    @Test fun `select reports a change once and ignores a repeat`() {
        val s = GlassSegmented(context, listOf("A", "B", "C"), 0)
        val seen = ArrayList<Int>()
        s.onChange = { seen += it }
        s.select(2); s.select(2)
        assertEquals(listOf(2), seen)
        assertEquals(2, s.selected)
    }

    @Test fun `a tap on the middle third selects the middle option`() {
        val s = GlassSegmented(context, listOf("A", "B", "C"), 0)
        s.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        s.layout(0, 0, 300, s.measuredHeight)
        assertEquals(1, s.optionAt(150f))
        assertEquals(2, s.optionAt(299f))
    }
}
