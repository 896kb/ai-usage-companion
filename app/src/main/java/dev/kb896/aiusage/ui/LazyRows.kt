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
 */
object LazyRows {
    const val PAGE = 10
    private const val ROW_IN_MS = 240L
    private const val ROW_STAGGER_MS = 35L

    fun nearEnd(contentHeight: Int, viewHeight: Int, scrollY: Int, margin: Int): Boolean =
        contentHeight - viewHeight - scrollY < margin

    /**
     * Loads [paged] into [content] inside [scroll]. [load] builds one page and calls its second
     * argument once its rows are in, on the main thread, so a page is never asked for twice.
     */
    fun attach(scroll: ScrollView, content: View, paged: Paged, load: (IntRange, () -> Unit) -> Unit) {
        var busy = false
        fun more() {
            if (busy) return
            // Half a sheet ahead, so a steady scroll doesn't reach the end before the rows do.
            if (!nearEnd(content.height, scroll.height, scroll.scrollY, scroll.height / 2)) return
            val page = paged.next() ?: return
            busy = true
            load(page) {
                busy = false
                // Measured only once the new rows are laid out; they may not fill the sheet yet.
                content.doOnNextLayout { more() }
            }
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> more() }
        scroll.doOnLayout { more() }
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
