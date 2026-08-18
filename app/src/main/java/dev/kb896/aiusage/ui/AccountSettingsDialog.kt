package dev.kb896.aiusage.ui

import android.app.AlertDialog
import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.MascotMood
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.RingMetric
import dev.kb896.aiusage.data.RingWindow
import dev.kb896.aiusage.rear.MascotRenderer

/**
 * Everything about ONE account, with a preview of just that account.
 *
 * A sub-screen rather than another section on the main one. Two accounts already made
 * that screen a long scroll, and the panel is meant to grow past two — an eighth account
 * should be an eighth row on the main screen, not eight more screens' worth of scrolling
 * before you reach the artwork settings.
 *
 * The preview is pinned at the top and shows this account's ring and rows, cropped out of
 * a real panel, so a colour can be judged where it will actually be seen.
 */
object AccountSettingsDialog {

    fun show(
        context: Context,
        index: Int,
        state: DashboardState,
        onChange: (PanelConfig) -> Unit,
    ) {
        val account = Account.entries.getOrNull(index) ?: return
        val store = ConfigStore(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Panel.Palette.BG)
        }

        val preview = AccountPreviewView(context).apply {
            this.account = account
            this.state = state
            this.config = store.load()
        }
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12))
        }
        root.addView(ScrollView(context).apply { addView(rows) })

        // Rebuilt after every edit, because a change to the label renames the rows below
        // it and a change to a ring renames its own. Reconciling in place would be more
        // code than rebuilding a list this short.
        fun build() {
            rows.removeAllViews()
            val config = store.load()
            val style = config.styleFor(index)

            fun write(edit: (PanelConfig) -> PanelConfig) {
                val next = edit(store.load())
                store.save(next)
                preview.config = next
                onChange(next)
            }

            SettingsRows.textField(rows, "NAME", style.label ?: account.label) { typed ->
                write { it.copy(accounts = it.withStyle(index) { s ->
                    s.copy(label = typed.ifBlank { null }) }) }
            }

            SettingsRows.colorGrid(rows, listOf(
                SettingsRows.Swatch("ACCENT", style.accent ?: account.accent) { c ->
                    write { it.copy(accounts = it.withStyle(index) { s -> s.copy(accent = c) }) }
                },
                SettingsRows.Swatch("MASCOT", style.mascotBody ?: MascotRenderer.BODY_GREEN) { c ->
                    write { it.copy(accounts = it.withStyle(index) { s -> s.copy(mascotBody = c) }) }
                },
                SettingsRows.Swatch("KIT", style.mascotAccent
                    ?: if (index == 1) MascotRenderer.ACCENT_WORK else MascotRenderer.ACCENT_ORANGE) { c ->
                    write { it.copy(accounts = it.withStyle(index) { s -> s.copy(mascotAccent = c) }) }
                },
            ))

            // The rings pointed at THIS account. A ring belongs to whichever account it
            // reports on, not to the slot it occupies, so this list is a filter rather
            // than an index — and an account with no ring simply shows no ring settings.
            val windowNames = listOf("5-hour", "7-day all", "7-day scoped")
            val metricNames = listOf("Time to reset", "Utilisation")
            config.rings.forEachIndexed { ringIndex, ring ->
                if (ring.account != index) return@forEachIndexed
                SettingsRows.choice(rows, "RING WINDOW", windowNames,
                    RingWindow.entries.indexOf(ring.window)) { pos ->
                    write { it.copy(rings = it.withRing(ringIndex) { r ->
                        r.copy(window = RingWindow.entries[pos]) }) }
                }
                SettingsRows.choice(rows, "RING SHOWS", metricNames,
                    RingMetric.entries.indexOf(ring.metric)) { pos ->
                    write { it.copy(rings = it.withRing(ringIndex) { r ->
                        r.copy(metric = RingMetric.entries[pos]) }) }
                }
            }
        }
        build()

        AlertDialog.Builder(context)
            .setView(root)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
