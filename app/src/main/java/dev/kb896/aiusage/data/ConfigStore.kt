package dev.kb896.aiusage.data

import android.content.Context

/**
 * Persists [PanelConfig] as one JSON blob.
 *
 * One blob rather than a preference per field, matching UsageHistory's reasoning: the
 * config is written whole on every edit, and a growing key set would be worse to migrate
 * and clean up than to re-serialise.
 *
 * Deliberately holds no logic. Everything that could be wrong lives in PanelConfig's
 * codec, which is pure and JVM-testable; this project has no Robolectric, so anything
 * needing a Context is untested by construction and must therefore be trivial enough not
 * to need testing.
 */
class ConfigStore(private val context: Context) {

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): PanelConfig = PanelConfig.decode(prefs.getString(KEY, null))

    fun save(config: PanelConfig) =
        prefs.edit().putString(KEY, PanelConfig.encode(config)).apply()

    private companion object {
        const val PREFS = "panel_config"
        const val KEY = "config"
    }
}
