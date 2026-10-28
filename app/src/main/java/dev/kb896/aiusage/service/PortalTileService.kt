package dev.kb896.aiusage.service

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

    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; subtitle = null; updateTile() }
    }

    override fun onClick() {
        qsTile?.apply { state = Tile.STATE_UNAVAILABLE; subtitle = "Sending…"; updateTile() }
        worker.execute {
            val pkg = try {
                Graph.of(applicationContext).bridge.sendFocusedToBack()
            } catch (_: Exception) {
                ""
            }
            // qsTile is null once the tile stops listening (shade closed); then there is
            // nothing to update and onStartListening resets it next time.
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                subtitle = if (pkg.isEmpty()) "Nothing to send" else "On the back"
                updateTile()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
