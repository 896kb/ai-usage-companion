package dev.kb896.aiusage.service

import android.app.Notification
import android.graphics.drawable.Icon
import android.os.Bundle
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.AccountStatus
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.Health
import dev.kb896.aiusage.data.UsageWindow
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

    /**
     * The window the island speaks for, and what to say about it.
     *
     * @param status the account it belongs to, for the label, the mascot and the accent
     */
    private data class Headline(val status: AccountStatus, val window: UsageWindow)

    /**
     * The most-used window across both accounts — the one that will stop you first.
     *
     * The island holds one line. Averaging the accounts would describe neither, and
     * always showing the personal one would go quiet at exactly the moment the work
     * account was the problem. Highest utilisation is the only choice that cannot hide
     * bad news.
     *
     * Degraded accounts still count. Their numbers are the last good ones, which is what
     * the panel shows too; treating them as absent would make a stale 95% vanish.
     */
    private fun headline(state: DashboardState): Headline? =
        state.statuses
            .flatMap { s -> s.snapshot?.windows.orEmpty().map { Headline(s, it) } }
            .maxByOrNull { it.window.utilization }

    /**
     * Decorates [builder] with the island payload and the matching shade content.
     *
     * Returns [builder] untouched when there is nothing to say yet — before the first
     * poll lands, an island reading "0%" would be a claim, not a placeholder.
     */
    fun decorate(builder: Notification.Builder, state: DashboardState): Notification.Builder {
        val head = headline(state) ?: return builder

        val percent = (head.window.utilization * 100f).toInt()
        val account = head.status.account
        val title = "${account.label} · ${head.window.label}"
        val reset = ResetClock.phrase(head.window.resetsAt)
        val content = "$percent% · $reset"
        // Stale numbers are labelled as such. An island is glanceable by design, which
        // means it is also the easiest place to mislead: a number with no qualifier reads
        // as current no matter how old it is.
        val suffix = if (head.status.health == Health.OK) "" else " · ${head.status.health.wire}"

        val mascot = runCatching {
            MascotRenderer.bitmap(
                head.status.mascot, PIC_PX,
                accent = if (account == dev.kb896.aiusage.data.Account.WORK) {
                    MascotRenderer.ACCENT_WORK
                } else {
                    MascotRenderer.ACCENT_ORANGE
                },
            )
        }.getOrNull()

        val extras = Bundle().apply {
            putString("miui.focus.param", param(title, content + suffix, percent, head))
            if (mascot != null) {
                putBundle(
                    "miui.focus.pics",
                    Bundle().apply { putParcelable(PIC_MASCOT, Icon.createWithBitmap(mascot)) },
                )
            }
        }

        return builder
            .setContentTitle(title)
            .setContentText(content + suffix)
            .addExtras(extras)
    }

    /**
     * The island payload.
     *
     * Two areas, and they are not two sizes of the same thing:
     *
     *  * `smallIslandArea` is the collapsed pill, a few characters wide beside the
     *    camera. It gets the percentage and the time — the two numbers worth having
     *    without any interaction — and the mascot, which carries the state as a picture
     *    where there is no room for a word for it.
     *  * `bigIslandArea` is what a tap expands to, and can afford the full sentence.
     */
    private fun param(title: String, content: String, percent: Int, head: Headline): String {
        val pic = JSONObject().put("type", 1).put("pic", PIC_MASCOT)

        val small = JSONObject()
            .put("picInfo", pic)
            .put(
                "textInfo",
                JSONObject()
                    .put("title", "$percent%")
                    .put("content", ResetClock.short(head.window.resetsAt).orEmpty()),
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
            .put("highlightColor", hex(head.status.account.accent))
            .put("param_island", island)

        return JSONObject().put("param_v2", v2).toString()
    }

    /** #RRGGBB, which is the only form the island's colour fields accept. */
    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)
}
