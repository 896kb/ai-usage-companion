package dev.kb896.aiusage.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import dev.kb896.aiusage.BuildConfig
import dev.kb896.aiusage.rear.Weather
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R
import dev.kb896.aiusage.auth.LoopbackServer
import dev.kb896.aiusage.auth.OAuthClient
import dev.kb896.aiusage.auth.PkceChallenge
import dev.kb896.aiusage.auth.TokenImport
import dev.kb896.aiusage.data.Account
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.ArtConfig
import dev.kb896.aiusage.data.ArtImport
import android.widget.LinearLayout
import dev.kb896.aiusage.data.RingWindow
import dev.kb896.aiusage.data.RingMetric
import dev.kb896.aiusage.rear.MascotRenderer
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.RingLayout
import dev.kb896.aiusage.data.DashboardState
import android.widget.SeekBar
import dev.kb896.aiusage.data.StatePayload
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.kb896.aiusage.rear.RearActivity
import dev.kb896.aiusage.rear.ShizukuBridge
import dev.kb896.aiusage.service.Graph
import androidx.core.app.NotificationManagerCompat
import dev.kb896.aiusage.service.UsagePollService
import dev.kb896.aiusage.service.WeatherPoll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/**
 * The control surface: everything else in the app runs headless (the service,
 * the rear activity in its own process). This is the only screen a human ever
 * looks at, and the only place that starts the poll loop — see onCreate().
 */
class MainActivity : ComponentActivity() {

    private val graph by lazy { Graph.of(this) }

    // Deliberately separate from Graph's internal OAuthClient: Graph exposes only
    // store/repository/bridge (Task 15's frozen surface), and personal sign-in is a
    // UI-only concern — one browser round trip, not something the poll loop needs.
    private val oauthHttp by lazy { OkHttpClient() }
    private val oauthClient by lazy { OAuthClient(oauthHttp) }

    private val prettyJson = Json { prettyPrint = true }
    private lateinit var recoveryNote: TextView
    private lateinit var recoveryScroll: View
    private lateinit var recoveryCommand: EditText
    private lateinit var recoveryDisplayAbsent: TextView
    private lateinit var showButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        applySystemBarInsets()
        applyPalette()
        wireActions()

        // The one call in the whole app that starts the poll loop. Safe to call
        // every time MainActivity is created: startForegroundService() on an
        // already-running service just redelivers onStartCommand, which this
        // service doesn't override, so onCreate()'s listener/loop setup runs
        // exactly once regardless of how many times this fires.
        UsagePollService.start(this)

        lifecycleScope.launch {
        }

        refreshStatus()

        buildSetupRows()
    }

    /**
     * What the camera tip's switch shows. The panel turns the setting off by itself when it
     * shows the tip — saved by the service, behind this screen's back — so while the screen
     * is open it watches the store and redraws the rows when the two disagree. Only for that
     * switch: redrawing on every write would rebuild the rows under someone typing a title.
     */
    private var shownCameraTip: Boolean? = null
    private var stopWatchingConfig: (() -> Unit)? = null

    private fun syncCameraTip() {
        val shown = shownCameraTip ?: return
        if (ConfigStore(this).load().cameraTip != shown) buildSettingsRows()
    }

    override fun onPause() {
        super.onPause()
        stopWatchingConfig?.invoke()
        stopWatchingConfig = null
        // Backgrounding re-locks: the app may be resumed by someone else, and a phone
        // handed over while a screen is open is exactly the case this guards.
        Unlock.lock()
    }

    override fun onResume() {
        super.onResume()
        buildSetupRows()
        // Back from the notification access page, most likely: the hint rows go once it is held.
        val access = Setup.notificationAccess(this)
        if (rowsHadAccess != null && rowsHadAccess != access) buildSettingsRows()
        rowsHadAccess = access
        syncCameraTip()
        stopWatchingConfig = ConfigStore(this).listen { syncCameraTip() }
        // Have the user service bound before any row needs it. Placement used to bind
        // it as a side effect; with placement gated on the onboarding, nothing else does.
        if (graph.bridge.status().granted) graph.bridge.bind()
        // Shizuku's permission grant is asynchronous (a system dialog the user
        // returns from) and requestPermission() below has no result callback wired
        // — see ShizukuBridge's history (Task 3) for why that's deliberate, not an
        // oversight. onResume is what catches the grant once the user comes back.
        refreshStatus()
    }

    private fun bindViews() {
        recoveryNote = findViewById(R.id.recovery_note)
        recoveryScroll = findViewById(R.id.recovery_scroll)
        recoveryCommand = findViewById(R.id.recovery_command)
        recoveryDisplayAbsent = findViewById(R.id.recovery_display_absent)
        showButton = findViewById(R.id.show_button)
        bindPreview()
        bindSettings()
        buildSettingsRows()
        bindArt()
        bindBackdrop()
        bindDiagnostics()
        bindAdvancedToggle()
        bindGeometrySliders()
        bindTabs()
        bindHeader()
        findViewById<Button>(R.id.credits).setOnClickListener { Credits.show(this) }

        // Selectable and copyable (long-press -> Copy), but never editable — this
        // field only ever displays a command MainActivity wrote, never accepts
        // typed input.
        recoveryCommand.keyListener = null
    }

    /**
     * Keep every screen out from under the system bars.
     *
     * Applied ONCE, to the activity's root, rather than per screen. The main screen only
     * cleared the status bar because it happened to have a 24dp padding; the account
     * sheet did not and its BACK row landed underneath the clock, unreachable. Any screen
     * added later would have had to remember, and would not have.
     *
     * The values come from the window: a status bar is a different height on a device
     * with a cutout, and the navigation bar disappears entirely under gesture nav.
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root_host)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun applyPalette() {
        val glass = dev.kb896.aiusage.ui.glass.UiTheme.isGlass(this)
        val host = findViewById<View>(R.id.root_host)
        if (glass) {
            dev.kb896.aiusage.ui.glass.Backdrop.install(host)
            // The status bar's icons dark over a light background, light over a dark one —
            // the silk is lime, and white icons on it were all but gone.
            host.post {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = dev.kb896.aiusage.ui.glass.Backdrop.meanLuminance() > 0.5f
            }
            // The headings are not Glassy, so invalidating the glass does not reach them;
            // they are plain text over the wallpaper and have to re-ink as it moves past.
            dev.kb896.aiusage.ui.glass.Backdrop.track(findViewById(R.id.root_scroll)) { inkHeadings() }
        } else {
            // Switching to ORIGINAL recreates the activity, and the backdrop is an object:
            // without this it would go on holding the dead activity's root view and its
            // window-sized bitmap for as long as the process lives.
            dev.kb896.aiusage.ui.glass.Backdrop.release()
            host.setBackgroundColor(Panel.Palette.BG)
        }
        // THE THEME'S FACE, not the panel's original one. This screen describes the panel,
        // and describing it in a face the panel does not use is the same mistake as a
        // preview drawn in the wrong colours.
        val face = UiType.of(this)
        val axes = UiType.variation(this)

        // Every TextView in the tree, found by walking it rather than by listing them.
        //
        // The list this replaced had to be extended by hand for each new control, and
        // was not — the ring-geometry labels were added later and quietly kept the
        // system sans, in an app whose whole visual identity is one monospaced face.
        // A sweep cannot be forgotten.
        fun applyFont(view: View) {
            when (view) {
                is TextView -> {
                    view.typeface = face
                    // Cleared as well as set, or a view keeps a weight request for a face
                    // that has no axes to answer it.
                    view.fontVariationSettings = axes
                }
                is ViewGroup -> for (i in 0 until view.childCount) applyFont(view.getChildAt(i))
            }
        }
        applyFont(findViewById(R.id.root_host))
        dev.kb896.aiusage.ui.glass.Unclip.tree(findViewById(R.id.settings_layout))

        if (glass) {
            // The hairlines are the ORIGINAL screen's structure; cards are this one's.
            listOf(R.id.rule_1, R.id.rule_2, R.id.rule_settings, R.id.rule_pinned, R.id.rule_advanced)
                .forEach { findViewById<View>(it).visibility = View.GONE }
            // Headings sit over the wallpaper, outside any card, so each takes its own cell.
            host.doOnLayout { inkHeadings() }
            return
        }

        recoveryNote.setTextColor(Panel.Palette.FG)
        recoveryCommand.setTextColor(Panel.Palette.FG)
        recoveryDisplayAbsent.setTextColor(Panel.Palette.FG)

        listOf(R.id.rule_1, R.id.rule_2, R.id.rule_settings, R.id.rule_pinned).forEach {
            findViewById<View>(it).setBackgroundColor(Panel.Palette.RULE)
        }

        // Settings controls, in the same tones as the rest: a heading is dim, a value
        // is foreground, a hint is dim so an empty field reads as "unset" rather than
        // as text someone typed.
        findViewById<TextView>(R.id.settings_heading).setTextColor(Panel.Palette.DIM)
        findViewById<TextView>(R.id.settings_title_label).setTextColor(Panel.Palette.DIM)
        findViewById<EditText>(R.id.settings_title).apply {
            setTextColor(Panel.Palette.FG)
            setHintTextColor(Panel.Palette.DIM)
        }
        findViewById<TextView>(R.id.geo_heading).setTextColor(Panel.Palette.WORK)
        findViewById<TextView>(R.id.geo_hint).setTextColor(Panel.Palette.DIM)
    }

    /**
     * The headings that sit outside any card. Held as a field, not built per call: this
     * now runs on every scroll frame, and a list built there is a per-frame allocation.
     */
    private val headingIds = intArrayOf(R.id.preview_heading, R.id.setup_heading,
        R.id.settings_heading, R.id.settings_art_label, R.id.backdrop_heading,
        R.id.clock_heading, R.id.media_heading, R.id.aod_heading)

    /**
     * The bottom bar: the panel's clock page, its usage page, its media page, and
     * always-on — the order the pages sit in on the back. Each tab shows its own section
     * of settings (the usage tab also holds everything general) and has the preview show
     * that screen. Onboarding and recovery stay above every tab.
     */
    private var tab = TAB_GENERAL

    private fun bindTabs() {
        val bar = findViewById<androidx.compose.ui.platform.ComposeView>(R.id.tab_bar)
        val scroll = findViewById<android.widget.ScrollView>(R.id.root_scroll)
        val host = findViewById<View>(R.id.root_host)
        val backdrop = dev.kb896.aiusage.ui.liquid.ViewBackdrop(host, findViewById(R.id.root_layout))
        // The rows run on under the bar, so the last of them needs room to scroll clear —
        // and the bar's glass is drawn from them, so it is told whenever they move.
        val reach = ((64f * dev.kb896.aiusage.ui.liquid.SCALE + 10f + 12f) * resources.displayMetrics.density).toInt()
        scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, reach)
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> backdrop.changed() }
        tabBackdrop = backdrop
        // The silk moves under it: the bar's glass is drawn again with each of its frames.
        dev.kb896.aiusage.ui.glass.Backdrop.onFrame += { backdrop.changed() }
        val selected = androidx.compose.runtime.mutableIntStateOf(tab)
        val lum = dev.kb896.aiusage.ui.glass.Backdrop.luminanceOf(host)
        val tabs = listOf(
            dev.kb896.aiusage.ui.liquid.SettingsTab(R.drawable.ic_tab_general, "General"),
            dev.kb896.aiusage.ui.liquid.SettingsTab(R.drawable.ic_tab_clock, "Clock"),
            dev.kb896.aiusage.ui.liquid.SettingsTab(R.drawable.ic_tab_usage, "Usage"),
            dev.kb896.aiusage.ui.liquid.SettingsTab(R.drawable.ic_tab_media, "Media"),
            dev.kb896.aiusage.ui.liquid.SettingsTab(R.drawable.ic_tab_aod, "Always-on"),
        )
        bar.setContent {
            dev.kb896.aiusage.ui.liquid.SettingsTabBar(
                tabs = tabs,
                selected = { selected.intValue },
                onSelect = { i ->
                    selected.intValue = i
                    if (i != tab) {
                        tab = i
                        showTab(i)
                        scroll.scrollTo(0, 0)
                        backdrop.changed()
                    }
                },
                backdrop = backdrop,
                text = androidx.compose.ui.graphics.Color(0xFFF2F2F2.toInt()),
                accent = androidx.compose.ui.graphics.Color(dev.kb896.aiusage.ui.glass.Ink.accent(lum)),
                surface = androidx.compose.ui.graphics.Color(0xFF121212.toInt()),
                face = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.outfit)),
            )
        }
        showTab(tab)
    }

    /** The app's icon and name at the top, as the launcher shows them. */
    private fun bindHeader() {
        findViewById<android.widget.ImageView>(R.id.app_icon).setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
        findViewById<TextView>(R.id.app_name).apply {
            text = applicationInfo.loadLabel(packageManager)
            UiType.dress(this)
            fontVariationSettings = "'wght' 600"
        }
    }

    /** The bar's view of the screen, told when the rows under it change. */
    private var tabBackdrop: dev.kb896.aiusage.ui.liquid.ViewBackdrop? = null

    private fun showTab(i: Int) {
        val sections = intArrayOf(R.id.tab_general, R.id.tab_clock, R.id.tab_usage, R.id.tab_media, R.id.tab_aod)
        for ((k, id) in sections.withIndex()) {
            findViewById<View>(id).visibility = if (k == i) View.VISIBLE else View.GONE
        }
        val preview = findViewById<PanelPreviewView>(R.id.panel_preview) ?: return
        when (i) {
            TAB_CLOCK -> preview.show(dev.kb896.aiusage.rear.RearDialView.PAGE_NOTICES, aod = false)
            TAB_MEDIA -> preview.show(dev.kb896.aiusage.rear.RearDialView.PAGE_MEDIA, aod = false)
            TAB_AOD -> preview.show(null, aod = true)
            TAB_USAGE -> preview.show(dev.kb896.aiusage.rear.RearDialView.PAGE_READOUT, aod = false)
            // General is about the whole panel: the back as it is.
            else -> preview.show(null, aod = false)
        }
        inkHeadings()
    }

    private fun inkHeadings() {
        for (id in headingIds) {
            val v = findViewById<TextView>(id)
            val ink = dev.kb896.aiusage.ui.glass.Ink.secondary(
                dev.kb896.aiusage.ui.glass.Backdrop.luminanceOf(v))
            // setTextColor invalidates whether or not the colour moved, and a heading
            // crosses the light/dark line perhaps twice in a whole scroll.
            if (ink != v.currentTextColor) v.setTextColor(ink)
        }
    }

    /**
     * A card with nothing showing is not a card, it is an empty pane of glass on the
     * wallpaper — which is exactly what the recovery and onboarding sections became
     * once their content was hidden. Under ORIGINAL the card is inert and an empty one
     * has no height anyway, so this changes nothing there.
     */
    private fun collapseEmptyCards() {
        for (id in intArrayOf(R.id.card_recovery, R.id.card_setup)) {
            val card = findViewById<ViewGroup>(id)
            val showing = (0 until card.childCount).any { card.getChildAt(it).visibility != View.GONE }
            card.visibility = if (showing) View.VISIBLE else View.GONE
        }
    }

    private fun wireActions() {
        // Renders whatever the repository already holds — no network call. Placing
        // the panel is a display action, not a data action, so it must not spend a
        // request against an endpoint that has already rate-limited one account.
        //
        // It also used to send "{}", which decodes to an empty state: the panel went
        // blank on placement and stayed blank until the next poll, now up to five
        // minutes away.
        showButton.setOnClickListener {
            // Until onboarding is done the toggle is "Setup", and opens it.
            val status = graph.bridge.status()
            if (!Setup.allDone(Setup.steps(this, status.granted, status.running))) {
                showOnboarding()
                return@setOnClickListener
            }
            when (Setup.xiaomiRearAppInstalled(this)) {
                // Xiaomi's app has the back: take it out of the way, and the panel goes up.
                true -> confirmRemoveXiaomiRearApp()
                // Ours has it: bring Xiaomi's back, and the panel comes down.
                false -> setXiaomiRearApp(installed = true)
                // A phone without Xiaomi's app: just the panel, up or down.
                null -> if (panelHidden) {
                    panelHidden = false
                    graph.bridge.show(StatePayload.encode(graph.repository.state.value, ConfigStore(this).load(), WeatherPoll.latest))
                    refreshControl()
                } else {
                    panelHidden = true
                    graph.bridge.hide()
                    refreshControl()
                }
            }
        }
    }

    /**
     * Notification access, asked for where it is needed rather than in onboarding: the
     * panel's notifications and its media controls both read from it, and nothing else
     * does. Switched on without it, the feature says so and offers the page; "Not now"
     * runs [declined], which turns the feature back off.
     */
    private fun askNotificationAccess(feature: String, declined: () -> Unit) {
        if (Setup.notificationAccess(this)) return
        Modal.show(Modal.builder(this)
            .setTitle("Allow notification access?")
            .setMessage(
                "$feature reads from Android's notification access — other apps' " +
                    "notifications and what is playing both come through it. Allow it for " +
                    "this app on the next screen."
            )
            .setPositiveButton("Allow") { _, _ -> openNotificationAccess() }
            .setNegativeButton("Not now") { _, _ -> declined(); buildSettingsRows() })
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Setup.notificationAccessIntent())
        } catch (_: Exception) {
            toast("Could not open that settings page")
        }
    }

    /** Whether notification access was held when the rows were last built; rebuilt when it changes. */
    private var rowsHadAccess: Boolean? = null

    /** On a phone without Xiaomi's rear app, whether the toggle last took the panel down. */
    private var panelHidden = false

    /**
     * The show/hide card's one button. Until onboarding's crucial steps are done it is
     * "Setup", and opens the checklist; after, it says what pressing it does — "Show on
     * the back" while Xiaomi's rear-screen app has the back, "Hide" while the panel does
     * (Setup.shownByXiaomi).
     */
    private fun refreshControl() {
        val status = graph.bridge.status()
        val done = Setup.allDone(Setup.steps(this, status.granted, status.running))
        if (!done) { showButton.text = "Setup"; return }
        val hidden = when (Setup.xiaomiRearAppInstalled(this)) {
            true -> true
            false -> false
            null -> panelHidden
        }
        showButton.text = if (hidden) "Show on the back" else "Hide"
    }

    /**
     * Imports the work credential from a paste. Deliberately NOT from a file on shared
     * storage: /sdcard is readable by any app holding storage permission, so staging a
     * live managed refresh token there would expose it to every app on the device.
     * The paste field is the only import route in this app — no file read, no
     * document picker, no storage permission anywhere in the manifest.
     */
    /**
     * Import a pasted credential for ONE account.
     *
     * Which account was hard-coded — personal signed in, work imported — because those
     * were the author's two accounts. Neither is a property of an account: a managed
     * account cannot do device login and a personal one can, and which is which is the
     * user's to say.
     *
     * @return the failure to show, or null on success
     */
    fun importTokenFor(account: Account, pasted: String): String? {
        // Prefer the file the export script writes into our own private storage — macOS
        // and Android share no clipboard, so "copy on the Mac, paste on the phone" cannot
        // work. Pasted text remains the manual route.
        val staged = java.io.File(filesDir, "work-token.json")
        val source = if (pasted.isNotBlank()) pasted
            else if (staged.canRead()) staged.readText() else ""
        if (source.isBlank()) return "Nothing to import"

        val record = TokenImport.parse(source)
            ?: return "That doesn't look like a Claude Code credential"
        graph.store.save(account, record)
        // Delete the staged file the moment its contents are encrypted in TokenStore —
        // app-private is not a reason to leave a live credential lying in plaintext.
        staged.delete()
        refreshNow()
        return null
    }

    /** Browser sign-in for ONE account. [onDone] reports the outcome for display. */
    fun signInFor(account: Account, onDone: (String?) -> Unit) {
        lifecycleScope.launch {
            try {
                val loopback = LoopbackServer()
                val challenge = PkceChallenge.generate()
                val redirectUri = loopback.redirectUri()
                val authorizeUrl = oauthClient.authorizeUrl(challenge, redirectUri)
                val codeDeferred = async(Dispatchers.IO) { loopback.awaitCode(challenge.state) }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)))
                val code = codeDeferred.await()
                if (code == null) {
                    onDone("Sign-in failed or timed out")
                    return@launch
                }
                val record = withContext(Dispatchers.IO) {
                    oauthClient.exchange(code, challenge.verifier, redirectUri, challenge.state)
                }
                graph.store.save(account, record)
                onDone(null)
                refreshNow()
            } catch (e: OAuthClient.Failure) {
                onDone("Sign-in failed: ${e.message}")
            } catch (e: Exception) {
                onDone("Sign-in failed")
            }
        }
    }

    /**
     * Fetches immediately, bypassing UsageRepository's burst floor. Both callers have
     * just been handed a credential by the user and are showing them the result, which
     * is the one case where waiting out the floor would look like a failed import.
     */
    private fun refreshNow() = lifecycleScope.launch {
        graph.repository.refresh(force = true)
        graph.bridge.update(
            StatePayload.encode(graph.repository.state.value, ConfigStore(this@MainActivity).load(), WeatherPoll.latest))
    }

    /**
     * Live ring tuning. Each slider writes through to GeometryStore and pushes the
     * result to the panel on every move, so the rings track the drag on the rear
     * display in real time — the same nudge mechanism the adb calibration used, minus
     * the adb.
     *
     * Only fromUser events are acted on. setProgress() during setup fires the listener
     * too, and reacting to that would broadcast five times on every open and — worse —
     * write the seeded values back over stored ones.
     */
    /**
     * The advanced section stays COLLAPSED until asked for.
     *
     * These sliders exist for a phone whose rear display is not the one the defaults
     * were measured against — a larger model in the same family, where the panel is a
     * different size and the lenses sit elsewhere. That is a real need, and it is also
     * a need almost nobody has: on the measured device the defaults are already right,
     * and five sliders that can only make a correct layout wrong do not belong in front
     * of someone who just installed the app.
     */
    /**
     * Diagnostics, if this variant has any. The release variant's Diagnostics is a no-op
     * with the same signature, so there is no BuildConfig check here and nothing to
     * accidentally leave switched on.
     */
    /**
     * Panel settings, written through on every keystroke and pushed immediately.
     *
     * No Save button. The panel is a live preview on the back of the phone, and a
     * two-step commit would mean typing, tapping, then turning the phone over — which is
     * how you end up setting a value three times because you cannot see it while you
     * choose it.
     *
     * A BLANK field means "use the default", not "an empty masthead". Storing the empty
     * string would leave the panel with a gap where its title is and no way to tell that
     * from a rendering failure; the hint shows what the default is, so an empty box is
     * self-explanatory rather than broken.
     */
    /**
     * Keeps the preview showing what the panel shows.
     *
     * Fed from the SAME state and the SAME config that go into the payload, so a setting
     * that looks right here is right on the back. Anything that reads its own copy of
     * either would eventually disagree, and a preview that disagrees is worse than none.
     */
    private fun bindPreview() {
        refreshPreview()
        // Follows polls as well as edits: the preview must show the numbers currently on
        // the panel, not the ones that were there when this screen opened.
        lifecycleScope.launch {
            graph.repository.state.collect { refreshPreview() }
        }
        // ...and the things that reach the panel by broadcast rather than by state.
        //
        // The preview draws with the same class as the panel, so it could always have
        // shown these — it simply was never told. Notifications and media go straight to
        // `:rear`, and this process, which is the one with a screen a human looks at,
        // heard none of it. The result was a settings page for two features whose effect
        // was invisible until you turned the phone over.
        ContextCompat.registerReceiver(
            this, previewRelay,
            IntentFilter().apply {
                addAction(RearActivity.ACTION_NOTIFICATION)
                addAction(RearActivity.ACTION_MEDIA)
                addAction(RearActivity.ACTION_MIRROR)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sendBroadcast(
            Intent(RearActivity.ACTION_MEDIA_COMMAND)
                .setPackage(packageName)
                .putExtra(RearActivity.EXTRA_MEDIA_COMMAND, RearActivity.MEDIA_SYNC),
        )
        // And the back's own state — page, always-on, slider — so the preview starts
        // as a mirror rather than on the readout.
        sendBroadcast(Intent(RearActivity.ACTION_MIRROR_SYNC).setPackage(packageName))
    }

    /** Feeds the preview the same broadcasts the panel gets. */
    private val previewRelay = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val preview = findViewById<PanelPreviewView>(R.id.panel_preview) ?: return
            when (intent?.action) {
                RearActivity.ACTION_NOTIFICATION -> preview.showNotification(
                    intent.getStringExtra(RearActivity.EXTRA_NOTIFICATION_APP).orEmpty(),
                    intent.getStringExtra(RearActivity.EXTRA_NOTIFICATION_PKG).orEmpty(),
                    intent.getStringExtra(RearActivity.EXTRA_NOTIFICATION_TITLE).orEmpty(),
                    intent.getStringExtra(RearActivity.EXTRA_NOTIFICATION_TEXT).orEmpty(),
                    intent.getStringExtra(RearActivity.EXTRA_NOTIFICATION_MORE_TITLE)
                        .orEmpty(),
                    intent.getStringExtra(RearActivity.EXTRA_NOTIFICATION_MORE_TEXT)
                        .orEmpty(),
                )
                RearActivity.ACTION_MIRROR -> preview.mirror(
                    dev.kb896.aiusage.rear.RearDialView.Mirror(
                        page = intent.getIntExtra(RearActivity.EXTRA_MIRROR_PAGE, 1),
                        aod = intent.getBooleanExtra(RearActivity.EXTRA_MIRROR_AOD, false),
                        locked = intent.getBooleanExtra(RearActivity.EXTRA_MIRROR_LOCKED, false),
                        ringsClosed = intent.getBooleanExtra(RearActivity.EXTRA_MIRROR_RINGS, false),
                        tableGlow = intent.getBooleanExtra(RearActivity.EXTRA_MIRROR_TABLE, false),
                        corner = intent.getFloatExtra(RearActivity.EXTRA_MIRROR_CORNER, 97f),
                    ),
                    dim = intent.getBooleanExtra(RearActivity.EXTRA_MIRROR_DIM, false),
                )
                RearActivity.ACTION_MEDIA -> preview.showMedia(
                    intent.getBooleanExtra(RearActivity.EXTRA_MEDIA_ACTIVE, false),
                    intent.getStringExtra(RearActivity.EXTRA_MEDIA_TITLE).orEmpty(),
                    intent.getStringExtra(RearActivity.EXTRA_MEDIA_ARTIST).orEmpty(),
                    intent.getBooleanExtra(RearActivity.EXTRA_MEDIA_PLAYING, false),
                    intent.getStringExtra(RearActivity.EXTRA_MEDIA_ART).orEmpty(),
                    intent.getLongExtra(RearActivity.EXTRA_MEDIA_DURATION, 0L),
                    intent.getLongExtra(RearActivity.EXTRA_MEDIA_POSITION, 0L),
                    intent.getLongExtra(RearActivity.EXTRA_MEDIA_POSITION_AT, 0L),
                    intent.getFloatExtra(RearActivity.EXTRA_MEDIA_SPEED, 1f),
                    intent.getStringExtra(RearActivity.EXTRA_MEDIA_LYRICS).orEmpty(),
                    intent.getStringExtra(RearActivity.EXTRA_MEDIA_LYRICS_FOR).orEmpty(),
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(previewRelay) }
        // A recreate() hands the backdrop a new root on the way back in; a finish does
        // not, so the last activity out turns the wallpaper off behind it. isFinishing
        // tells the two apart — releasing on a rotation would just cost a rebuild.
        if (isFinishing) dev.kb896.aiusage.ui.glass.Backdrop.release()
        super.onDestroy()
    }

    private fun refreshPreview() {
        val preview = findViewById<PanelPreviewView>(R.id.panel_preview) ?: return
        preview.config = ConfigStore(this).load()
        preview.state = graph.repository.state.value
        preview.weather = WeatherPoll.latest
    }

    private fun bindSettings() {
        val store = ConfigStore(this)
        val field = findViewById<EditText>(R.id.settings_title)
        val stored = store.load().title
        if (stored != PanelConfig.DEFAULT.title) field.setText(stored)

        // Capped at what the masthead can actually draw, measured off the real font —
        // see TitleLimits. The label says the number, because a field that stops
        // accepting characters without saying why reads as broken.
        val max = TitleLimits.masthead(this)
        field.filters = TitleLimits.filter(max)
        findViewById<TextView>(R.id.settings_title_label).text = "TITLE  (max $max)"

        val swatch = findViewById<dev.kb896.aiusage.ui.glass.GlassChip>(R.id.settings_title_color)
        // Under ORIGINAL the chip's setter IS setBackgroundColor, so this is the same
        // flat rectangle it always was; under GLASS the colour is drawn over the pane.
        fun paintSwatch() { swatch.colour = store.load().titleColor }
        paintSwatch()
        swatch.setOnClickListener {
            ColorPickerDialog.show(this, store.load().titleColor) { picked ->
                val config = store.load().copy(titleColor = picked)
                store.save(config)
                graph.bridge.update(StatePayload.encode(graph.repository.state.value, config, WeatherPoll.latest))
                paintSwatch()
                refreshPreview()
            }
        }

        field.doAfterTextChanged { text ->
            val typed = text?.toString().orEmpty().trim()
            val config = store.load()
                .copy(title = if (typed.isEmpty()) PanelConfig.DEFAULT.title else typed)
            store.save(config)
            graph.bridge.update(StatePayload.encode(graph.repository.state.value, config, WeatherPoll.latest))
            refreshPreview()
        }
    }

    /**
     * The picked image is COPIED into app storage, not remembered as a URI.
     *
     * A content:// URI is a grant: it does not survive a reboot without persistable
     * permission, and the app that issued it can revoke it or be uninstalled. The panel
     * redraws forever and cannot depend on another app still being installed and still
     * willing.
     */
    /**
     * The background picture. A picker of its own, into a file of its own.
     *
     * Sharing the artwork's launcher and telling them apart by a flag was the shorter
     * way and the wrong one: the result arrives on a separate trip through the activity
     * lifecycle, and a flag set before the picker opens is a flag that has to survive the
     * process being killed while somebody browses their gallery.
     */
    private val pickBackdrop = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // Into a waiting slot, not the background's own file: see ArtImport.promote.
        val path = ArtImport.store(this, uri, ArtImport.Slot.BACKGROUND_PENDING)
        if (path == null) {
            toast("Could not read that image")
            return@registerForActivityResult
        }
        cropBackdrop(path, pending = true)
    }

    private val pickArt = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val path = ArtImport.store(this, uri)
        if (path == null) {
            toast("Could not read that image")
            return@registerForActivityResult
        }
        // Straight into the cropper. The art region is about 4:1, so almost nothing from
        // a camera fits it, and picking the middle of the picture silently is a decision
        // the user discovers by turning the phone over.
        cropArtwork(path)
    }

    /**
     * Asked when the location switch is turned on. A refusal turns the switch back off —
     * the setting cannot be on without the permission, and a switch that says otherwise
     * would be lying about a fetch that silently falls back to the typed place.
     */
    private val askLocation = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val store = ConfigStore(this)
        store.save(store.load().copy(weatherUseDevice = granted))
        buildSettingsRows()
        if (granted) WeatherPoll.nudge()
    }

    /** The place field's geocode, debounced — see the "Place" row in [buildSettingsRows].
     *  Cancelled on every keystroke so only the typed value that survives 800ms idle
     *  ever reaches the network. */
    private var geocodeJob: Job? = null

    /**
     * Everything else the panel exposes, built as rows rather than markup.
     *
     * Rebuilt wholesale when a structural choice changes — a ring pointing at a
     * different account renames the rows below it — because reconciling views in place
     * is more code than throwing them away, and this list is short.
     */
    private fun buildSettingsRows() {
        // Cancelled here, before the rows it would report into are thrown away: a
        // pending geocode landing on a detached TextView is silently lost, and worse,
        // Reset Everything writing a fresh config must not be undone by a lookup that
        // started before the reset and resolves after it.
        geocodeJob?.cancel()
        geocodeJob = null

        val glass = dev.kb896.aiusage.ui.glass.UiTheme.isGlass(this)
        val container = findViewById<LinearLayout>(R.id.settings_rows)
        val advanced = findViewById<LinearLayout>(R.id.advanced_rows)
        // One container per tab of the bottom bar — see bindTabs. The panel card holds the
        // usage page's rows and everything general; the clock page, the media page and
        // always-on each have their own.
        val generalRows = findViewById<LinearLayout>(R.id.general_rows)
        val clockRows = findViewById<LinearLayout>(R.id.clock_rows)
        val mediaRows = findViewById<LinearLayout>(R.id.media_rows)
        val aodRows = findViewById<LinearLayout>(R.id.aod_rows)
        container.removeAllViews()
        advanced.removeAllViews()
        generalRows.removeAllViews()
        clockRows.removeAllViews()
        mediaRows.removeAllViews()
        aodRows.removeAllViews()
        // A newer release, found by the two-hourly check: a row at the top with its notes,
        // there until this build is that version. The notification says it once; this
        // says it every time the screen is opened. See UpdateCheck.
        dev.kb896.aiusage.service.UpdateCheck.available(this)?.let { release ->
            SettingsRows.navigation(generalRows, "Update available: ${release.tag} — download") {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(dev.kb896.aiusage.service.UpdateCheck.APK),
                    ),
                )
            }
            val notes = dev.kb896.aiusage.service.UpdateCheck.plain(release.notes)
                .lineSequence().filter { it.isNotBlank() }.take(4).joinToString("\n")
            if (notes.isNotEmpty()) SettingsRows.note(generalRows, notes)
        }
        val store = ConfigStore(this)

        fun write(edit: (PanelConfig) -> PanelConfig) {
            val config = edit(store.load())
            store.save(config)
            graph.bridge.update(StatePayload.encode(graph.repository.state.value, config, WeatherPoll.latest))
            refreshPreview()
            // Ask the watcher to say again what is playing.
            //
            // Turning the media row ON used to do nothing until the next track change,
            // because the watcher declines to publish while the feature is off and the
            // panel only ever learns about media from a broadcast. On a paused player
            // that is forever, so the switch appeared not to work and then worked after a
            // restart — which is the worst way for a setting to behave.
            sendBroadcast(
                Intent(RearActivity.ACTION_MEDIA_COMMAND)
                    .setPackage(packageName)
                    .putExtra(RearActivity.EXTRA_MEDIA_COMMAND, RearActivity.MEDIA_SYNC),
            )
        }

        val config = store.load()

        // FIRST, because it is the only setting that changes what every other setting
        // looks like. A theme decides the face, the glyphs and whether surfaces refract —
        // see PanelTheme — so finding it at the bottom of a list of colours would be
        // finding out afterwards that half of them meant something else.
        SettingsRows.choice(
            generalRows,
            "Theme",
            dev.kb896.aiusage.data.PanelTheme.entries.map { it.name },
            config.theme.ordinal,
        ) { i ->
            // The back's look only: the front is glass whichever it is (UiTheme), so nothing
            // here needs rebuilding — the preview redraws with the rest.
            write { it.copy(theme = dev.kb896.aiusage.data.PanelTheme.entries[i]) }
        }
        // A look over whichever theme: the panel on a curved tube. Off by default —
        // it bends the picture, and touches with it. See CrtScreen.
        SettingsRows.toggle(
            generalRows, "CRT screen", config.crt,
        ) { on -> write { it.copy(crt = on) } }

        // Accounts are #1 and #2, never "personal" and "work". Those were this author's
        // two accounts, not a taxonomy — someone else has a client and a side project,
        // or one account and nothing else. The number is the identity; the name is
        // theirs to write.
        // Accounts in DISPLAY order, numbered by where they appear rather than by which
        // enum entry they are — the number is a position on the panel, and moving a row
        // is meant to change it.
        val order = config.orderedAccounts(Account.entries.size)
        order.forEachIndexed { position, index ->
            val account = Account.entries[index]
            val name = config.styleFor(index).label ?: account.label
            SettingsRows.reorderable(
                container,
                "#${position + 1}  $name",
                canMoveUp = position > 0,
                onMoveUp = {
                    write { cfg ->
                        val moved = cfg.orderedAccounts(Account.entries.size).toMutableList()
                        moved[position] = moved[position - 1]
                        moved[position - 1] = index
                        cfg.copy(accountOrder = moved)
                    }
                    buildSettingsRows()
                },
            ) {
                AccountSettingsSheet.show(this, findViewById(R.id.root_host), index,
                    graph.repository.state.value) { edited ->
                    graph.bridge.update(StatePayload.encode(graph.repository.state.value, edited, WeatherPoll.latest))
                    refreshPreview()
                    buildSettingsRows()
                }
            }
        }

        // "Time" rather than "Show the clock": the switch already says what it does, and
        // the word "show" in a row that is visibly a show/hide control is filler.
        SettingsRows.toggleWithColor(
            clockRows, "Time", config.showClock, config.clockTint,
            onChange = { on -> write { it.copy(showClock = on) } },
            onPick = { c -> write { it.copy(clockColor = c) } },
        )
        SettingsRows.toggleWithColor(
            clockRows, "Date", config.showDate, config.dateTint,
            onChange = { on -> write { it.copy(showDate = on) } },
            onPick = { c -> write { it.copy(dateColor = c) } },
        )
        // No switch: the line shows whenever there is a reading, and the Weather rows
        // below decide that. Only the colour is a choice, so only a swatch is offered.
        // Off leaves only the weather on the glass — rain when it rains, with no degrees
        // or place spelled out.
        SettingsRows.toggleWithColor(
            clockRows, "Weather line", config.showWeatherLine, config.weatherTint,
            onChange = { on -> write { it.copy(showWeatherLine = on) } },
            onPick = { c -> write { it.copy(weatherColor = c) } },
        )
        // The lights round the margin when the phone lies on its face — see table
        // mode in the README. They follow the clock's colour unless told otherwise.
        SettingsRows.colorRow(clockRows, "Table lights", config.tableTint) { c ->
            write { it.copy(tableColor = c) }
        }
        SettingsRows.toggleWithColor(
            clockRows, "Battery", config.showBattery, config.batteryTint,
            onChange = { on -> write { it.copy(showBattery = on) } },
            onPick = { c -> write { it.copy(batteryColor = c) } },
        )
        // The only control the panel has. Off by default — everything else on that
        // surface is something to look at, and a readout that reacts to being touched is
        // a surprise unless it was asked for.
        // The same row as Time, Date and Battery, and named for what they are rather
        // than described. "Camera button on the panel" was a sentence where the others
        // were labels, which made two controls of the same kind look like two kinds.
        SettingsRows.toggleWithColor(
            container, "Camera", config.showCamera, config.cameraTint,
            onChange = { on -> write { it.copy(showCamera = on) } },
            onPick = { c -> write { it.copy(cameraColor = c) } },
        )
        SettingsRows.toggleWithColor(
            clockRows, "Notifications", config.showNotifications, config.notificationTint,
            onChange = { on ->
                write { it.copy(showNotifications = on) }
                if (on) askNotificationAccess("Notifications on the back") { write { it.copy(showNotifications = false) } }
                buildSettingsRows()
            },
            onPick = { c -> write { it.copy(notificationColor = c) } },
        )
        // A SEPARATE switch, and deliberately so. This display faces away from its owner,
        // so everything on it is shown to whoever is behind them. Knowing a message
        // arrived and broadcasting what it said are different decisions.
        if (config.showNotifications) {
            if (!Setup.notificationAccess(this)) {
                SettingsRows.navigation(clockRows, "  ↳ allow notification access") { openNotificationAccess() }
            }
            SettingsRows.toggle(
                clockRows, "  ↳ include the message text", config.showNotificationText,
            ) { on -> write { it.copy(showNotificationText = on) } }
            SettingsRows.colorRow(
                clockRows, "  ↳ background", config.notificationGround,
            ) { c -> write { it.copy(notificationBg = c) } }
        }
        // NOT a "show" row like the ones above it — it changes nothing on the panel, only
        // how long the panel is there. Placed with them anyway because it is the same
        // kind of decision: one switch, one behaviour, no submenu.
        SettingsRows.toggle(
            aodRows, "Keep panel awake", config.keepPanelAwake,
        ) { on -> write { it.copy(keepPanelAwake = on) } }
        // The rear screen's always-on: dim and frozen instead of dark, asleep only in a
        // pocket, the slider one double tap away. See RearActivity.enterAod.
        SettingsRows.toggle(
            aodRows, "Always-on display", config.alwaysOn,
        ) { on -> write { it.copy(alwaysOn = on) }; buildSettingsRows() }
        if (config.alwaysOn) {
            SettingsRows.toggle(
                aodRows, "  ↳ battery level above the clock", config.aodBattery,
            ) { on -> write { it.copy(aodBattery = on) } }
            SettingsRows.toggle(
                aodRows, "  ↳ date", config.aodDate,
            ) { on -> write { it.copy(aodDate = on) } }
            SettingsRows.toggle(
                aodRows, "  ↳ weather line", config.aodWeather,
            ) { on -> write { it.copy(aodWeather = on) } }
            // Off by default: on a phone whose rear proximity sensor reads covered in the
            // hand, pocket mode is the panel going black for no reason anyone can see.
            SettingsRows.toggle(
                aodRows, "  ↳ pocket mode: display off when the back is covered", config.aodPocket,
            ) { on -> write { it.copy(aodPocket = on) } }
        }
        // One choice with four answers rather than switches, which would let
        // somebody ask for rain and snow at once.
        SettingsRows.choice(
            clockRows, "Weather", listOf("None", "Auto", "Rain", "Snow"),
            when (config.weather) {
                Weather.AUTO -> 1
                Weather.RAIN -> 2
                Weather.SNOW -> 3
                else -> 0
            },
        ) { i ->
            write {
                it.copy(
                    weather = when (i) {
                        1 -> Weather.AUTO
                        2 -> Weather.RAIN
                        3 -> Weather.SNOW
                        else -> Weather.OFF
                    },
                )
            }
        }

        // The place is typed, then resolved once — the poll never geocodes. The note under
        // the field shows what the geocoder made of it, so a typo is visible where it was
        // typed, and carries the attribution Open-Meteo's terms ask for.
        lateinit var placeNote: TextView
        fun placeNoteText(c: PanelConfig): String {
            val what = when {
                c.weatherPlaceLabel.isNotEmpty() -> "Weather for ${c.weatherPlaceLabel} · "
                c.weatherPlace.isNotBlank() -> "Not found · "
                else -> ""
            }
            return what + "Weather by Open-Meteo"
        }
        SettingsRows.textField(clockRows, "Place", config.weatherPlace, 40) { typed ->
            write { it.copy(weatherPlace = typed) }
            geocodeJob?.cancel()
            geocodeJob = lifecycleScope.launch {
                delay(800)   // after the last keystroke, not on every one
                val place = withContext(Dispatchers.IO) { graph.weatherApi.geocode(typed) }
                write {
                    it.copy(
                        weatherPlaceLabel = place?.label.orEmpty(),
                        weatherLat = place?.latitude, weatherLon = place?.longitude,
                    )
                }
                placeNote.text = placeNoteText(store.load())
                if (place != null) WeatherPoll.nudge()
            }
        }
        placeNote = SettingsRows.note(clockRows, placeNoteText(config))

        SettingsRows.toggle(clockRows, "Use the phone's location", config.weatherUseDevice) { on ->
            val allowed = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            when {
                !on -> write { it.copy(weatherUseDevice = false) }
                allowed -> { write { it.copy(weatherUseDevice = true) }; WeatherPoll.nudge() }
                else -> askLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        // DEBUG BUILDS ONLY. The demo replaces real readings with invented ones and real
        // notifications with written ones — which is exactly right for filming and would
        // be a lie in anybody else's hands. It is a tool for making the video, not a
        // feature of the app, so it does not ship.
        //
        // Under "keep awake" because it implies it: a demo holds the screen up whatever
        // that says, and putting the two together is what makes that obvious.
        if (BuildConfig.DEBUG) {
            SettingsRows.toggle(
                generalRows, "Demo mode (debug)", config.demoMode,
            ) { on -> write { it.copy(demoMode = on) } }
        }
        // Directly under Notifications, because it runs on the same grant — see Setup's
        // notification-access step. Someone turning this on and finding it dead has the
        // reason one row up rather than in an accordion they have not opened.
        SettingsRows.toggleWithColor(
            mediaRows, "Media controls", config.showMedia, config.mediaTint,
            // the cover and lyrics rows live under this switch: rebuild so they appear now
            onChange = { on ->
                write { it.copy(showMedia = on) }
                if (on) askNotificationAccess("Media controls") { write { it.copy(showMedia = false) } }
                buildSettingsRows()
            },
            onPick = { c -> write { it.copy(mediaColor = c) } },
        )
        if (config.showMedia) {
            if (!Setup.notificationAccess(this)) {
                SettingsRows.navigation(mediaRows, "  ↳ allow notification access") { openNotificationAccess() }
            }
            SettingsRows.choice(
                mediaRows, "  ↳ cover", listOf("SQUARE", "VINYL"),
                config.mediaArt.ordinal,
            ) { i ->
                write { it.copy(mediaArt = dev.kb896.aiusage.data.MediaArt.entries[i]) }
            }
            // Its own row and its own warning, because it is the only setting in this app
            // that sends anything anywhere. The note is not a disclaimer — it is the
            // information needed to make the choice, so it sits with the switch rather
            // than in a document nobody opens.
            SettingsRows.toggle(mediaRows, "  ↳ lyrics", config.showLyrics) { on ->
                // Turning it OFF wipes what was fetched. A feature that gathered data
                // should not leave the data behind when it is switched off; anything else
                // makes "off" mean "stopped collecting" rather than "undone".
                if (!on) {
                    dev.kb896.aiusage.data.LyricsCache(
                        java.io.File(filesDir, "lyrics"),
                    ).clear()
                }
                write { it.copy(showLyrics = on) }
                buildSettingsRows() // the note under this row says what the switch does
            }
            SettingsRows.note(
                mediaRows,
                if (config.showLyrics) {
                    "     sends each track's name, artist and length to lrclib.net"
                } else {
                    "     off — nothing about what you play leaves the phone"
                },
            )
        }
        if (config.showMedia && !NotificationManagerCompat.getEnabledListenerPackages(this)
                .contains(packageName)
        ) {
            SettingsRows.note(
                mediaRows,
                "  ↳ needs notification access — see ONBOARDING",
            )
        }
        SettingsRows.toggleWithColor(
            container, "Game", config.showClose, config.closeColor,
            onChange = { on -> write { it.copy(showClose = on) } },
            onPick = { c -> write { it.copy(closeColor = c) } },
        )

        // No colour grid here any more. Every panel colour now sits on the row of the
        // thing it paints — a grid made sense while they were a set chosen against each
        // other, and once each one had an owner it was just a second place to look.
        // The ramp keeps its grid in ADVANCED, where the five ARE chosen as a set.

        // Rings. Each picks an account, a window, and what to show about it.
        // The ramp belongs in ADVANCED. It is five colours that already work together,
        // and the common case for touching them is not "I want a different green" but
        // "I am adapting this to a different display" — the same reason the ring geometry
        // lives there.
        // For a bug report: what the system says about the rear display, gathered through
        // Shizuku and copied to the clipboard. The panel dying on someone else's phone is
        // a question about their firmware, and this is the shortest way to ask it.
        SettingsRows.navigation(advanced, "Copy rear-display report") { copyRearReport() }
        // The camera tip shows once; this brings it back for the next camera, and goes off
        // by itself once it has been swiped through again.
        shownCameraTip = config.cameraTip
        SettingsRows.toggle(
            advanced, "Show the camera tip next time", config.cameraTip,
        ) { on -> shownCameraTip = on; write { it.copy(cameraTip = on) } }
        SettingsRows.colorGrid(advanced, config.rampColors.mapIndexed { i, colour ->
            SettingsRows.Swatch("RAMP ${i + 1}", colour) { c ->
                write { cfg -> cfg.copy(rampColors = cfg.rampColors.toMutableList().also { it[i] = c }) }
            }
        })

        // Last row of the last section, and the only destructive control in the app.
        // It used to be debug-only, on the reasoning that a user has no business
        // wiping their own configuration — but they do: a panel tuned into a corner is
        // easier to start over than to unpick, and "reinstall the app" was the only
        // way out. What made it dangerous was being one tap from a colour swatch, and
        // that is what the confirmation fixes.
        dev.kb896.aiusage.ui.glass.GlassButton(this).apply {
            text = "Reset every setting to its default"
            textSize = 14f
            // The one filled button in the app. Everything else is text on the panel's
            // own ground, so a solid red block cannot be mistaken for one of them — which
            // matters for the only control here that destroys something. Red text alone
            // said the same thing far too quietly, in a list where a dim label and a
            // bright one are both just labels.
            //
            // Under GLASS the capsule takes CRIT as its tint, the same hue-blend every
            // other tinted button uses; under ORIGINAL it stays the flat red block.
            if (glass) {
                tint = Panel.Palette.CRIT
            } else {
                setTextColor(android.graphics.Color.WHITE)
                background = getDrawable(R.drawable.button_danger)
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_reset, 0, 0, 0)
            compoundDrawablePadding = (10 * resources.displayMetrics.density).toInt()
            typeface = UiType.of(this@MainActivity)
            setOnClickListener { confirmResetEverything() }
            advanced.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (16 * resources.displayMetrics.density).toInt() })
        }
        // Rows rebuilt are rows that clip again: let the glass in them draw past them.
        dev.kb896.aiusage.ui.glass.Unclip.tree(findViewById(R.id.settings_layout))
    }

    /**
     * Confirm before wiping every setting, and hold the button shut for [RESET_HOLD_MS].
     *
     * The delay is not there to be read — it is there to break the rhythm. A dialog
     * whose confirm button is live on arrival is dismissed by the same reflex that
     * opened it, and this is the one action in the app with nothing behind it: no undo,
     * and the previous configuration is gone the moment it runs. Five seconds is long
     * enough that finishing the tap is a second decision rather than the end of the
     * first one.
     *
     * Counting down out loud, rather than a button that silently becomes tappable —
     * otherwise a disabled button with no explanation reads as the dialog being broken.
     */
    private fun confirmResetEverything() {
        val dialog = Modal.builder(this)
            .setTitle("Reset every setting?")
            .setMessage(
                "Titles, colours, bar order, the ring settings and the bottom art all " +
                    "go back to how they shipped. Your connected accounts stay signed " +
                    "in. This cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset", null)   // wired in onShow, so it can start shut
            .create()

        var timer: android.os.CountDownTimer? = null
        dialog.setOnShowListener {
            val confirm = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            confirm.isEnabled = false
            confirm.text = "Reset (${RESET_HOLD_MS / 1000})"
            // Set only once the wait is over. Wiring it in the builder would leave the
            // action attached to a button that is merely disabled, and a disabled
            // button is a weaker guarantee than one with nothing behind it.
            confirm.setOnClickListener { dialog.dismiss(); resetEverything() }
            timer = object : android.os.CountDownTimer(RESET_HOLD_MS, 1000L) {
                // Rounded UP: the first tick lands just under four seconds and would
                // otherwise read 3, skipping a number off a five-second count.
                override fun onTick(left: Long) { confirm.text = "Reset (${(left + 999) / 1000})" }
                override fun onFinish() { confirm.isEnabled = true; confirm.text = "Reset" }
            }.start()
        }
        // A dialog dismissed mid-count leaves a timer holding a button from a window
        // that is gone.
        dialog.setOnDismissListener { timer?.cancel() }
        // Dressed after the show listener above has run, so the colour it puts on the
        // confirm button is the one that survives.
        Modal.show(dialog, danger = true)
    }

    private fun resetEverything() {
        val store = ConfigStore(this)
        // The imported image too. Leaving the file behind and only clearing the config
        // that points at it means "reset" restores the default art while the picture
        // the user chose sits in app storage forever.
        ArtImport.clear(this)
        store.save(PanelConfig.DEFAULT)
        graph.bridge.update(StatePayload.encode(graph.repository.state.value, PanelConfig.DEFAULT, WeatherPoll.latest))
        buildSettingsRows()
        refreshPreview()
        toast("Every setting reset")
    }

    /**
     * Choose which part of the stored image the panel shows.
     *
     * Pan and zoom always start at centre and 1x for a newly picked image — they were
     * chosen against the previous one and mean nothing against different proportions —
     * but the same dialog reopens on the stored values when an existing image is being
     * re-cropped.
     */
    private fun cropArtwork(path: String, reuseTransform: Boolean = false) {
        val bitmap = android.graphics.BitmapFactory.decodeFile(path)
        if (bitmap == null) {
            toast("Could not read that image")
            return
        }
        val store = ConfigStore(this)
        val existing = store.load().art
        val view = ArtCropView(this).apply {
            setImage(
                bitmap,
                if (reuseTransform) existing.panX else 0f,
                if (reuseTransform) existing.panY else 0f,
                if (reuseTransform) existing.zoom else 1f,
            )
        }
        Modal.show(Modal.builder(this)
            .setTitle("Drag to move, pinch to zoom")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use it") { _, _ ->
                val config = store.load().copy(art = ArtConfig(
                    imagePath = path, panX = view.panX, panY = view.panY, zoom = view.zoom))
                store.save(config)
                graph.bridge.update(StatePayload.encode(graph.repository.state.value, config, WeatherPoll.latest))
                refreshPreview()
                toast("Artwork set")
            })
    }

    /**
     * Crop a picture into the whole panel, rather than into the art strip.
     *
     * The same view and the same maths as the artwork — only the region's shape differs,
     * and it differs a lot: 4:1 against the panel's 1.58:1. Showing one and landing in
     * the other is exactly what this view exists to prevent.
     */
    private fun cropBackdrop(path: String, reuseTransform: Boolean = false, pending: Boolean = false) {
        val bitmap = android.graphics.BitmapFactory.decodeFile(path)
        if (bitmap == null) {
            toast("Could not read that image")
            return
        }
        val store = ConfigStore(this)
        val existing = store.load().backdrop
        // OPACITY, AND THE COLOUR BEHIND. The picture can be let through: below full, the
        // panel's ground colour shows under it, and that colour is chosen here, beside the
        // slider, rather than from the section's own colour button — which replaces the
        // picture. Both are previewed in the crop, inside the region, as the panel has them.
        var opacity = if (reuseTransform) existing.opacity else 1f
        var behind = store.load().background
        val view = ArtCropView(this).apply {
            regionAspect = ArtCropView.PANEL_ASPECT
            setImage(
                bitmap,
                if (reuseTransform) existing.panX else 0f,
                if (reuseTransform) existing.panY else 0f,
                if (reuseTransform) existing.zoom else 1f,
            )
            ground = behind
            imageAlpha = opacity
        }
        val dp = resources.displayMetrics.density
        val sheet = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (4 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        sheet.addView(view)
        val opacityLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(Panel.Palette.FG)
            typeface = UiType.of(this@MainActivity)
            text = "OPACITY  ${(opacity * 100).toInt()}%"
        }
        sheet.addView(opacityLabel, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (14 * dp).toInt() })
        val slider = dev.kb896.aiusage.ui.glass.GlassSlider(this).apply {
            // The settings' PanelSeekBar look, for ORIGINAL; GLASS draws its own.
            if (!glass) {
                progressDrawable = getDrawable(R.drawable.seek_track)
                thumb = getDrawable(R.drawable.seek_thumb)
                thumbOffset = 0
                splitTrack = false
            }
            background = null
            max = 100
            progress = (opacity * 100).toInt()
        }
        sheet.addView(slider, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        // The colour behind: only while there is something to see through to.
        val behindRow = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        SettingsRows.colorRow(behindRow, "Colour behind", behind) { picked ->
            behind = picked
            view.ground = picked
        }
        sheet.addView(behindRow)
        behindRow.visibility = if (opacity < 1f) View.VISIBLE else View.GONE
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                opacity = progress / 100f
                view.imageAlpha = opacity
                opacityLabel.text = "OPACITY  $progress%"
                behindRow.visibility = if (progress < 100) View.VISIBLE else View.GONE
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        var used = false
        Modal.show(Modal.builder(this)
            .setTitle("Drag to move, pinch to zoom")
            .setView(sheet)
            .setNegativeButton("Cancel", null)
            // Cancelled, or dismissed any other way: a picked picture that was never used
            // goes, and the background stays exactly as it was.
            .setOnDismissListener { if (pending && !used) ArtImport.clear(this, ArtImport.Slot.BACKGROUND_PENDING) }
            .setPositiveButton("Use it") { _, _ ->
                used = true
                // Only now does the picked picture become the background's file.
                val finalPath = if (pending) {
                    ArtImport.promote(this, ArtImport.Slot.BACKGROUND_PENDING, ArtImport.Slot.BACKGROUND)
                        ?: run { toast("Could not keep that image"); return@setPositiveButton }
                } else path
                var config = store.load().copy(backdrop = ArtConfig(
                    imagePath = finalPath, panX = view.panX, panY = view.panY, zoom = view.zoom, opacity = opacity))
                // The colour behind is the panel's ground; it only matters below full.
                if (opacity < 1f) config = config.copy(background = behind)
                store.save(config)
                dev.kb896.aiusage.ui.glass.Backdrop.rebuild(this)
                graph.bridge.update(StatePayload.encode(graph.repository.state.value, config, WeatherPoll.latest))
                refreshPreview()
                refreshBackdropClear()
                toast("Background set")
            })
    }

    /**
     * The background: a colour, or a picture, in one place.
     *
     * They used to be two controls in two sections — the colour as a row among the
     * toggles, the picture under a heading of its own further down — which asked the user
     * to know that they were the same decision. The panel has ONE ground and it is either
     * of those; the section says so.
     */
    private fun bindBackdrop() {
        findViewById<Button>(R.id.backdrop_color).setOnClickListener {
            ColorPickerDialog.show(this, ConfigStore(this).load().background) { picked ->
                val store = ConfigStore(this)
                // A COLOUR REPLACES A PICTURE. The panel has one ground and these are two
                // ways of saying what it is, so leaving the image in place would mean
                // choosing a colour and seeing no change at all — the picture is drawn
                // over the ground, and would go on covering it.
                //
                // The file goes with it, as it does from the bin: a megabyte of somebody's
                // photograph that nothing will read again. Said out loud in the toast,
                // because a control that quietly deletes something is worse than one that
                // asks — and this one is one tap from a picker that puts it back.
                val had = store.load().backdrop.imagePath != null
                val config = store.load().copy(background = picked, backdrop = ArtConfig())
                store.save(config)
                if (had) ArtImport.clear(this, ArtImport.Slot.BACKGROUND)
                graph.bridge.update(
                    StatePayload.encode(graph.repository.state.value, config, WeatherPoll.latest),
                )
                refreshPreview()
                dev.kb896.aiusage.ui.glass.Backdrop.rebuild(this)
                refreshBackdropClear()
                if (had) toast("Colour set, image removed")
            }
        }

        val pick = findViewById<Button>(R.id.backdrop_pick)
        pick.setOnClickListener { pickBackdrop.launch("image/*") }
        // Long-press re-crops what is already there, without going back through the
        // picker to choose the same file again — the same affordance the artwork has.
        pick.setOnLongClickListener {
            val path = ConfigStore(this).load().backdrop.imagePath
            if (path == null) toast("No image chosen yet")
            else cropBackdrop(path, reuseTransform = true)
            true
        }

        findViewById<android.widget.ImageButton>(R.id.backdrop_clear).setOnClickListener {
            val store = ConfigStore(this)
            val config = store.load().copy(backdrop = ArtConfig())
            store.save(config)
            // The file goes too. It is a megabyte of somebody's photograph that nothing
            // will ever read again, and leaving it would make "remove" a lie about disk.
            ArtImport.clear(this, ArtImport.Slot.BACKGROUND)
            graph.bridge.update(StatePayload.encode(graph.repository.state.value, config, WeatherPoll.latest))
            refreshPreview()
            dev.kb896.aiusage.ui.glass.Backdrop.rebuild(this)
            refreshBackdropClear()
            toast("Background removed")
        }
        refreshBackdropClear()
    }

    /**
     * The bin is live only when there is something in it.
     *
     * A control that is always available and does nothing most of the time teaches people
     * to ignore it. Dimmed, it also answers a question the section would otherwise leave
     * open — whether a picture is set at all — without a line of text saying so.
     */
    private fun refreshBackdropClear() {
        val has = ConfigStore(this).load().backdrop.imagePath != null
        findViewById<android.widget.ImageButton>(R.id.backdrop_clear).apply {
            isEnabled = has
            alpha = if (has) 1f else 0.35f
        }
    }

    private fun bindArt() {
        val pick = findViewById<Button>(R.id.art_pick)
        pick.setOnClickListener { pickArt.launch("image/*") }
        // Long-press re-crops what is already there, without going back through the
        // picker to choose the same file again.
        pick.setOnLongClickListener {
            val path = ConfigStore(this).load().art.imagePath
            if (path == null) toast("No image chosen yet") else cropArtwork(path, reuseTransform = true)
            true
        }
    }

    private fun bindDiagnostics() {
        // Restoring the built-in mark is the only reset left in here — wiping every
        // setting moved to ADVANCED, behind a confirmation, where a user can reach it.
        Diagnostics.install(findViewById(R.id.debug_slot)) {
            buildSettingsRows()
            refreshPreview()
            toast("Built-in mark restored")
        }
        lifecycleScope.launch {
            graph.repository.state.collect { Diagnostics.render(it, ConfigStore(this@MainActivity).load()) }
        }
    }

    /** Advanced is an open card now, like every other: nothing to expand. */
    private fun bindAdvancedToggle() = Unit

    /**
     * Live ring tuning, writing through to the panel config on every move.
     *
     * These edit the same PanelConfig everything else does and reach the panel by the
     * same payload, so the rings track the drag on the rear display in real time. They
     * previously wrote to a separate GeometryStore with its own broadcast — two stores
     * and two channels aimed at one panel, which is how a panel ends up drawing one
     * source's numbers with another's layout.
     *
     * Only fromUser events are acted on. setProgress() during seeding fires the listener
     * too, and reacting to that would push five times on every open and — worse — write
     * the seeded values back over stored ones.
     */
    private fun bindGeometrySliders() {
        val store = ConfigStore(this)
        var current = store.load().effectiveRingLayout

        data class Slider(
            val bar: SeekBar, val label: TextView, val name: String,
            val range: IntRange, val get: () -> Int, val set: (Int) -> RingLayout,
        )

        val sliders = listOf(
            Slider(findViewById(R.id.geo_cx), findViewById(R.id.geo_cx_label), "RING X",
                RingLayout.CX_RANGE, { current.cx }, { current.copy(cx = it) }),
            Slider(findViewById(R.id.geo_cy1), findViewById(R.id.geo_cy1_label), "FIRST Y",
                RingLayout.CY_FIRST_RANGE, { current.cyFirst }, { current.copy(cyFirst = it) }),
            Slider(findViewById(R.id.geo_cy2), findViewById(R.id.geo_cy2_label), "SECOND Y",
                RingLayout.CY_SECOND_RANGE, { current.cySecond }, { current.copy(cySecond = it) }),
            Slider(findViewById(R.id.geo_radius), findViewById(R.id.geo_radius_label), "RADIUS",
                RingLayout.RADIUS_RANGE, { current.radius }, { current.copy(radius = it) }),
            Slider(findViewById(R.id.geo_stroke), findViewById(R.id.geo_stroke_label), "STROKE",
                RingLayout.STROKE_RANGE, { current.stroke }, { current.copy(stroke = it) }),
        )

        fun seed() {
            for (s in sliders) {
                s.bar.max = s.range.last - s.range.first
                s.bar.progress = s.get() - s.range.first
                s.label.text = "${s.name}  ${s.get()}"
            }
        }
        seed()

        fun pushLayout(layout: RingLayout) {
            val config = store.load().copy(ringLayout = layout)
            store.save(config)
            graph.bridge.update(StatePayload.encode(graph.repository.state.value, config, WeatherPoll.latest))
            refreshPreview()
        }

        for (s in sliders) {
            s.bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val value = progress + s.range.first
                    current = s.set(value)
                    s.label.text = "${s.name}  $value"
                    pushLayout(current)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }

        findViewById<Button>(R.id.geo_reset).setOnClickListener {
            current = RingLayout.forDevice()
            seed()
            pushLayout(current)
            toast("Ring geometry reset")
        }
    }

    /**
     * The grant button IS the status.
     *
     * Three lines said "Shizuku running", "Permission granted" and the rear display's
     * id, above a button asking for the permission two of them described. Once granted
     * they never change again, and a display id is a number nobody acts on — it belonged
     * to bringing this up, not to using it. What remains worth knowing is whether the
     * permission is in place, which the button can say by itself: ticked and disabled
     * when there is nothing left to do.
     *
     * The failure states still speak, through renderRecovery — that is where Shizuku not
     * running or the display being absent turns into something the user can act on.
     */
    /**
     * The setup checklist: what has to be true, and whether it is.
     *
     * Rebuilt on every resume, because most of these are changed in Settings and the
     * user comes back to this screen afterwards — a checklist that needed a restart to
     * notice would be worse than none.
     */
    /**
     * Setup as BUTTONS, one per step, not a list of headings with prose under them.
     *
     * Each is the action itself: tap it and you land on the settings page that satisfies
     * it. Once satisfied it ticks, greens, and disables — there is nothing left to press,
     * and a live button that does nothing is worse than an absent one.
     *
     * Rebuilt on every resume, because these are all changed in Settings and the user
     * comes straight back here afterwards. A checklist needing a restart to notice would
     * be worse than none.
     */
    /**
     * Onboarding is a dialog, not a section of the page.
     *
     * While anything that can be checked is still undone it comes up by itself and cannot
     * be closed — no Cancel, no back, no tap outside — because nothing else here works
     * until it is. "Done" unlocks once every checkable step has ticked: the same rule as
     * ever (Setup.allDone) — a step whose state cannot be read, autostart or the recents
     * lock, is advice and never holds it open. While a step is undone the show/hide
     * button reads "Setup" and opens it too (refreshControl).
     *
     * Rebuilt on every resume, inside the dialog if it is up, because most of these are
     * changed in Settings and the user comes straight back here afterwards.
     */
    private var onboarding: android.app.AlertDialog? = null
    private var onboardingRows: LinearLayout? = null

    private fun buildSetupRows() {
        // The page's own section is gone for good; the checklist lives in the dialog.
        findViewById<View>(R.id.setup_heading)?.visibility = View.GONE
        findViewById<View>(R.id.setup_rows)?.visibility = View.GONE
        findViewById<View>(R.id.rule_1)?.visibility = View.GONE
        val status = graph.bridge.status()
        val steps = Setup.steps(this, status.granted, status.running)
        val done = Setup.allDone(steps)
        if (!done && onboarding?.isShowing != true) showOnboarding()
        onboardingRows?.let { fillSetupRows(it, steps) }
        refreshControl()
        onboarding?.let { dialog ->
            dialog.setCancelable(done)
            dialog.setCanceledOnTouchOutside(done)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = done
        }
        collapseEmptyCards()
    }

    /** Opens the checklist; closable only when nothing checkable is left to do. */
    private fun showOnboarding() {
        if (onboarding?.isShowing == true) return
        val status = graph.bridge.status()
        val steps = Setup.steps(this, status.granted, status.running)
        val done = Setup.allDone(steps)
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPx(20), dpPx(4), dpPx(20), dpPx(8))
        }
        fillSetupRows(rows, steps)
        onboardingRows = rows
        val dialog = Modal.show(Modal.builder(this)
            .setTitle("Get set up")
            .setView(android.widget.ScrollView(this).apply { addView(rows) })
            .setCancelable(done)
            // Wired below, so it can start shut and be opened by the rebuild.
            .setPositiveButton("Done", null))
        dialog.setCanceledOnTouchOutside(done)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            isEnabled = done
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setOnDismissListener {
            if (onboarding === dialog) { onboarding = null; onboardingRows = null }
        }
        onboarding = dialog
    }

    /** The steps as buttons, one each: tap it and you land where it is satisfied. */
    private fun fillSetupRows(container: LinearLayout, steps: List<Setup.Step>) {
        container.removeAllViews()
        for (step in steps) {
            // A step nobody can act on for you is TEXT, not a button. Locking the app in
            // recents is a gesture in the launcher; a button that cannot perform it, and
            // whose state cannot be read back, is a control in name only.
            if (step.satisfied == null && step.open == null) {
                container.addView(TextView(this).apply {
                    text = step.why
                    textSize = 11f
                    setTextColor(Panel.Palette.DIM)
                    typeface = UiType.of(this@MainActivity)
                    setPadding(0, dpPx(10), 0, dpPx(2))
                })
                continue
            }
            val done = step.satisfied == true
            // A step whose state cannot be read shows no mark at all rather than an
            // empty box: an unticked box reads as something the user failed to do, and
            // they will keep trying to tick it.
            val mark = when (step.satisfied) {
                true -> "✓  "
                false -> ""
                null -> ""
            }
            container.addView(dev.kb896.aiusage.ui.glass.GlassButton(this).apply {
                text = "$mark${step.title}"
                textSize = 13f
                val actionable = step.open != null || step.kind != Setup.Kind.OPEN
                // The Xiaomi row stays live in both states: done means "restore", not "nothing".
                isEnabled = if (step.kind == Setup.Kind.XIAOMI_APP) true else !done && actionable
                // Coloured by whether it DOES anything, not by whether its state can be
                // read. Those were the same thing while the only unreadable step was a
                // line of advice with no button; the autostart step is unreadable AND
                // pressable, and dimming it made a working button look disabled.
                setTextColor(
                    when {
                        done -> Panel.Palette.WORK
                        actionable -> Panel.Palette.FG
                        else -> Panel.Palette.DIM
                    }
                )
                typeface = UiType.of(this@MainActivity)
                setOnClickListener {
                    if (step.kind == Setup.Kind.XIAOMI_APP) {
                        // satisfied == true means the app is already gone: the tap restores it
                        if (step.satisfied == true) setXiaomiRearApp(installed = true)
                        else confirmRemoveXiaomiRearApp()
                        return@setOnClickListener
                    }
                    val intent = step.open?.invoke()
                    if (intent == null) {
                        // Shizuku: granted in-app. onResume re-reads the state when the
                        // system dialog returns, so the row ticks itself.
                        // The request goes nowhere while Shizuku is down; say so rather
                        // than let the tap fall silent.
                        if (!graph.bridge.status().running) toast("Start Shizuku first — the row above")
                        else graph.bridge.requestPermission(this@MainActivity)
                        return@setOnClickListener
                    }
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {
                        toast("Could not open that settings page")
                    }
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpPx(4) })
        }

    }

    private fun dpPx(value: Int) = (value * resources.displayMetrics.density).toInt()

    /**
     * Only the recovery text now. The Shizuku state is a row in the setup list, which
     * ticks and disables itself — a separate button saying the same thing was the third
     * place on this screen to learn it from.
     */
    private fun refreshStatus() {
        renderRecovery(graph.bridge.status())
    }

    /**
     * The three failure states this renders are independent, not a priority chain
     * dressed up as one: rearDisplayId is derived from DisplayManager alone (see
     * ShizukuBridge.rearDisplayId's KDoc) and has nothing to do with Shizuku's own
     * running/granted state, so its message is shown whenever it applies, regardless
     * of the other two. running vs granted genuinely IS a priority chain — asking to
     * grant a permission to a server that isn't up yet is meaningless — so only one
     * of those two command blocks is ever shown at a time.
     */
    private fun renderRecovery(s: ShizukuBridge.Status) {
        when {
            !s.running -> {
                recoveryNote.text =
                    "Shizuku server is not running. It does not survive a reboot, and " +
                        "restarting it is a manual step on this non-rooted device — the " +
                        "panel stays dark until you run this:"
                recoveryNote.visibility = View.VISIBLE
                recoveryCommand.setText(SHIZUKU_START_COMMAND)
                recoveryScroll.visibility = View.VISIBLE
            }
            !s.granted -> {
                recoveryNote.text =
                    "Shizuku is running but this app hasn't been granted permission. " +
                        "Tap \"Grant Shizuku permission\" above, or run:"
                recoveryNote.visibility = View.VISIBLE
                recoveryCommand.setText(SHIZUKU_GRANT_COMMAND)
                recoveryScroll.visibility = View.VISIBLE
            }
            else -> {
                recoveryNote.visibility = View.GONE
                recoveryScroll.visibility = View.GONE
            }
        }
        recoveryDisplayAbsent.text = "Rear panel not found — this build only supports the Xiaomi 17 Pro and Pro Max."
        recoveryDisplayAbsent.visibility = if (s.rearDisplayId < 0) View.VISIBLE else View.GONE
        collapseEmptyCards()
    }



    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /**
     * The one destructive-looking tap in the app asks first, and the asking says what
     * actually happens: nothing is deleted, and the same row undoes it.
     */
    private fun confirmRemoveXiaomiRearApp() {
        Modal.show(Modal.builder(this)
            .setTitle("Remove Xiaomi's rear-screen app?")
            .setMessage(
                "It is hidden for your user, not deleted — it stays on the system partition, " +
                    "and this row restores it with one tap. Without it out of the way HyperOS " +
                    "keeps putting its own screen back over the panel."
            )
            .setPositiveButton("Remove") { _, _ -> setXiaomiRearApp(installed = false) }
            .setNegativeButton("Cancel", null), danger = true)
    }

    private fun copyRearReport() {
        if (!graph.bridge.status().ready) {
            toast("Shizuku first — this needs its privilege")
            return
        }
        toast("Gathering…")
        lifecycleScope.launch(Dispatchers.IO) {
            val report = graph.bridge.rearReport()
            withContext(Dispatchers.Main) {
                if (report.isNullOrBlank()) {
                    toast("Shizuku's service did not answer — is Shizuku running?")
                    return@withContext
                }
                val text = "AI Usage ${dev.kb896.aiusage.BuildConfig.VERSION_NAME}\n$report"
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("rear-display report", text))
                Modal.show(Modal.builder(this@MainActivity)
                    .setTitle("Rear-display report — copied")
                    .setMessage(text)
                    .setPositiveButton("OK", null))
            }
        }
    }

    private fun setXiaomiRearApp(installed: Boolean) {
        if (!graph.bridge.status().ready) {
            toast("Shizuku first — this needs its privilege")
            return
        }
        toast(if (installed) "Restoring…" else "Removing…")
        // Off the main thread: the bridge may wait for Shizuku to start the service, and
        // the pm command itself takes a moment.
        lifecycleScope.launch(Dispatchers.IO) {
            val result = graph.bridge.setXiaomiRearApp(installed)
            withContext(Dispatchers.Main) {
                when (result) {
                    null -> toast("Shizuku's service did not answer — is Shizuku running?")
                    true -> {
                        toast(if (installed) "Xiaomi's rear-screen app restored" else "Xiaomi's rear-screen app removed")
                        // the bridge's gate decides: the panel goes up now that the way is
                        // clear, or comes down now that Xiaomi's app is back
                        graph.bridge.show(StatePayload.encode(graph.repository.state.value, ConfigStore(this@MainActivity).load(), WeatherPoll.latest))
                    }
                    false -> toast("That did not work — see the log")
                }
                buildSetupRows()
            }
        }
    }

    companion object {
        /** The bottom bar's tabs, in its order. See bindTabs. */
        private const val TAB_GENERAL = 0
        private const val TAB_CLOCK = 1
        private const val TAB_USAGE = 2
        private const val TAB_MEDIA = 3
        private const val TAB_AOD = 4

        /** How long the reset dialog holds its confirm button shut. */
        private const val RESET_HOLD_MS = 5_000L

        private val GOOD_COLOR = Panel.RAMP_STOPS.first().second

        /**
         * Copy-pasted by the user out of this app, verbatim, onto their Mac's
         * terminal — it MUST stay exactly one line with no `sh -c` wrapper. `adb
         * shell sh -c '...'` makes adb join its arguments and the device shell
         * re-parse them, which destroys the `$(...)` command substitutions: `$SRC`
         * comes back empty and `dirname` fails with no argument. Backslash
         * continuations don't help either — a backslash-newline inside single
         * quotes is literal on this shell, not a continuation. See Task 3 Step 10's
         * report for the reproduction. Do not reformat this string for readability.
         */
        private const val SHIZUKU_START_COMMAND =
            "adb -s f940914b shell 'SRC=\$(pm path moe.shizuku.privileged.api | sed s/package://); " +
                "TOKEN=\$(cat /proc/sys/kernel/random/uuid); \$(dirname \$SRC)/lib/arm64/libshizuku.so " +
                "--apk=\$SRC --token=\$TOKEN --package=moe.shizuku.privileged.api &'"

        private const val SHIZUKU_GRANT_COMMAND =
            "adb -s f940914b shell pm grant dev.kb896.aiusage moe.shizuku.manager.permission.API_V23"
    }
}
