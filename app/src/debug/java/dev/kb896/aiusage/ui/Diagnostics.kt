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
import dev.kb896.aiusage.data.CallLog
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
    private var calls: TextView? = null

    /**
     * @param onReset run after a reset, to rebuild the settings rows and repaint the
     *   preview — the stored values changed underneath them and every seeded control is
     *   now showing something that is no longer true.
     */
    fun install(slot: ViewGroup, onReset: () -> Unit = {}) {
        LayoutInflater.from(slot.context).inflate(R.layout.debug_panel, slot, true)
        preview = slot.findViewById(R.id.preview_text)
        calls = slot.findViewById(R.id.calls_text)
        // The debug panel styles its own views: MainActivity cannot, because these ids
        // do not exist in a release build.
        slot.findViewById<TextView>(R.id.preview_label).setTextColor(Panel.Palette.DIM)
        slot.findViewById<TextView>(R.id.calls_heading).setTextColor(Panel.Palette.DIM)
        calls?.setTextColor(Panel.Palette.FG)
        preview?.setTextColor(Panel.Palette.FG)
        slot.findViewById<View>(R.id.rule_3).setBackgroundColor(Panel.Palette.RULE)
        // Same typeface as everything else. The panel is drawn in Departure Mono and a
        // diagnostics block in the system sans would read as a different application.
        // The only way back to the built-in mark, and deliberately debug-only: the mark
        // is a third party's, shipped for fidelity rather than as a choice on offer, so
        // "use the ACRNM mark" is not a button to put in front of a user.
        slot.findViewById<android.widget.Button>(R.id.debug_reset_art).setOnClickListener {
            val store = ConfigStore(slot.context)
            ArtImport.clear(slot.context)
            store.save(store.load().copy(art = dev.kb896.aiusage.data.ArtConfig()))
            onReset()
        }

        // Open, like every other card: the label sits at its top and there is nothing to
        // expand. And in the front's face, not the old monospaced one.
        for (id in intArrayOf(R.id.debug_heading, R.id.preview_label, R.id.preview_text,
                R.id.mascot_heading, R.id.calls_heading, R.id.calls_text)) {
            UiType.dress(slot.findViewById<TextView>(id))
        }
        // The card's title, as every other card's: see CardTitle.
        dev.kb896.aiusage.ui.glass.CardTitle.style(slot.findViewById(R.id.debug_heading))
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
        calls?.text = renderCallLog()
    }

    /**
     * The last few Anthropic requests, newest first.
     *
     * The panel can show that an account is unhealthy but not why or when, and until
     * this was wired up the only answer was logcat over adb — which is no use to
     * somebody holding the phone. The renderer already existed in MainActivity and was
     * never called from anywhere: dead since whatever used to append it to the state
     * dump stopped doing so.
     *
     * Debug-only, and it belongs here rather than in the release UI for the same reason
     * the payload dump does. It records outcomes, never a token and never a body — see
     * CallLog — but "which requests failed and when" is still operator detail, not
     * something to put in front of everyone.
     */
    private fun renderCallLog(): String {
        val recent = CallLog.recent()
        if (recent.isEmpty()) return "(none yet)"
        val now = System.currentTimeMillis()
        return buildString {
            for (e in recent) {
                // Age rather than a wall-clock time: the useful question here is "how
                // long since", and a timestamp makes the reader do that subtraction.
                val agoSec = ((now - e.atMillis) / 1000).coerceAtLeast(0)
                val ago = if (agoSec >= 60) "${agoSec / 60}m" else "${agoSec}s"
                append(ago.padStart(4)).append("  ")
                    .append(e.account.padEnd(9)).append(e.outcome).append('\n')
            }
        }.trimEnd()
    }
}
