package dev.kb896.aiusage.ui.glass

import android.content.Context
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig

/**
 * The front screen's theme, read the way UiType reads the face: from the store.
 *
 * Cached on the stored blob rather than re-decoded, because every twin asks this in its
 * constructor: building the settings screen parsed the whole config JSON several dozen
 * times, under BOTH themes, on every resume, to read one enum out of it. SharedPreferences
 * already holds the blob in memory and hands back the same String instance, so the common
 * answer costs an identity comparison; a save changes the string and the next read decodes.
 */
object UiTheme {
    @Volatile private var raw: String? = null
    @Volatile private var cached: Boolean? = null

    fun isGlass(context: Context): Boolean {
        val now = ConfigStore(context).raw()
        val hit = cached
        if (hit != null && (now === raw || now == raw)) return hit
        val fresh = PanelConfig.decode(now).theme.isGlass
        raw = now
        cached = fresh
        return fresh
    }
}
