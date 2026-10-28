package dev.kb896.aiusage.ui

import android.content.Context
import android.content.Intent
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
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** The launcher query runs off the main thread; the dialog opens when it is back. */
    fun show(activity: androidx.activity.ComponentActivity, pinned: List<String>, onChange: (List<String>) -> Unit) {
        activity.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { launchable(activity) }
            var current = pinned
            val checked = apps.map { it.pkg in pinned }.toBooleanArray()
            Modal.show(
                Modal.builder(activity)
                    .setTitle("Apps in the portal")
                    .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, which, _ ->
                        current = PortalApps.toggled(current, apps[which].pkg)
                    }
                    .setPositiveButton("Done") { _, _ -> onChange(current) }
                    .setNegativeButton("Cancel", null),
            )
        }
    }
}
