package dev.kb896.aiusage.ui.liquid

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop

/**
 * What the bar's glass sees: the settings screen as it is drawn — the wallpaper on
 * [host] and the [content] views over it — not a Compose layer, since this screen is
 * Views. Drawn where it sits in the window, behind whichever element asks.
 *
 * Bump [changed] whenever what is behind the bar moves (a scroll, a rebuild): the read
 * of [tick] in the draw is what makes Compose draw the glass again.
 */
class ViewBackdrop(private val host: View, private val content: View) : Backdrop {
    private val tick = mutableIntStateOf(0)
    private val loc = IntArray(2)

    fun changed() { tick.intValue++ }

    override val isCoordinatesDependent: Boolean get() = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        tick.intValue
        val at = coordinates?.positionInWindow() ?: Offset.Zero
        host.getLocationInWindow(loc)
        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            canvas.save()
            canvas.translate(loc[0] - at.x, loc[1] - at.y)
            host.background?.draw(canvas)
            canvas.translate(content.left.toFloat(), content.top.toFloat())
            content.draw(canvas)
            canvas.restore()
        }
    }
}

/** One tab: its glyph and its label. */
data class SettingsTab(val icon: Int, val label: String)

/**
 * The settings' bottom bar, laid out as the original's shell lays out its own: the
 * liquid tabs at [SCALE] of the width, 10dp above the bottom, each tab a glyph over a
 * label in the text colour — the chosen one is seen in the accent through the thumb.
 */
@Composable
fun SettingsTabBar(
    tabs: List<SettingsTab>,
    selected: () -> Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    text: Color,
    accent: Color,
    surface: Color,
    face: FontFamily,
) {
    // Room above the bar for the held thumb, which grows past it to 78/56.
    Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.BottomCenter) {
        LiquidBottomTabs(
            selectedTabIndex = selected,
            onTabSelected = onSelect,
            backdrop = backdrop,
            tabsCount = tabs.size,
            accentColor = accent,
            surfaceColor = surface,
            scale = SCALE,
            modifier = Modifier
                .padding(bottom = 10.dp)
                .fillMaxWidth(if (tabs.size > 5) SCALE + 0.16f else SCALE), // six tabs need the room: "Always-on" must not clip
        ) {
            tabs.forEachIndexed { i, tab ->
                LiquidBottomTab(onClick = { onSelect(i) }) {
                    Image(
                        painterResource(tab.icon), contentDescription = null,
                        colorFilter = ColorFilter.tint(text),
                        modifier = Modifier.size((22f * SCALE).dp),
                    )
                    BasicText(
                        tab.label,
                        style = TextStyle(
                            color = text,
                            fontSize = (12f * SCALE).sp,
                            lineHeight = (14f * SCALE).sp,
                            fontFamily = face,
                        ),
                    )
                }
            }
        }
    }
}

/** One knob for the whole bar: geometry, type, icons and lens bands scale together. */
const val SCALE = 0.7f
