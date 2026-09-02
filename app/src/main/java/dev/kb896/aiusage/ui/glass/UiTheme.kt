package dev.kb896.aiusage.ui.glass

import android.content.Context
import dev.kb896.aiusage.data.ConfigStore

/** The front screen's theme, read the way UiType reads the face: from the store. */
object UiTheme {
    fun isGlass(context: Context): Boolean = ConfigStore(context).load().theme.isGlass
}
