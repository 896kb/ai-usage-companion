package dev.kb896.aiusage.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreditsTest {

    @Test fun `the first credit is the Pro Max's, with their reddit profile`() {
        val first = Credits.PEOPLE.first()
        assertEquals("u/Over_Squash3318", first.name)
        assertEquals("https://www.reddit.com/user/Over_Squash3318/", first.url)
        assertTrue(first.why.contains("Pro Max"))
    }

    @Test fun `every credit links somewhere safe and says what it is for`() {
        for (c in Credits.PEOPLE) {
            assertTrue(c.name, c.url.startsWith("https://"))
            assertTrue(c.name, c.why.isNotBlank())
        }
    }

    @Test fun `the dialog names each person, and their name opens their link`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Credits.show(activity)
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val texts = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView) texts += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(dialog.window!!.decorView)

        for (c in Credits.PEOPLE) {
            val name = texts.firstOrNull { it.text.toString().startsWith(c.name) }
            assertNotNull("no row for ${c.name}", name)
            assertTrue(texts.any { it.text.toString() == c.why })
            name!!.performClick()
            val opened = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertEquals(c.url, opened.dataString)
        }
    }
}
