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

            // Who this is. Enough to tell two accounts apart and no more — a name, the
            // organisation, the plan. It is read from the profile response the app
            // already fetches for the plan tag, and it lives only here: identity never
            // enters the payload, because that is what the outward-facing panel draws.
            val (holder, email, org) = ProfileStore(context).identity(account)
            val tier = ProfileStore(context).tier(account)
            val who = listOfNotNull(holder ?: email, org, tier).joinToString(" · ")
            if (who.isNotBlank()) {
                // Hidden until the device credential is confirmed, the way a banking app
                // hides a balance. The panel never carries identity at all — it faces
                // outward — and this screen only carries it to tell two accounts apart,
                // but a phone gets handed across a desk unlocked. The screen being open
                // is not evidence that the person looking at it is the owner.
                val line = android.widget.TextView(context).apply {
                    text = if (Unlock.isUnlocked()) who else "•••  tap to reveal"
                    textSize = 12f
                    setTextColor(if (Unlock.isUnlocked()) Panel.Palette.FG else Panel.Palette.DIM)
                    typeface = context.resources.getFont(R.font.departure_mono)
                    setPadding(0, dp(context, 2), 0, dp(context, 2))
                }
                line.setOnClickListener {
                    if (Unlock.isUnlocked()) return@setOnClickListener
                    val activity = context as? Activity ?: return@setOnClickListener
                    Unlock.require(activity, "To show this account's details") { ok ->
                        if (ok) {
                            line.text = who
                            line.setTextColor(Panel.Palette.FG)
                        }
                    }
                }
                rows.addView(line)
            }

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
                    android.app.AlertDialog.Builder(context)
                        .setMessage("Forget this account's credential?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Sign out") { _, _ ->
                            dev.kb896.aiusage.service.Graph.of(context).store.clear(account)
                            build()
                        }
                        .show()
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
            val metricNames = listOf("Time to reset", "Utilisation")
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

        // Inset below the status bar. The sheet covers the whole host, including the
        // strip the system draws the clock and battery into, so its first row — BACK —
        // landed underneath them. Taken from the window rather than hard-coded: the
        // status bar is a different height on a device with a cutout, and the main
        // screen only avoids this by having a 24dp padding that happens to be enough.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sheet) { view, insets ->
            val top = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }

        sheet.post {
            sheet.translationX = sheet.width.toFloat()
            sheet.animate().translationX(0f).setDuration(SLIDE_MS)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        // Leaving the screen re-locks. The unlock is for looking at one account now, not
        // for the rest of the session.
        fun dismiss() {
            Unlock.lock()
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
        android.app.AlertDialog.Builder(context)
            .setTitle("Connect this account")
            .setItems(arrayOf("Sign in with a browser", "Import a token")) { _, which ->
                if (which == 0) {
                    (context as? MainActivity)?.signInFor(account) { failure ->
                        android.widget.Toast.makeText(context,
                            failure ?: "Signed in", android.widget.Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                } else {
                    promptForToken(context, account, onDone)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        box.addView(paste)
        box.addView(android.widget.TextView(context).apply {
            text = TokenStatus.EXAMPLE
            textSize = 10f
            setTextColor(Panel.Palette.DIM)
            typeface = context.resources.getFont(R.font.departure_mono)
            setPadding(0, dp(context, 8), 0, 0)
        })

        android.app.AlertDialog.Builder(context)
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
            }
            .show()
    }

    /** Matches the panel's own 1.8s easing family, shortened: a screen transition that
     *  took as long as a bar's would feel broken. */
    private const val SLIDE_MS = 220L

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
