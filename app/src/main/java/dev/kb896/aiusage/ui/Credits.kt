package dev.kb896.aiusage.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout

/**
 * The people who helped, behind the button at the bottom of the settings screen.
 *
 * A list rather than one line, because it is meant to grow: each entry is a name, where
 * the name leads, and what they did. Their name is the button — it opens their page.
 */
object Credits {

    data class Credit(val name: String, val url: String, val why: String)

    val PEOPLE = listOf(
        Credit(
            name = "u/Over_Squash3318",
            url = "https://www.reddit.com/user/Over_Squash3318/",
            why = "Got the panel working on the Xiaomi 17 Pro Max: the logs from their " +
                "phone showed why it kept closing there.",
        ),
    )

    fun show(context: Context) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 4), dp(context, 20), 0)
        }
        for (person in PEOPLE) {
            SettingsRows.navigation(box, person.name) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(person.url)))
            }
            SettingsRows.note(box, person.why)
        }
        Modal.show(Modal.builder(context)
            .setTitle("Credits")
            .setView(box)
            .setPositiveButton("Close", null))
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
