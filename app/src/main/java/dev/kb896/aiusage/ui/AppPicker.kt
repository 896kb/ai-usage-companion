package dev.kb896.aiusage.ui

import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import dev.kb896.aiusage.data.PortalApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The App Portal's pin list: every app with a launcher icon (this one excepted), by name,
 * the pinned ones ticked. Ticking pins at the end of the list; order is changed with the
 * rows under it. No more than PortalApps.MAX: past that a tick is put back, and the note at
 * the top says why.
 */
object AppPicker {

    data class App(val pkg: String, val label: String)

    fun launchable(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { App(it.activityInfo.packageName, it.activityInfo.applicationInfo.loadLabel(pm).toString()) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private var opening = false

    /**
     * The sheet opens at once, a glass spinner turning while the launcher query runs off the main
     * thread (each label is read from its app, and with a lot of apps that took a while with
     * nothing on screen). Then the spinner fades, and the rows come in ten at a time as the list
     * is scrolled (LazyRows). A second call while the sheet is up does nothing. Pinned packages
     * that are not in the launcher list are left as they were, and so are ones not yet scrolled to.
     */
    fun show(activity: androidx.activity.ComponentActivity, pinned: List<String>, onChange: (List<String>) -> Unit) {
        if (opening) return
        opening = true
        var current = pinned
        // The app's own rows, not setMultiChoiceItems: adapter rows come back in the
        // stock typeface (see Modal).
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), 0)
        }
        val count = SettingsRows.note(column, countNote(current.size))
        val looking = dev.kb896.aiusage.ui.glass.GlassSpinner(activity).also { column.addView(it) }
        // The rows go in here, so the room kept for the rest stays under them (LazyRows).
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(rows)
        val filler = android.view.View(activity)
        column.addView(filler, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        // The sheet's height from the start, so the rows arriving don't stretch it.
        var filling = true
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(w: Int, h: Int) {
                val cap = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, if (filling) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
            }
        }.apply { addView(column) }
        val dialog = Modal.builder(activity)
            .setTitle("Apps in the portal")
            .setView(scroll)
            .setPositiveButton("Done") { _, _ -> onChange(current) }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener { opening = false }
        Modal.show(dialog)

        fun addPage(apps: List<App>, page: IntRange, done: () -> Unit) {
            if (!dialog.isShowing) return
            for (i in page) {
                val app = apps[i]
                val before = rows.childCount
                var setBack: (Boolean) -> Unit = {}
                setBack = SettingsRows.toggle(rows, app.label, app.pkg in current) { on ->
                    val next = PortalApps.pinned(current, app.pkg, on)
                    val refused = on && app.pkg !in next
                    // Full: the tick goes back off (under ORIGINAL that calls here again, unpinning nothing).
                    if (refused) setBack(false)
                    current = next
                    count.text = countNote(current.size, refused)
                }
                for (at in before until rows.childCount) LazyRows.arrive(rows.getChildAt(at), i - page.first)
            }
            done()
        }

        activity.lifecycleScope.launch {
            val apps = runCatching { withContext(Dispatchers.IO) { launchable(activity) } }.getOrElse {
                android.util.Log.w("AppPicker", "launcher query failed: ${it.message}")
                emptyList()
            }
            if (!dialog.isShowing) return@launch
            looking.animate().alpha(0f).setDuration(SPINNER_OUT_MS).withEndAction {
                // Laid out without the spinner first: the rows' height is measured from here.
                filler.doOnNextLayout {
                    val paged = Paged(apps.size)
                    LazyRows.attach(scroll, filler, paged, apps.size) { page, done ->
                        addPage(apps, page) {
                            if (!paged.hasMore) filling = false
                            done()
                        }
                    }
                    if (apps.isEmpty()) filling = false
                }
                column.removeView(looking)
            }
        }
    }

    private const val SPINNER_OUT_MS = 160L

    /** The note over the list: how many are pinned, and — full, a tick turned down — why. */
    private fun countNote(n: Int, refused: Boolean = false): String = when {
        n >= PortalApps.MAX && refused -> "${PortalApps.MAX} pinned, the most there can be. Unpin one to pin another."
        n >= PortalApps.MAX -> "${PortalApps.MAX} pinned, the most there can be."
        else -> "$n of ${PortalApps.MAX} pinned."
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
