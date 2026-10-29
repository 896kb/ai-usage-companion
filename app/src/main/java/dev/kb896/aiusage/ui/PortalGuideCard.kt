package dev.kb896.aiusage.ui

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R
import dev.kb896.aiusage.service.PortalTileService
import dev.kb896.aiusage.ui.glass.GlassButton

/**
 * The Portal tab's guide, as views: the card at the top of the tab with its three steps
 * (PortalGuide says which are done), and the "How to use the portal" sheet the row at the
 * bottom opens once the card is down. The tile's add request lives here too.
 *
 * A step is a button while there is something to tap — the picker, the add request — and
 * reads as the setup checklist's do: a tick and the done colour once it is done, never a
 * box to tick by hand. The gestures step cannot be done from the front, so it is the
 * sketch itself, and ticks the first time the sheet opens on the back.
 */
object PortalGuideCard {

    /** What each touch zone does, in the sketch's order — under it in the card and in the sheet. */
    val GESTURES = listOf(
        "Swipe up from the bottom: the portal. Once more, with an app open there: its DPI slider.",
        "Swipe in from the right edge: Back, inside the app.",
        "Pull down from the top, right of the cameras: the control centre.",
        "Double tap: the back sleeps — not on the media buttons, nor in the control centre.",
    )

    /** The tile's name in the add request, as the manifest gives it. */
    private const val TILE_LABEL = "Send to back"

    /**
     * The card's contents into [into] (emptied first): the title, the three steps, a way to
     * dismiss it. [onPin] opens the picker, [onTile] asks for the tile, [onDismiss] puts the
     * card down for good.
     */
    fun fill(
        into: LinearLayout,
        state: PortalGuide.State,
        onPin: () -> Unit,
        onTile: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val context = into.context
        into.removeAllViews()
        into.addView(TextView(context).apply {
            text = "Get started with the portal"
            dev.kb896.aiusage.ui.glass.CardTitle.style(this)
        })

        step(into, 1, "Pin apps", state.pinned, onPin)
        if (!state.pinned) SettingsRows.note(into, "The apps the portal opens on the back. Pick them here, or under “Apps in the portal” below.")

        step(into, 2, "Add the “$TILE_LABEL” tile", state.tile, onTile)
        if (!state.tile) SettingsRows.note(into, "In Quick Settings, it sends the app on this screen to the back.")

        // Nothing to tap: the sketch is the step.
        into.addView(TextView(context).apply {
            text = "${mark(3, state.gestures)}Learn the gestures"
            textSize = 14f
            setTextColor(if (state.gestures) Panel.Palette.WORK else Panel.Palette.FG)
            typeface = UiType.of(context)
            setPadding(dp(context, 4), 0, 0, 0)
        }, rowParams(context, 14))
        sketch(into)
        if (!state.gestures) SettingsRows.note(into, "Ticks itself the first time you open the portal on the back.")

        into.addView(GlassButton(context).apply {
            text = "Dismiss"
            textSize = 13f
            setTextColor(Panel.Palette.DIM)
            typeface = UiType.of(context)
            setOnClickListener { onDismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 10)
            gravity = Gravity.END
        })
    }

    /**
     * The gesture sketch on its own, in a dialog — for the row at the bottom of the tab once
     * the card is down. [onTile], given while the tile is not in the panel yet, adds a button
     * for it: a card dismissed early would otherwise leave no way to ask.
     */
    fun showHelp(activity: Activity, onTile: (() -> Unit)?) {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 4))
        }
        sketch(column)
        val builder = Modal.builder(activity)
            .setTitle("How to use the portal")
            .setView(ScrollView(activity).apply { addView(column) })
            .setPositiveButton("Done", null)
        if (onTile != null) builder.setNeutralButton("Add the tile") { _, _ -> onTile() }
        Modal.show(builder)
    }

    /**
     * Asks the system to put the "Send to back" tile in Quick Settings — its own dialog, no
     * trip to the shade's editor (API 33; minSdk is past it). [onAnswer] gets
     * PortalGuide.tileAnswer's reading of the result, on the main thread; null too when the
     * request could not be made at all.
     */
    fun requestTile(activity: Activity, onAnswer: (Boolean?) -> Unit) {
        val bar = activity.getSystemService(StatusBarManager::class.java)
        if (bar == null) { onAnswer(null); return }
        try {
            bar.requestAddTileService(
                ComponentName(activity, PortalTileService::class.java),
                TILE_LABEL,
                Icon.createWithResource(activity, R.drawable.ic_tile_send_to_back),
                activity.mainExecutor,
            ) { result ->
                Log.i(TAG, "add tile: result $result")
                onAnswer(PortalGuide.tileAnswer(result))
            }
        } catch (e: Exception) {
            Log.w(TAG, "add tile: could not ask: ${e.message}")
            onAnswer(null)
        }
    }

    /** The sketch, then what each zone does. */
    private fun sketch(into: LinearLayout) {
        val context = into.context
        into.addView(GestureSketchView(context), rowParams(context, 10))
        GESTURES.forEach { SettingsRows.note(into, "·  $it") }
    }

    /** A step's button: its number while to do, a tick once done (and then nothing to press). */
    private fun step(into: LinearLayout, n: Int, title: String, done: Boolean, onClick: () -> Unit) {
        val context = into.context
        into.addView(GlassButton(context).apply {
            text = if (done) "${mark(n, true)}$title" else "${mark(n, false)}$title  ›"
            textSize = 14f
            isEnabled = !done
            setTextColor(if (done) Panel.Palette.WORK else Panel.Palette.FG)
            typeface = UiType.of(context)
            setOnClickListener { onClick() }
        }, rowParams(context, 8))
    }

    private fun mark(n: Int, done: Boolean) = if (done) "✓  " else "$n  "

    private fun rowParams(context: android.content.Context, topDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(context, topDp) }

    private fun dp(context: android.content.Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()

    private const val TAG = "PortalGuide"
}
