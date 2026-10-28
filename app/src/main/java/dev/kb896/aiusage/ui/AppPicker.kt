package dev.kb896.aiusage.ui

import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import dev.kb896.aiusage.data.PortalApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The App Portal's pin list: every app with a launcher icon (this one excepted), by name,
 * the pinned ones ticked. Ticking pins at the end of the list; order is changed with the
 * rows under it.
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
     * The launcher query runs off the main thread; the dialog opens when it is back.
     * A second call while the query is in flight or the dialog is up does nothing.
     * Pinned packages that are not in the launcher list are left as they were.
     */
    fun show(activity: androidx.activity.ComponentActivity, pinned: List<String>, onChange: (List<String>) -> Unit) {
        if (opening) return
        opening = true
        activity.lifecycleScope.launch {
            val apps = try {
                withContext(Dispatchers.IO) { launchable(activity) }
            } catch (e: Throwable) {
                opening = false
                throw e
            }
            var current = pinned
            // The app's own rows, not setMultiChoiceItems: adapter rows come back in the
            // stock typeface (see Modal).
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), 0)
            }
            apps.forEach { app ->
                SettingsRows.toggle(column, app.label, app.pkg in pinned) {
                    current = PortalApps.toggled(current, app.pkg)
                }
            }
            val scroll = object : ScrollView(activity) {
                override fun onMeasure(w: Int, h: Int) {
                    val cap = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
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
        }
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
