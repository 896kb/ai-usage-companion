package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Typeface
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.rear.PanelType

/**
 * The front screen's face, which is the panel's face.
 *
 * THE THEME IS THE WHOLE APP'S, not the rear display's. The settings screen exists to
 * describe the panel, and describing it in a face the panel does not use is the same
 * mistake as a preview drawn in the wrong colours — you are choosing by looking at
 * something that is not the thing.
 *
 * Reads the stored config rather than being handed one, because the callers are a
 * scattered set of view builders that have no config between them and should not grow a
 * parameter each to carry one down.
 */
object UiType {

    /** The front screen's face: GLASS's, always — the front is glass whatever the back is (UiTheme). */
    fun of(context: Context): Typeface =
        PanelType.of(context.resources, dev.kb896.aiusage.data.PanelTheme.GLASS)

    /**
     * The variation axes to set alongside it, or null.
     *
     * Separate from the face for the reason PanelType.variation gives: a variable font is
     * one file with an axis, and asking a Typeface for a weight it has no siblings for
     * returns its default instance — which for Outfit is Thin.
     */
    fun variation(context: Context): String? =
        PanelType.variation(dev.kb896.aiusage.data.PanelTheme.GLASS)

    /** The front's face on [view], with its axes — set, or cleared when the face has none. */
    fun dress(view: android.widget.TextView) {
        PanelType.weigh(view, of(view.context), variation(view.context))
    }
}
