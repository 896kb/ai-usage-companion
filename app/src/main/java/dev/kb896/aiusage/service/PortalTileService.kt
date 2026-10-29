package dev.kb896.aiusage.service

import android.util.Log
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.util.concurrent.Executors

/**
 * Send to back: whatever is open on the front screen moves to the rear as the App
 * Portal's guest. The shade is over it when the tile is tapped, but the focused app is
 * still the one underneath, which is the one meant.
 *
 * sendFocusedToBack() blocks (up to ~6 s, it awaits the Shizuku service), so it runs on
 * [worker]; the tile reads UNAVAILABLE meanwhile so a second tap cannot queue another
 * send. The shade is left open afterwards: collapsing it from a TileService would take
 * startActivityAndCollapse, i.e. launching an activity just to close the shade.
 */
class PortalTileService : TileService() {

    private val worker = Executors.newSingleThreadExecutor()

    /** True from the tap until the send has finished, across the shade closing and reopening. */
    @Volatile private var sending = false

    // In the panel or not, kept for the Portal tab's guide (PortalGuide's second step). These
    // run in the app's own process, on its main thread, as the settings screen's writes do.
    override fun onTileAdded() = markAdded(true)
    override fun onTileRemoved() = markAdded(false)

    /** Saved only when it changes: listening happens every time the shade opens. */
    private fun markAdded(added: Boolean) {
        val store = dev.kb896.aiusage.data.ConfigStore(applicationContext)
        val config = store.load()
        if (config.portalTileAdded != added) {
            store.save(dev.kb896.aiusage.ui.PortalGuide.settled(config.copy(portalTileAdded = added)))
        }
    }

    override fun onStartListening() {
        // Listening at all means it is in the panel — added before there was anything to tell.
        markAdded(true)
        qsTile?.apply {
            if (sending) { state = Tile.STATE_UNAVAILABLE; subtitle = "Sending…" }
            else { state = Tile.STATE_INACTIVE; subtitle = null }
            updateTile()
        }
    }

    override fun onClick() {
        if (sending) return
        sending = true
        qsTile?.apply { state = Tile.STATE_UNAVAILABLE; subtitle = "Sending…"; updateTile() }
        try {
            worker.execute {
                try {
                    val pkg = try {
                        Graph.of(applicationContext).bridge.sendFocusedToBack()
                    } catch (e: Exception) {
                        Log.w(TAG, "send to back failed: ${e.message}", e)
                        ""
                    }
                    // qsTile is null once the tile stops listening (shade closed); then there is
                    // nothing to update and onStartListening resets it next time.
                    qsTile?.apply {
                        state = Tile.STATE_INACTIVE
                        subtitle = if (pkg.isEmpty()) "Nothing to send" else "On the back"
                        updateTile()
                    }
                } finally {
                    sending = false
                }
            }
        } catch (e: Exception) {
            // Rejected (the worker is shut down): nothing will clear the flag otherwise.
            sending = false
            Log.w(TAG, "send to back not started: ${e.message}")
        }
    }

    private companion object { const val TAG = "PortalTile" }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
