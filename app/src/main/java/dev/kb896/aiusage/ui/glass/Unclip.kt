package dev.kb896.aiusage.ui.glass

import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import dev.kb896.aiusage.ui.PanelPreviewView
import dev.kb896.aiusage.ui.video.VideoCropView

/**
 * Lets glass draw past its own edges.
 *
 * A glass control grows while it is pressed and its rim sits on its very edge, and every
 * layout around it clipped both to the box it was given — a pressed button cut flat, a rim
 * half missing. Everything under [root] stops clipping its children, except what must:
 * scrollers, which would otherwise draw their content over the screen above them, the
 * panel preview, whose rounded corners are a clip, and the video crop frame, whose video
 * runs past the region and would otherwise cover the dialog's title.
 */
object Unclip {
    fun tree(root: View) {
        if (root !is ViewGroup) return
        if (root is PanelPreviewView || root is VideoCropView) return
        if (root !is ScrollView && root !is HorizontalScrollView) {
            root.clipChildren = false
            root.clipToPadding = false
        }
        for (i in 0 until root.childCount) tree(root.getChildAt(i))
    }
}
