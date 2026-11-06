package dev.kb896.aiusage.ui

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout

/** Which rows of [total] come next, [size] at a time. Pure. */
class Paged(private val total: Int, private val size: Int = LazyRows.PAGE) {
    private var loaded = 0

    val hasMore: Boolean get() = loaded < total

    /** One row taken back (it moved to a place past what is loaded): the next page starts a row earlier. */
    fun unload() {
        if (loaded > 0) loaded--
    }

    fun next(): IntRange? {
        if (!hasMore) return null
        val from = loaded
        loaded = minOf(total, loaded + size)
        return from until loaded
    }
}

/**
 * A long list in a sheet, ten rows at a time: the next page as the scroll nears the end of what
 * is there, and at once while what is there does not yet fill the sheet. Building a row is the
 * cost in both of its lists: a whole font opened for its typeface, a glass switch per app.
 *
 * THE ROWS NOT LOADED YET TAKE UP THEIR ROOM. A fling works out how far it may go when it starts,
 * and a list growing under it stopped at the old end and sprang back, page after page (seen
 * 2026-11-01). So a filler under the rows holds the height of those still to come, from the rows
 * loaded so far, and the list is its full length from the first page.
 */
object LazyRows {
    const val PAGE = 10
    private const val ROW_IN_MS = 240L
    private const val ROW_STAGGER_MS = 35L

    /** Whether the loaded rows end within [margin] of the bottom of the view. */
    fun nearEnd(contentHeight: Int, viewHeight: Int, scrollY: Int, margin: Int): Boolean =
        contentHeight - viewHeight - scrollY < margin

    /** The room for [remaining] rows, at the average of the [loaded] rows' [loadedHeight]. */
    fun fillerHeight(remaining: Int, loaded: Int, loadedHeight: Int): Int =
        if (loaded <= 0) 0 else (remaining.toLong() * loadedHeight / loaded).toInt()

    /**
     * Loads [paged] above [filler], the last child of the list inside [scroll]. [load] builds one
     * page and calls its second argument once its rows are in, on the main thread, so a page is
     * never asked for twice. Call it outside a layout pass (see [more]'s post).
     */
    fun attach(scroll: ScrollView, filler: View, paged: Paged, total: Int, load: (IntRange, () -> Unit) -> Unit) {
        var busy = false
        var loaded = 0
        var rowsTop = -1
        fun more() {
            if (busy) return
            // Half a sheet ahead, so a steady scroll doesn't reach the end before the rows do.
            // The rows' end, not the content's: that includes the room kept for the rest.
            if (!nearEnd(filler.top, scroll.height, scroll.scrollY, scroll.height / 2)) return
            val page = paged.next() ?: return
            busy = true
            load(page) {
                busy = false
                loaded = page.last + 1
                // Measured only once the new rows are laid out; they may not fill the sheet yet.
                filler.doOnNextLayout {
                    // After the pass, not in it: a view still reads as layout-requested until
                    // its listeners have run, and a size set here belongs to the next one.
                    filler.post {
                        filler.layoutParams = filler.layoutParams.apply {
                            height = fillerHeight(total - loaded, loaded, filler.top - rowsTop)
                        }
                        more()
                    }
                }
            }
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> more() }
        filler.doOnLayout {
            rowsTop = filler.top
            more()
        }
    }

    /** A row arriving: from the left as it fades up, a beat behind the one above it in its page. */
    fun arrive(row: View, indexInPage: Int) {
        row.alpha = 0f
        row.translationX = -28 * row.resources.displayMetrics.density
        row.animate().alpha(1f).translationX(0f)
            .setStartDelay(ROW_STAGGER_MS * indexInPage)
            .setDuration(ROW_IN_MS)
            .setInterpolator(DecelerateInterpolator(2f))
    }
}
