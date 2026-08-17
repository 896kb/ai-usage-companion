package dev.kb896.aiusage.service

import android.app.Notification
import android.graphics.drawable.Icon
import android.os.Bundle
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.AccountStatus
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.Health
import dev.kb896.aiusage.data.MascotMood
import dev.kb896.aiusage.data.UsageWindow
import dev.kb896.aiusage.data.WindowKind
import dev.kb896.aiusage.rear.MascotRenderer
import org.json.JSONObject

/**
 * Puts the usage into Xiaomi's HyperIsland — the pill around the front camera — so the
 * numbers are readable without turning the phone over.
 *
 * Attached to the ongoing foreground-service notification rather than posted as a second
 * one. HyperOS builds the island FROM a notification, and this app already has to keep a
 * permanent one alive for its service; a second permanent entry in the shade to say the
 * same thing would be a tax on every pull-down for no extra information.
 *
 * The mechanism is `miui.focus.param`, an undocumented JSON blob HyperOS reads off the
 * notification's extras. It is proprietary and version-specific, so everything here is
 * best-effort: if HyperOS rejects the payload, or the phone is not a HyperOS phone at
 * all, the notification still renders as an ordinary one and nothing else is affected.
 * The structure mirrors the one proven on this device in ~/labs/another project, extended with
 * pictures.
 *
 * `org.json` is deliberate here, against this codebase's rule of kotlinx.serialization:
 * that rule exists so the JVM tests can parse, and this is a write-only blob for a
 * proprietary consumer, with no schema worth modelling and nothing to round-trip.
 */
internal object IslandNotification {

    /** Names the pictures are referenced by inside the JSON. */
    private const val PIC_MASCOT = "mascot"

    /** Bitmap edge, in px. The pill scales it down; islands are small. */
    private const val PIC_PX = 128

    /** One account's headline reading. */
    private data class Line(val status: AccountStatus, val window: UsageWindow)

    /**
     * Both accounts, in a fixed order, skipping any that has no numbers yet.
     *
     * The 5-hour window specifically, not the worst window: it is the one that moves on
     * the timescale a glance is useful over, and the one the panel's bars and mascots
     * already speak for. Falling back to the busiest window keeps an account visible if
     * the API ever stops reporting a session.
     *
     * Order is [Account.entries] — personal, then work — and is deliberately FIXED rather
     * than sorted by usage. The labels are abbreviated to fit, so position is doing real
     * work; a list that reorders itself when one account overtakes the other would make
     * every glance a re-read.
     */
    private fun lines(state: DashboardState): List<Line> = Account.entries.mapNotNull { acc ->
        val status = state.statuses.firstOrNull { it.account == acc } ?: return@mapNotNull null
        val windows = status.snapshot?.windows.orEmpty()
        val window = windows.firstOrNull { it.kind == WindowKind.SESSION }
            ?: windows.maxByOrNull { it.utilization }
            ?: return@mapNotNull null
        Line(status, window)
    }

    private fun percent(w: UsageWindow) = (w.utilization * 100f).toInt()

    /**
     * "PERSONAL 100% · WORK 42%", with any account that cannot vouch for its numbers
     * carrying why.
     *
     * Stale numbers are labelled because an island is glanceable by design, which makes
     * it the easiest place to mislead: a percentage with no qualifier reads as current no
     * matter how many hours old it is.
     */
    private fun title(lines: List<Line>) = lines.joinToString("  ·  ") {
        val health = if (it.status.health == Health.OK) "" else " (${it.status.health.wire})"
        "${it.status.account.label} ${percent(it.window)}%$health"
    }

    private fun content(lines: List<Line>): String {
        val label = lines.map { it.window.label }.distinct().singleOrNull() ?: "5H"
        val resets = lines.joinToString(" / ") { ResetClock.short(it.window.resetsAt) ?: "—" }
        return "$label · resets in $resets"
    }

    /**
     * Decorates [builder] with the island payload and the matching shade content.
     *
     * Returns [builder] untouched when there is nothing to say yet — before the first
     * poll lands, an island reading "0%" would be a claim, not a placeholder.
     */
    fun decorate(builder: Notification.Builder, state: DashboardState): Notification.Builder {
        val lines = lines(state)
        if (lines.isEmpty()) return builder

        val title = title(lines)
        val content = content(lines)

        // ALWAYS the plain standing mascot, never the mood.
        //
        // The moods are built to be read at panel size, in colour, moving. The island
        // gives them none of that: it tints the picture to a flat white silhouette a few
        // millimetres across, where the dead pose's crossed-out eyes turn into something
        // that looks like a crying face and the confetti into unexplained specks. The
        // state is in the numbers beside it; the picture's whole job here is to say which
        // app is talking.
        val mascot = runCatching { MascotRenderer.bitmap(MascotMood.STAND, PIC_PX) }.getOrNull()

        val extras = Bundle().apply {
            putString("miui.focus.param", param(title, content, lines))
            if (mascot != null) {
                putBundle(
                    "miui.focus.pics",
                    Bundle().apply { putParcelable(PIC_MASCOT, Icon.createWithBitmap(mascot)) },
                )
            }
        }

        return builder
            .setContentTitle(title)
            .setContentText(content)
            .addExtras(extras)
    }

    /**
     * The island payload.
     *
     * Two areas, and they are not two sizes of the same thing:
     *
     *  * `smallIslandArea` is the collapsed pill, a few characters wide beside the
     *    camera. Both percentages and nothing else — with the labels dropped, since the
     *    order is fixed and there is no room to name them.
     *  * `bigIslandArea` is what a tap expands to, and can afford the full sentence.
     */
    private fun param(title: String, content: String, lines: List<Line>): String {
        val pic = JSONObject().put("type", 1).put("pic", PIC_MASCOT)

        val small = JSONObject()
            .put("picInfo", pic)
            .put(
                "textInfo",
                JSONObject()
                    .put("title", lines.joinToString(" · ") { "${percent(it.window)}%" })
                    // The nearest reset of the two: the pill has room for one, and the
                    // one worth having is the one about to happen.
                    .put(
                        "content",
                        lines.mapNotNull { it.window.resetsAt }.minOrNull()
                            ?.let { ResetClock.short(it) }.orEmpty(),
                    ),
            )

        val big = JSONObject()
            .put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", pic))
            .put(
                "imageTextInfoRight",
                JSONObject().put("type", 1).put(
                    "textInfo",
                    JSONObject()
                        .put("title", title)
                        .put("content", content)
                        .put("showHighlightColor", false),
                ),
            )

        val island = JSONObject()
            .put("islandProperty", 1)
            .put("islandPriority", 2)
            .put("smallIslandArea", small)
            .put("bigIslandArea", big)

        val v2 = JSONObject()
            .put("protocol", 3)
            .put("business", "claude_usage")
            .put("ticker", content)
            // Never float this in over whatever is on screen. The status is ambient — it
            // is why the island exists — and a limit crossing already interrupts through
            // LimitNotifier, which is the surface that has earned the right to.
            .put("enableFloat", false)
            .put("islandFirstFloat", false)
            .put("updatable", true)
            .put("param_island", island)

        return JSONObject().put("param_v2", v2).toString()
    }
}
