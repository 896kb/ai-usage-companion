package dev.kb896.aiusage.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.view.ViewGroup
import dev.kb896.aiusage.R
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.auth.TokenStatus
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.ProfileStore
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.MascotMood
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.RingMetric
import dev.kb896.aiusage.data.RingWindow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dev.kb896.aiusage.rear.MascotRenderer

/**
 * Everything about ONE account, with a preview of just that account.
 *
 * A sub-screen rather than another section on the main one. Two accounts already made
 * that screen a long scroll, and the panel is meant to grow past two — an eighth account
 * should be an eighth row on the main screen, not eight more screens' worth of scrolling
 * before you reach the artwork settings.
 *
 * The preview is pinned at the top and shows this account's ring and rows, cropped out of
 * a real panel, so a colour can be judged where it will actually be seen.
 */
object AccountSettingsSheet {

    /**
     * Slides in over the settings rather than opening a dialog.
     *
     * A dialog floats a second card over a screen that is already a card, and dismisses
     * by tapping outside — which, on a screen made of colour swatches, is a tap away from
     * losing your place by accident. Sliding in from the right says "this is a level
     * down, and back returns you", which is what it is.
     *
     * @param host the activity's root, which this covers and then uncovers
     */
    /** Receivers registered by the open sheet, unregistered when it closes. */
    private val sheetReceivers = mutableListOf<android.content.BroadcastReceiver>()

    /** Collectors running for the open sheet, cancelled when it closes. */
    private val sheetJobs = mutableListOf<kotlinx.coroutines.Job>()

    fun show(
        context: Context,
        host: ViewGroup,
        index: Int,
        state: DashboardState,
        onChange: (PanelConfig) -> Unit,
    ) {
        val account = Account.entries.getOrNull(index) ?: return
        val store = ConfigStore(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Panel.Palette.BG)
        }

        val preview = AccountPreviewView(context).apply {
            this.account = account
            this.state = state
            this.config = store.load()
        }
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The preview follows the polls, exactly as the whole-panel preview on the screen
        // behind it does. It was handed a DashboardState once, at open, and then showed
        // it for as long as the sheet stayed up — so a sign-in landed, the panel on the
        // back filled in, and this preview went on showing the empty bars the account had
        // before it was connected. Nothing new is fetched for this: it is the same flow
        // the rest of the app already listens to.
        //
        // Only the preview's state. Rebuilding the rows on a poll would throw away the
        // scroll position and whatever control was mid-edit, and nothing in them depends
        // on a reading — connecting and disconnecting call build() themselves.
        (context as? androidx.activity.ComponentActivity)?.let { activity ->
            sheetJobs += activity.lifecycleScope.launch {
                dev.kb896.aiusage.service.Graph.of(context).repository.state.collect { fresh ->
                    preview.state = fresh
                }
            }
        }

        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12))
        }
        // Weighted, so it takes the space left after the preview and the back row and
        // scrolls inside it. Without the weight a ScrollView wraps its content, grows
        // past the screen, and never scrolls — the rows below the fold are unreachable
        // and it looks like a bug because it is one.
        root.addView(ScrollView(context).apply { addView(rows) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Rebuilt after every edit, because a change to the label renames the rows below
        // it and a change to a ring renames its own. Reconciling in place would be more
        // code than rebuilding a list this short.
        fun build() {
            rows.removeAllViews()
            val config = store.load()
            val style = config.styleFor(index)

            fun write(edit: (PanelConfig) -> PanelConfig) {
                val next = edit(store.load())
                store.save(next)
                preview.config = next
                onChange(next)
            }

            // ---- connection ----------------------------------------------------
            //
            // One control at a time, for the state the account is actually in. Both
            // routes and a paste field were on screen permanently, including for an
            // account already connected, where all three were noise around the one thing
            // a signed-in user might want: to sign out.
            val record = dev.kb896.aiusage.service.Graph.of(context).store.load(account)

            rows.addView(android.widget.TextView(context).apply {
                text = TokenStatus.describe(record)
                textSize = 12f
                setTextColor(
                    if (TokenStatus.healthy(record)) Panel.Palette.WORK else Panel.Palette.CRIT)
                typeface = context.resources.getFont(R.font.departure_mono)
                setPadding(0, dp(context, 4), 0, dp(context, 4))
            })

            if (record != null) {
                SettingsRows.navigation(rows, "SIGN OUT") {
                    // Confirmed, because it is not undoable by tapping again: signing back
                    // in means a browser round trip, or finding the credential JSON a
                    // second time.
                    Modal.show(
                        Modal.builder(context)
                            .setTitle("Sign out")
                            .setMessage("Forget this account's credential?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Sign out") { _, _ ->
                                dev.kb896.aiusage.service.Graph.of(context).store.clear(account)
                                build()
                            },
                        danger = true,
                    )
                }
            } else {
                SettingsRows.navigation(rows, "CONNECT THIS ACCOUNT") {
                    chooseConnection(context, account) { build() }
                }
            }

            SettingsRows.textField(rows, "NAME", style.label ?: account.label) { typed ->
                write { it.copy(accounts = it.withStyle(index) { s ->
                    s.copy(label = typed.ifBlank { null }) }) }
            }

            // The two clips that carry accent-coloured hardware — the flag's pole, and
            // the headband and dumbbells — sit directly above the swatches that colour
            // them, animating, so a colour is judged on the artwork it will appear on
            // rather than on a square.
            val mascots = MascotPairView(context).apply {
                bodyColor = style.mascotBody ?: MascotRenderer.BODY_GREEN
                accentColor = style.mascotAccent
                    ?: if (index == 1) MascotRenderer.ACCENT_WORK else MascotRenderer.ACCENT_ORANGE
            }
            rows.addView(mascots, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            SettingsRows.colorGrid(rows, listOf(
                SettingsRows.Swatch("ACCENT", style.accent ?: account.accent) { c ->
                    write { it.copy(accounts = it.withStyle(index) { s -> s.copy(accent = c) }) }
                },
                SettingsRows.Swatch("MASCOT", style.mascotBody ?: MascotRenderer.BODY_GREEN) { c ->
                    // Updated in place rather than by rebuilding: a rebuild would restart
                    // both clips from frame zero on every colour change, so the artwork
                    // would twitch each time the picker was used.
                    mascots.bodyColor = c
                    write { it.copy(accounts = it.withStyle(index) { s -> s.copy(mascotBody = c) }) }
                },
                SettingsRows.Swatch("KIT", style.mascotAccent
                    ?: if (index == 1) MascotRenderer.ACCENT_WORK else MascotRenderer.ACCENT_ORANGE) { c ->
                    mascots.accentColor = c
                    write { it.copy(accounts = it.withStyle(index) { s -> s.copy(mascotAccent = c) }) }
                },
            ))

            SettingsRows.toggle(rows, "Show the mascot", style.showMascot) { on ->
                write { it.copy(accounts = it.withStyle(index) { s -> s.copy(showMascot = on) }) }
            }

            // Which rows this account shows. Per account because the accounts are not
            // alike: a scoped weekly limit matters on the account that uses that model
            // and is noise on the other.
            val allKinds = listOf("session" to "5-hour", "weekly_all" to "7-day all",
                "weekly_scoped" to "7-day scoped")
            val shown = config.barsFor(index)
            allKinds.forEach { (wire, name) ->
                val isOn = wire in shown
                SettingsRows.toggle(rows, "Show the $name bar", isOn) { on ->
                    // The last bar cannot be switched off. An account with no bars is a
                    // heading and a mascot standing on nothing, which on the panel is
                    // indistinguishable from an account whose data failed to load.
                    if (!on && shown.size <= 1) {
                        android.widget.Toast.makeText(context,
                            "An account needs at least one bar", android.widget.Toast.LENGTH_SHORT)
                            .show()
                        build()
                        return@toggle
                    }
                    write { cfg ->
                        val next = allKinds.map { it.first }
                            .filter { if (it == wire) on else it in cfg.barsFor(index) }
                        cfg.copy(accounts = cfg.withStyle(index) { s -> s.copy(barOrder = next) })
                    }
                    build()
                }
            }

            // The rings pointed at THIS account. A ring belongs to whichever account it
            // reports on, not to the slot it occupies, so this list is a filter rather
            // than an index — and an account with no ring simply shows no ring settings.
            val windowNames = listOf("5-hour", "7-day all", "7-day scoped")
            // Positional against RingMetric.entries, so this list and that enum have
            // to stay in the same order.
            val metricNames = listOf("Time to reset", "Utilisation", "None")
            // The ring in this account's position. Ring N belongs to the Nth account in
            // display order, so the account's place in that order is which ring is its.
            val position = config.orderedAccounts(Account.entries.size).indexOf(index)
            config.rings.forEachIndexed { ringIndex, ring ->
                if (ringIndex != position) return@forEachIndexed
                SettingsRows.choice(rows, "RING WINDOW", windowNames,
                    RingWindow.entries.indexOf(ring.window)) { pos ->
                    write { it.copy(rings = it.withRing(ringIndex) { r ->
                        r.copy(window = RingWindow.entries[pos]) }) }
                }
                SettingsRows.choice(rows, "RING SHOWS", metricNames,
                    RingMetric.entries.indexOf(ring.metric)) { pos ->
                    write { it.copy(rings = it.withRing(ringIndex) { r ->
                        r.copy(metric = RingMetric.entries[pos]) }) }
                }
            }

            // ---- who this is ----------------------------------------------------
            //
            // LAST, after everything that is used regularly. Recognising an account is
            // something you do once, when connecting it or when you have forgotten which
            // is which; the ring settings are what you came here to change. Putting
            // identity above them made the common task scroll past the rare one.
            //
            // In labelled rows. It was a single dim line squeezed above
            // the controls, where the one thing it exists for — recognising which
            // account you are editing — was the hardest thing on the screen to read.
            //
            // Masked until the device credential is confirmed. The panel never carries
            // identity because it faces outward; this screen carries it because it is in
            // the user's hand — but a phone gets handed across a desk unlocked, and a
            // screen being open is not evidence that the person holding it is the owner.
            val (holder, email, org) = ProfileStore(context).identity(account)
            val tier = ProfileStore(context).tier(account)
            val facts = listOf(
                "NAME" to holder,
                "EMAIL" to email,
                "ORGANISATION" to org,
                "PLAN" to tier,
            ).filter { it.second != null }

            if (facts.isNotEmpty()) {
                rows.addView(android.widget.TextView(context).apply {
                    text = "ACCOUNT"
                    textSize = 11f
                    setTextColor(Panel.Palette.DIM)
                    typeface = context.resources.getFont(R.font.departure_mono)
                    setPadding(0, dp(context, 18), 0, dp(context, 2))
                })

                val values = mutableListOf<android.widget.TextView>()
                for ((label, value) in facts) {
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    row.addView(android.widget.TextView(context).apply {
                        text = label
                        textSize = 12f
                        setTextColor(Panel.Palette.DIM)
                        typeface = context.resources.getFont(R.font.departure_mono)
                        layoutParams = LinearLayout.LayoutParams(dp(context, 108),
                            ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                    val shown = android.widget.TextView(context).apply {
                        // The plan is a bracket, not an identity — it is on the panel
                        // already, so masking it here would be theatre.
                        text = if (label == "PLAN" || Unlock.isUnlocked()) value else MASK
                        textSize = 12f
                        setTextColor(Panel.Palette.FG)
                        typeface = context.resources.getFont(R.font.departure_mono)
                        tag = value
                    }
                    if (label != "PLAN") values += shown
                    row.addView(shown)
                    rows.addView(row, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(context, 4) })
                }

                if (values.isNotEmpty()) {
                    // One control that toggles, not two states of one screen. Hiding must
                    // be as reachable as revealing: someone who reveals a name in front of
                    // a colleague needs to put it away without leaving the screen.
                    fun applyMask(masked: Boolean) {
                        values.forEach { it.text = if (masked) MASK else it.tag as? String }
                    }
                    lateinit var toggle: android.widget.Button

                    // The timer is posted to the view, so it dies with the view. A
                    // Handler kept elsewhere would outlive a closed sheet and remask a
                    // screen that no longer exists.
                    val hideNow = Runnable {
                        Unlock.lock()
                        applyMask(true)
                        toggle.text = "REVEAL  ›"
                    }

                    toggle = SettingsRows.navigationButton(rows,
                        if (Unlock.isUnlocked()) "HIDE" else "REVEAL") {
                        if (Unlock.isUnlocked()) {
                            rows.removeCallbacks(hideNow)
                            hideNow.run()
                        } else {
                            val activity = context as? Activity ?: return@navigationButton
                            Unlock.require(activity, "To show this account's details") { ok ->
                                if (ok) {
                                    applyMask(false)
                                    toggle.text = "HIDE  ›"
                                    // Auto-hide, for the case none of the other triggers
                                    // catch: no lock, no hide, no navigation — a phone
                                    // simply put down with the details on screen.
                                    rows.removeCallbacks(hideNow)
                                    rows.postDelayed(hideNow, Unlock.WINDOW_MS)
                                }
                            }
                        }
                    }

                    // Already unlocked when the sheet was rebuilt — a colour edit
                    // rebuilds these rows — so the countdown continues from what is left
                    // of the window rather than starting again.
                    if (Unlock.isUnlocked()) {
                        rows.postDelayed(hideNow, Unlock.remainingMs())
                    }

                    // The screen going dark with a name on it, waiting to be read by
                    // whoever wakes the phone, is the case this gate exists for.
                    val watcher = Unlock.observeScreenOff(context) {
                        applyMask(true)
                        toggle.text = "REVEAL  ›"
                    }
                    sheetReceivers += watcher
                }
            }

        }
        build()

        val sheet = FrameLayout(context).apply {
            setBackgroundColor(Panel.Palette.BG)
            // Swallows touches, so the settings underneath cannot be tapped through the
            // sheet while it is open.
            isClickable = true
            addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // A TextView, not a Button: the platform's button background is a filled slab
        // that reads as the loudest thing on a screen whose whole job is showing colours.
        // Back is a way out, not the point of the screen.
        val back = android.widget.TextView(context).apply {
            text = "‹  BACK"
            textSize = 14f
            setTextColor(Panel.Palette.DIM)
            typeface = context.resources.getFont(R.font.departure_mono)
            setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 10))
            isClickable = true
        }
        root.addView(back, 0, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        host.addView(sheet, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.TOP))

        // Insets are handled once for the whole activity — see applySystemBarInsets.

        sheet.post {
            sheet.translationX = sheet.width.toFloat()
            sheet.animate().translationX(0f).setDuration(SLIDE_MS)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        // Leaving the screen re-locks. The unlock is for looking at one account now, not
        // for the rest of the session.
        fun dismiss() {
            Unlock.lock()
            // Leaking a receiver per sheet open would eventually crash the process.
            sheetReceivers.forEach { runCatching { context.unregisterReceiver(it) } }
            sheetReceivers.clear()
            // Same for the collector: the activity's scope outlives the sheet, so it
            // would otherwise keep pushing states into a preview that has been removed —
            // once per sheet ever opened.
            sheetJobs.forEach { it.cancel() }
            sheetJobs.clear()
            sheet.animate().translationX(sheet.width.toFloat()).setDuration(SLIDE_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { host.removeView(sheet) }
                .start()
        }
        back.setOnClickListener { dismiss() }
        // The system back gesture closes the sheet before it closes the screen.
        (context as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher
            ?.addCallback(context, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    remove()
                    dismiss()
                }
            })
    }

    /**
     * Ask which route, then take it.
     *
     * Asked rather than shown side by side, because it is a one-off decision made once
     * per account and then never again — and the two routes are not comparable options
     * so much as answers to "can this account do device login". Keeping both on screen
     * afterwards, with a paste field between them, made the common case look unfinished.
     */
    private fun chooseConnection(context: Context, account: Account, onDone: () -> Unit) {
        // Two of the app's own buttons rather than setItems. A dialog's list rows are
        // built by an adapter as they appear, so they arrive in the platform's typeface
        // and colours and there is no reliable moment at which to repaint them — and
        // these two rows are the first thing a new user sees.
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 4), dp(context, 20), 0)
        }
        val dialog = Modal.builder(context)
            .setTitle("Connect this account")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .create()
        SettingsRows.navigation(box, "SIGN IN WITH A BROWSER") {
            dialog.dismiss()
            (context as? MainActivity)?.signInFor(account) { failure ->
                android.widget.Toast.makeText(context,
                    failure ?: "Signed in", android.widget.Toast.LENGTH_SHORT).show()
                onDone()
            }
        }
        SettingsRows.navigation(box, "IMPORT A TOKEN") {
            dialog.dismiss()
            promptForToken(context, account, onDone)
        }
        Modal.show(dialog)
    }

    /**
     * The paste field, with the shape it expects shown beside it.
     *
     * Shown rather than described: "paste your credentials" is not an instruction anyone
     * can follow, because this is a specific object out of a keychain entry whose field
     * names nobody guesses.
     */
    private fun promptForToken(context: Context, account: Account, onDone: () -> Unit) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), 0)
        }
        val paste = android.widget.EditText(context).apply {
            hint = "Paste the credential JSON"
            textSize = 12f
            setTextColor(Panel.Palette.FG)
            setHintTextColor(Panel.Palette.DIM)
            typeface = context.resources.getFont(R.font.departure_mono)
            minLines = 3
        }
        // A credential already waiting in our own storage, put there by the export
        // helper over adb. Saying so is the difference between a dialog that looks like
        // it needs something and one that is ready — and the alternative was the user
        // hunting for text to paste that macOS cannot hand them anyway, because the two
        // clipboards are not shared.
        val staged = java.io.File(context.filesDir, "work-token.json")
        if (staged.canRead()) {
            box.addView(android.widget.TextView(context).apply {
                text = "A credential is staged on this device. Import takes it — " +
                    "leave the field empty."
                textSize = 11f
                setTextColor(Panel.Palette.WORK)
                typeface = context.resources.getFont(R.font.departure_mono)
                setPadding(0, 0, 0, dp(context, 8))
            })
        }
        box.addView(paste)
        box.addView(android.widget.TextView(context).apply {
            text = TokenStatus.EXAMPLE
            textSize = 10f
            setTextColor(Panel.Palette.DIM)
            typeface = context.resources.getFont(R.font.departure_mono)
            setPadding(0, dp(context, 8), 0, 0)
        })

        Modal.show(Modal.builder(context)
            .setTitle("Import a token")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import") { _, _ ->
                val failure = (context as? MainActivity)
                    ?.importTokenFor(account, paste.text?.toString().orEmpty())
                // Cleared whatever happens: a credential left in a field stays in the
                // view hierarchy, recoverable from a screenshot and restored on rotation.
                paste.setText("")
                android.widget.Toast.makeText(context,
                    failure ?: "Token imported", android.widget.Toast.LENGTH_SHORT).show()
                onDone()
            })
    }

    /** Long enough to read as redacted rather than as a value that happens to be short. */
    private const val MASK = "••••••••"

    /** Matches the panel's own 1.8s easing family, shortened: a screen transition that
     *  took as long as a bar's would feel broken. */
    private const val SLIDE_MS = 220L

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
