package dev.kb896.aiusage.rear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.AccountStatus
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.Health
import dev.kb896.aiusage.data.PanelConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The play/pause glyph has to follow the player whichever pages the panel has.
 *
 * It did not without a readout. The glyph's verdict was taken in the readout's media row,
 * so with no account connected — no readout page at all — nothing ever took it, and the
 * media page's button kept whatever it showed when the track arrived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayGlyphTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun panel(state: DashboardState) = RearDialView(context).apply {
        config = PanelConfig.DEFAULT.copy(showMedia = true)
        this.state = state
        measure(
            View.MeasureSpec.makeMeasureSpec(Panel.WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(Panel.HEIGHT, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, Panel.WIDTH, Panel.HEIGHT)
    }

    private fun RearDialView.frame() =
        draw(Canvas(Bitmap.createBitmap(Panel.WIDTH, Panel.HEIGHT, Bitmap.Config.ARGB_8888)))

    /** Where the glyph is headed: 1 is the pause bars (playing), 0 the play triangle. */
    private fun RearDialView.glyph(): Float =
        RearDialView::class.java.getDeclaredField("playTo")
            .apply { isAccessible = true }.getFloat(this)

    private fun RearDialView.track(playing: Boolean) = showMedia(
        active = true, title = "Windowlicker", artist = "Aphex Twin",
        playing = playing, durationMs = 367_000L,
    )

    private fun pauseReaches(view: RearDialView) {
        view.track(playing = true)
        view.frame()
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_000))
        view.frame()
        assertEquals(1f, view.glyph())

        view.track(playing = false)
        view.frame()
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_000))
        view.frame()
        assertEquals(0f, view.glyph())
    }

    @Test fun `with a readout, a pause reaches the glyph`() {
        val connected = AccountStatus(Account.PERSONAL, Health.OK, null, connected = true)
        pauseReaches(panel(DashboardState(System.currentTimeMillis(), listOf(connected))))
    }

    @Test fun `with no account and so no readout, a pause still reaches the glyph`() {
        // Signed out, as the app reports it — not EMPTY, which the panel reads as "not
        // known yet" and still gives a readout.
        val out = Account.entries.map { AccountStatus(it, Health.AUTH, null, connected = false) }
        pauseReaches(panel(DashboardState(System.currentTimeMillis(), out)))
    }
}
