package dev.kb896.aiusage.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.view.doOnNextLayout
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.PortalApps
import dev.kb896.aiusage.ui.glass.GlassField
import dev.kb896.aiusage.ui.glass.GlassIconButton
import dev.kb896.aiusage.ui.glass.GlassSpinner
import dev.kb896.aiusage.ui.glass.Ink
import dev.kb896.aiusage.ui.glass.SoftEdgeScrollView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The App Portal's one list of apps: every app with a launcher icon (this one excepted), with a
 * search over it, the pinned ones first in their order on the back and the rest by name. A row
 * pins or unpins with its pin, and a pinned one opens its settings (PortalAppSheet) with its gear
 * and moves up the back's order with its arrow. There were two lists, one to pin in and one for
 * what was pinned. No more than PortalApps.MAX: past that a pin does nothing, and the note says why.
 *
 * What a row does is saved at once, as every other setting is, so the sheet has only Done. A row
 * pinned, unpinned or moved goes to its place there and then, the rest staying as they are. Next
 * to its place, the two rows slide past each other. Further off, it fades out where it was, the
 * rows between close up, and it fades in at the new place. One whose place isn't loaded yet just
 * goes, and comes with its page.
 */
object AppPicker {

    data class App(val pkg: String, val label: String)

    /** What the rows ask of whoever keeps the config. */
    interface Actions {
        fun pinned(): List<String>
        /** False: full, nothing changed. */
        fun pin(pkg: String, on: Boolean): Boolean
        fun moveUp(pkg: String)
        /** [changed] when it was unpinned from there. */
        fun settings(pkg: String, label: String, changed: () -> Unit)
    }

    fun launchable(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { App(it.activityInfo.packageName, it.activityInfo.applicationInfo.loadLabel(pm).toString()) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** The rows for [query]: pinned first in [pinned]'s order, the rest of [apps] as they came. */
    fun arranged(apps: List<App>, pinned: List<String>, query: String): List<App> {
        val q = query.trim()
        val shown = if (q.isEmpty()) apps else apps.filter { it.label.contains(q, ignoreCase = true) }
        val byPkg = shown.associateBy { it.pkg }
        val first = pinned.mapNotNull { byPkg[it] }
        val firstSet = first.toSet()
        return first + shown.filter { it !in firstSet }
    }

    private var opening = false

    /**
     * The sheet opens at once, a glass spinner turning while the launcher query runs off the main
     * thread (each label is read from its app, and with a lot of apps that took a while with
     * nothing on screen). Then the rows come in ten at a time as the list is scrolled (LazyRows),
     * each page's icons read off the main thread first. A second call while the sheet is up does
     * nothing. [onDone] when the sheet goes, however it goes: everything is already saved.
     */
    fun show(activity: androidx.activity.ComponentActivity, actions: Actions, onDone: () -> Unit) {
        if (opening) return
        opening = true
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), 0)
        }
        val count = SettingsRows.note(top, countNote(actions.pinned().size))
        val search = GlassField(activity).apply {
            hint = "Search apps"
            setHintTextColor(Ink.secondary(0f))
            setSingleLine()
            textSize = 15f
            typeface = UiType.of(activity)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        top.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 8)
            bottomMargin = dp(activity, 4)
        })
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), 0, dp(activity, 12), 0)
        }
        val looking = GlassSpinner(activity).also { column.addView(it) }
        // The rows go in here, so the room kept for the rest stays under them (LazyRows).
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(rows)
        val filler = View(activity)
        column.addView(filler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        // The sheet's height from the start, so rows arriving or a search narrowing them don't move it.
        val scroll = object : SoftEdgeScrollView(activity) {
            override fun onMeasure(w: Int, h: Int) {
                val cap = (resources.displayMetrics.heightPixels * 0.45f).toInt()
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY))
            }
        }.apply { addView(column) }
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(scroll)
        }
        val dialog = Modal.builder(activity)
            .setTitle("Apps in the portal")
            .setView(sheet)
            .setPositiveButton("Done", null)
            .create()
        dialog.setOnDismissListener {
            opening = false
            onDone()
        }
        Modal.show(dialog)

        val icons = HashMap<String, Drawable?>()
        // A search replaces the list: anything still arriving for the one before is dropped.
        var generation = 0

        // The list as it stands, its first [Paged]-loaded rows being [rows]' children in order.
        var all = emptyList<App>()
        var list = emptyList<App>()
        var paged = Paged(0)
        var query = ""
        var sliding: ValueAnimator? = null

        /** What a row shows for the order as it now stands: the arrow on a pinned row but the first, with no search. */
        fun dress(line: View) {
            val app = line.tag as? App ?: return
            val pinned = actions.pinned()
            val on = app.pkg in pinned
            line.findViewWithTag<GlassIconButton>(PIN_TAG)?.setImageResource(if (on) R.drawable.ic_pinned else R.drawable.ic_pin)
            line.findViewWithTag<View>(GEAR_TAG)?.visibility = if (on) View.VISIBLE else View.INVISIBLE
            line.findViewWithTag<View>(UP_TAG)?.visibility =
                if (on && query.isBlank() && pinned.first() != app.pkg) View.VISIBLE else View.INVISIBLE
        }

        fun dressAll() {
            for (i in 0 until rows.childCount) dress(rows.getChildAt(i))
        }

        lateinit var row: (App) -> View

        /** A copy of [line] where it is on screen, fading away: the row itself is already elsewhere. */
        fun fadeOut(line: View, app: App) {
            val ghost = row(app)
            ghost.measure(
                View.MeasureSpec.makeMeasureSpec(line.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(line.height, View.MeasureSpec.EXACTLY),
            )
            val y = (line.top + line.translationY).toInt()
            ghost.layout(line.left, y, line.right, y + line.height)
            rows.overlay.add(ghost)
            ghost.animate().alpha(0f).setDuration(MOVE_MS / 2).withEndAction { rows.overlay.remove(ghost) }
        }

        /** [app]'s pin or place changed: to where the sorted list now has it, in view. */
        fun moved(app: App) {
            list = arranged(all, actions.pinned(), query)
            val at = list.indexOf(app)
            val old = (0 until rows.childCount).firstOrNull { rows.getChildAt(it).tag == app }
            dressAll()
            if (old == null || at == old) return
            val line = rows.getChildAt(old)
            // Where each row is on screen now, a move still running included, to slide on from there.
            val was = (0 until rows.childCount).map { rows.getChildAt(it) }.associateWith { it.top + it.translationY }
            val next = kotlin.math.abs(at - old) == 1
            if (!next) fadeOut(line, app)
            sliding?.cancel()
            rows.removeViewAt(old)
            // Past what's loaded (rows now one short): its page brings it.
            if (at > rows.childCount) paged.unload() else rows.addView(line, at)
            rows.doOnPreDraw {
                val from = was.filterKeys { it.parent === rows && (next || it !== line) }
                    .mapValues { (v, y) -> y - v.top }
                from.forEach { (v, dy) -> v.translationY = dy }
                if (next) line.translationZ = 1f
                else {
                    line.translationY = 0f
                    line.alpha = 0f
                    line.animate().alpha(1f).setStartDelay(MOVE_MS / 3).setDuration(MOVE_MS - MOVE_MS / 3)
                }
                sliding = ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = MOVE_MS
                    interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
                    addUpdateListener { a -> from.forEach { (v, dy) -> v.translationY = dy * (a.animatedValue as Float) } }
                    doOnEnd { line.translationZ = 0f }
                    start()
                }
            }
        }

        row = { app ->
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(activity, 56)
                tag = app
            }
            line.addView(ImageView(activity).apply { setImageDrawable(icons[app.pkg]) },
                LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)).apply { marginEnd = dp(activity, 12) })
            line.addView(TextView(activity).apply {
                text = app.label
                textSize = 15f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Ink.primary(0f))
                UiType.dress(this)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fun button(icon: Int, what: String, click: () -> Unit) = GlassIconButton(activity).apply {
                setImageResource(icon)
                contentDescription = what
                minimumWidth = dp(activity, 44)
                minimumHeight = dp(activity, 44)
                setOnClickListener { click() }
            }.also { b ->
                line.addView(b, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)).apply { marginStart = dp(activity, 4) })
            }
            button(R.drawable.ic_arrow_up, "Move ${app.label} up") {
                actions.moveUp(app.pkg)
                moved(app)
            }.apply { tag = UP_TAG }
            button(R.drawable.ic_tab_general, "${app.label} settings") {
                actions.settings(app.pkg, app.label) {
                    count.text = countNote(actions.pinned().size)
                    moved(app)
                }
            }.apply { tag = GEAR_TAG }
            button(R.drawable.ic_pin, "Pin ${app.label}") {
                val on = app.pkg !in actions.pinned()
                val took = actions.pin(app.pkg, on)
                count.text = countNote(actions.pinned().size, on && !took)
                if (took) moved(app)
            }.apply { tag = PIN_TAG }
            dress(line)
            line
        }

        fun fill(apps: List<App>) {
            val mine = ++generation
            all = apps
            query = search.text?.toString().orEmpty()
            list = arranged(apps, actions.pinned(), query)
            sliding?.cancel()
            rows.overlay.clear()
            rows.removeAllViews()
            filler.layoutParams = filler.layoutParams.apply { height = 0 }
            scroll.scrollTo(0, 0)
            if (list.isEmpty()) {
                rows.addView(TextView(activity).apply {
                    text = if (query.isBlank()) "No apps found." else "No app matches “${query.trim()}”."
                    textSize = 14f
                    setTextColor(Ink.secondary(0f))
                    UiType.dress(this)
                    setPadding(0, dp(activity, 16), 0, 0)
                })
                return
            }
            paged = Paged(list.size)
            val pages = paged
            // The first page measured from the top of an empty list (LazyRows).
            filler.doOnNextLayout {
              filler.post {
                if (mine != generation) return@post
                LazyRows.attach(scroll, filler, pages, list.size) load@{ page, done ->
                    // A list a search has since replaced: its loader is still on the scroll, and
                    // the list it counted is gone. Never done, so it asks no more.
                    if (mine != generation) return@load
                    val wanted = page.mapNotNull { list.getOrNull(it) }.filter { it.pkg !in icons }
                    activity.lifecycleScope.launch {
                        // Read off the main thread into a map of their own, kept on it: the
                        // rows read [icons] on the main thread.
                        val read = withContext(Dispatchers.IO) {
                            val pm = activity.packageManager
                            wanted.associate { it.pkg to runCatching { pm.getApplicationIcon(it.pkg) }.getOrNull() }
                        }
                        icons.putAll(read)
                        if (!dialog.isShowing || mine != generation) return@launch
                        // From where the rows end now: a pin while the icons were read moved things.
                        val first = rows.childCount
                        for (n in 0 until page.count()) {
                            val app = list.getOrNull(first + n) ?: break
                            if (app.pkg !in icons) icons[app.pkg] = runCatching { activity.packageManager.getApplicationIcon(app.pkg) }.getOrNull()
                            val v = row(app)
                            rows.addView(v)
                            LazyRows.arrive(v, n)
                        }
                        done()
                    }
                }
              }
            }
            filler.requestLayout()
        }

        activity.lifecycleScope.launch {
            val apps = runCatching { withContext(Dispatchers.IO) { launchable(activity) } }.getOrElse {
                android.util.Log.w("AppPicker", "launcher query failed: ${it.message}")
                emptyList()
            }
            if (!dialog.isShowing) return@launch
            looking.animate().alpha(0f).setDuration(SPINNER_OUT_MS).withEndAction {
                column.removeView(looking)
                fill(apps)
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = fill(apps)
                })
            }
        }
    }

    private const val SPINNER_OUT_MS = 160L
    private const val MOVE_MS = 240L
    private const val UP_TAG = "up"
    private const val GEAR_TAG = "gear"
    private const val PIN_TAG = "pin"

    /** The note over the list: how many are pinned, and, full and a pin turned down, why. */
    private fun countNote(n: Int, refused: Boolean = false): String = when {
        n >= PortalApps.MAX && refused -> "$n pinned, the most there can be. Unpin one to pin another."
        n >= PortalApps.MAX -> "$n pinned, the most there can be."
        else -> "$n pinned, up to ${PortalApps.MAX}."
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
