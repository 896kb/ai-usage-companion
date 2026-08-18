package dev.kb896.aiusage.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.ArtImport
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.StatePayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Developer diagnostics: the payload the panel receives, and every mascot state animating
 * at once.
 *
 * This is the DEBUG implementation. `src/release` holds a no-op with the same signature,
 * so a release build contains neither these views nor MascotStripView nor this JSON
 * dump — they are absent, not hidden. Hiding was tried and is worse: a hidden
 * MascotStripView is still attached, and it posts a redraw every 40ms for as long as it
 * is, so a release build would animate five mascots nobody can see, forever.
 */
object Diagnostics {

    private val pretty = Json { prettyPrint = true }
    private var preview: TextView? = null

    /**
     * @param onReset run after a reset, to rebuild the settings rows and repaint the
     *   preview — the stored values changed underneath them and every seeded control is
     *   now showing something that is no longer true.
     */
    fun install(slot: ViewGroup, onReset: () -> Unit = {}) {
        LayoutInflater.from(slot.context).inflate(R.layout.debug_panel, slot, true)
        preview = slot.findViewById(R.id.preview_text)
        // The debug panel styles its own views: MainActivity cannot, because these ids
        // do not exist in a release build.
        slot.findViewById<TextView>(R.id.preview_label).setTextColor(Panel.Palette.DIM)
        preview?.setTextColor(Panel.Palette.FG)
        slot.findViewById<View>(R.id.rule_3).setBackgroundColor(Panel.Palette.RULE)
        // Same typeface as everything else. The panel is drawn in Departure Mono and a
        // diagnostics block in the system sans would read as a different application.
        // Reset lives in DEBUG only. Wiping every setting is a development convenience —
        // getting back to a known state to check a default still looks right — and a
        // one-tap way for a user to lose an afternoon's configuration.
        slot.findViewById<android.widget.Button>(R.id.debug_reset_all).setOnClickListener {
            ConfigStore(slot.context).save(PanelConfig.DEFAULT)
            ArtImport.clear(slot.context)
            onReset()
        }
        // The only way back to the built-in mark, and deliberately debug-only: the mark
        // is a third party's, shipped for fidelity rather than as a choice on offer, so
        // "use the ACRNM mark" is not a button to put in front of a user.
        slot.findViewById<android.widget.Button>(R.id.debug_reset_art).setOnClickListener {
            val store = ConfigStore(slot.context)
            ArtImport.clear(slot.context)
            store.save(store.load().copy(art = dev.kb896.aiusage.data.ArtConfig()))
            onReset()
        }

        val heading = slot.findViewById<TextView>(R.id.debug_heading)
        val body = slot.findViewById<View>(R.id.debug_body)
        heading.setOnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            heading.text = if (show) "DEBUG  ▾" else "DEBUG  ▸"
        }
        heading.setTextColor(Panel.Palette.WORK)

        val mono = slot.context.resources.getFont(R.font.departure_mono)
        for (id in intArrayOf(R.id.debug_heading, R.id.preview_label, R.id.preview_text,
                R.id.mascot_heading)) {
            slot.findViewById<TextView>(id).typeface = mono
        }
    }

    /** Renders the payload exactly as the panel receives it, config included. */
    fun render(state: DashboardState, config: PanelConfig) {
        val view = preview ?: return
        val raw = StatePayload.encode(state, config)
        view.text = try {
            pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw))
        } catch (_: Exception) {
            raw
        }
    }
}
