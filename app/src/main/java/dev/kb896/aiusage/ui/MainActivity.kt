package dev.kb896.aiusage.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import dev.kb896.aiusage.BuildConfig
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
import dev.kb896.aiusage.data.CallLog
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
import dev.kb896.aiusage.rear.ShizukuBridge
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.service.UsagePollService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    private lateinit var grantButton: Button
    private lateinit var showButton: Button
    private lateinit var hideButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
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

    override fun onPause() {
        super.onPause()
        // Backgrounding re-locks: the app may be resumed by someone else, and a phone
        // handed over while a screen is open is exactly the case this guards.
        Unlock.lock()
    }

    override fun onResume() {
        super.onResume()
        buildSetupRows()
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
        grantButton = findViewById(R.id.grant_button)
        showButton = findViewById(R.id.show_button)
        hideButton = findViewById(R.id.hide_button)
        bindPreview()
        bindSettings()
        buildSettingsRows()
        bindArt()
        bindDiagnostics()
        bindAdvancedToggle()
        bindGeometrySliders()

        // Selectable and copyable (long-press -> Copy), but never editable — this
        // field only ever displays a command MainActivity wrote, never accepts
        // typed input.
        recoveryCommand.keyListener = null
    }

    private fun applyPalette() {
        findViewById<View>(R.id.root_host).setBackgroundColor(Panel.Palette.BG)
        val mono = resources.getFont(R.font.departure_mono)

        // Every TextView in the tree, found by walking it rather than by listing them.
        //
        // The list this replaced had to be extended by hand for each new control, and
        // was not — the ring-geometry labels were added later and quietly kept the
        // system sans, in an app whose whole visual identity is one monospaced face.
        // A sweep cannot be forgotten.
        fun applyFont(view: View) {
            when (view) {
                is TextView -> view.typeface = mono
                is ViewGroup -> for (i in 0 until view.childCount) applyFont(view.getChildAt(i))
            }
        }
        applyFont(findViewById(R.id.root_host))

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

    private fun wireActions() {
        grantButton.setOnClickListener {
            graph.bridge.requestPermission(this)
            refreshStatus()
        }
        // Renders whatever the repository already holds — no network call. Placing
        // the panel is a display action, not a data action, so it must not spend a
        // request against an endpoint that has already rate-limited one account.
        //
        // It also used to send "{}", which decodes to an empty state: the panel went
        // blank on placement and stayed blank until the next poll, now up to five
        // minutes away.
        showButton.setOnClickListener {
            graph.bridge.show(StatePayload.encode(graph.repository.state.value, ConfigStore(this).load()))
        }
        hideButton.setOnClickListener { graph.bridge.hide() }
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
            StatePayload.encode(graph.repository.state.value, ConfigStore(this@MainActivity).load()))
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
    }

    private fun refreshPreview() {
        val preview = findViewById<PanelPreviewView>(R.id.panel_preview) ?: return
        preview.config = ConfigStore(this).load()
        preview.state = graph.repository.state.value
    }

    private fun bindSettings() {
        val store = ConfigStore(this)
        val field = findViewById<EditText>(R.id.settings_title)
        val stored = store.load().title
        if (stored != PanelConfig.DEFAULT.title) field.setText(stored)

        field.doAfterTextChanged { text ->
            val typed = text?.toString().orEmpty().trim()
            val config = store.load()
                .copy(title = if (typed.isEmpty()) PanelConfig.DEFAULT.title else typed)
            store.save(config)
            graph.bridge.update(StatePayload.encode(graph.repository.state.value, config))
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
     * Everything else the panel exposes, built as rows rather than markup.
     *
     * Rebuilt wholesale when a structural choice changes — a ring pointing at a
     * different account renames the rows below it — because reconciling views in place
     * is more code than throwing them away, and this list is short.
     */
    private fun buildSettingsRows() {
        val container = findViewById<LinearLayout>(R.id.settings_rows)
        val advanced = findViewById<LinearLayout>(R.id.advanced_rows)
        container.removeAllViews()
        advanced.removeAllViews()
        val store = ConfigStore(this)

        fun write(edit: (PanelConfig) -> PanelConfig) {
            val config = edit(store.load())
            store.save(config)
            graph.bridge.update(StatePayload.encode(graph.repository.state.value, config))
            refreshPreview()
        }

        val config = store.load()

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
                    graph.bridge.update(StatePayload.encode(graph.repository.state.value, edited))
                    refreshPreview()
                    buildSettingsRows()
                }
            }
        }

        SettingsRows.toggle(container, "Show the clock", config.showClock) { on ->
            write { it.copy(showClock = on) }
        }
        SettingsRows.toggle(container, "Show the battery", config.showBattery) { on ->
            write { it.copy(showBattery = on) }
        }

        // Colours as one grid, so they can be judged against each other — which is how
        // they are actually chosen — rather than down a column one at a time.
        val swatches = mutableListOf(
            SettingsRows.Swatch("BACKGROUND", config.background) { c ->
                write { it.copy(background = c) }
            },
            SettingsRows.Swatch("CLOCK & BATT", config.extrasColor) { c ->
                write { it.copy(extrasColor = c) }
            },
        )
        SettingsRows.colorGrid(container, swatches)

        // Rings. Each picks an account, a window, and what to show about it.
        // The ramp belongs in ADVANCED. It is five colours that already work together,
        // and the common case for touching them is not "I want a different green" but
        // "I am adapting this to a different display" — the same reason the ring geometry
        // lives there.
        SettingsRows.colorGrid(advanced, config.rampColors.mapIndexed { i, colour ->
            SettingsRows.Swatch("RAMP ${i + 1}", colour) { c ->
                write { cfg -> cfg.copy(rampColors = cfg.rampColors.toMutableList().also { it[i] = c }) }
            }
        })
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
        android.app.AlertDialog.Builder(this)
            .setTitle("Drag to move, pinch to zoom")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use it") { _, _ ->
                val config = store.load().copy(art = ArtConfig(
                    imagePath = path, panX = view.panX, panY = view.panY, zoom = view.zoom))
                store.save(config)
                graph.bridge.update(StatePayload.encode(graph.repository.state.value, config))
                refreshPreview()
                toast("Artwork set")
            }
            .show()
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
        Diagnostics.install(findViewById(R.id.debug_slot)) {
            buildSettingsRows()
            refreshPreview()
            toast("Settings reset")
        }
        lifecycleScope.launch {
            graph.repository.state.collect { Diagnostics.render(it, ConfigStore(this@MainActivity).load()) }
        }
    }

    private fun bindAdvancedToggle() {
        val heading = findViewById<TextView>(R.id.geo_heading)
        val body = findViewById<View>(R.id.geo_body)
        heading.setOnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            heading.text = if (show) "ADVANCED  ▾" else "ADVANCED  ▸"
        }
    }

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
        var current = store.load().ringLayout

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
            graph.bridge.update(StatePayload.encode(graph.repository.state.value, config))
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
            current = RingLayout()
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
    private fun buildSetupRows() {
        val container = findViewById<LinearLayout>(R.id.setup_rows) ?: return
        container.removeAllViews()
        val status = graph.bridge.status()
        val steps = Setup.steps(this, shizukuGranted = status.granted,
            shizukuRunning = status.running)

        for (step in steps) {
            // A step whose state cannot be read shows a bullet, not an empty box: an
            // unticked box reads as something the user failed to do, and they will keep
            // trying to tick it.
            val mark = when (step.satisfied) {
                true -> "✓"
                false -> "○"
                null -> "•"
            }
            val colour = when (step.satisfied) {
                true -> Panel.Palette.WORK
                false -> Panel.Palette.WARN
                null -> Panel.Palette.DIM
            }
            container.addView(TextView(this).apply {
                text = "$mark  ${step.title}"
                textSize = 13f
                setTextColor(colour)
                typeface = resources.getFont(R.font.departure_mono)
                setPadding(0, dpPx(8), 0, 0)
            })
            // The reason is shown only while the step is outstanding. Once satisfied it
            // is a paragraph explaining something that is already true.
            if (step.satisfied == false || step.satisfied == null) {
                container.addView(TextView(this).apply {
                    text = step.why
                    textSize = 11f
                    setTextColor(Panel.Palette.DIM)
                    typeface = resources.getFont(R.font.departure_mono)
                    setPadding(dpPx(18), dpPx(2), 0, 0)
                })
                step.open?.let { intent ->
                    container.addView(Button(this).apply {
                        text = "Open settings"
                        textSize = 12f
                        setTextColor(Panel.Palette.FG)
                        typeface = resources.getFont(R.font.departure_mono)
                        setOnClickListener {
                            try {
                                startActivity(intent())
                            } catch (_: Exception) {
                                toast("Could not open that settings page")
                            }
                        }
                    })
                }
            }
        }
    }

    private fun dpPx(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun refreshStatus() {
        val s = graph.bridge.status()
        val ready = s.running && s.granted
        grantButton.text = if (ready) "✓  Shizuku permission granted"
            else "Grant Shizuku permission"
        grantButton.isEnabled = !ready
        grantButton.setTextColor(if (ready) Panel.Palette.WORK else Panel.Palette.FG)
        renderRecovery(s)
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
        recoveryDisplayAbsent.text = "Rear panel not found — this build only supports the Xiaomi 17 Pro."
        recoveryDisplayAbsent.visibility = if (s.rearDisplayId < 0) View.VISIBLE else View.GONE
    }

    /**
     * The last few Anthropic requests, newest first. The panel can show that an
     * account is unhealthy but not why or when — this is the only place on the device
     * that answers that without adb.
     */
    private fun renderCallLog(): String {
        val recent = CallLog.recent()
        if (recent.isEmpty()) return "\n\nRECENT CALLS\n  (none yet)"
        val now = System.currentTimeMillis()
        return buildString {
            append("\n\nRECENT CALLS\n")
            for (e in recent) {
                val agoSec = ((now - e.atMillis) / 1000).coerceAtLeast(0)
                val ago = if (agoSec >= 60) "${agoSec / 60}m" else "${agoSec}s"
                append("  ").append(ago.padStart(4)).append("  ")
                    .append(e.account.padEnd(9)).append(e.outcome).append('\n')
            }
        }
    }


    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
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
