package dev.rgarjoaba.claudestatus.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.rgarjoaba.claudestatus.Panel
import dev.rgarjoaba.claudestatus.R
import dev.rgarjoaba.claudestatus.auth.LoopbackServer
import dev.rgarjoaba.claudestatus.auth.OAuthClient
import dev.rgarjoaba.claudestatus.auth.PkceChallenge
import dev.rgarjoaba.claudestatus.auth.TokenImport
import dev.rgarjoaba.claudestatus.data.Account
import dev.rgarjoaba.claudestatus.data.DashboardState
import dev.rgarjoaba.claudestatus.data.StatePayload
import dev.rgarjoaba.claudestatus.rear.ShizukuBridge
import dev.rgarjoaba.claudestatus.service.Graph
import dev.rgarjoaba.claudestatus.service.UsagePollService
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

    private lateinit var statusRunning: TextView
    private lateinit var statusGranted: TextView
    private lateinit var statusDisplay: TextView
    private lateinit var recoveryNote: TextView
    private lateinit var recoveryScroll: View
    private lateinit var recoveryCommand: EditText
    private lateinit var recoveryDisplayAbsent: TextView
    private lateinit var grantButton: Button
    private lateinit var signInButton: Button
    private lateinit var pasteField: EditText
    private lateinit var importButton: Button
    private lateinit var showButton: Button
    private lateinit var hideButton: Button
    private lateinit var previewLabel: TextView
    private lateinit var previewText: TextView

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
            graph.repository.state.collect { state -> renderPreview(state) }
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // Shizuku's permission grant is asynchronous (a system dialog the user
        // returns from) and requestPermission() below has no result callback wired
        // — see ShizukuBridge's history (Task 3) for why that's deliberate, not an
        // oversight. onResume is what catches the grant once the user comes back.
        refreshStatus()
    }

    private fun bindViews() {
        statusRunning = findViewById(R.id.status_running)
        statusGranted = findViewById(R.id.status_granted)
        statusDisplay = findViewById(R.id.status_display)
        recoveryNote = findViewById(R.id.recovery_note)
        recoveryScroll = findViewById(R.id.recovery_scroll)
        recoveryCommand = findViewById(R.id.recovery_command)
        recoveryDisplayAbsent = findViewById(R.id.recovery_display_absent)
        grantButton = findViewById(R.id.grant_button)
        signInButton = findViewById(R.id.sign_in_button)
        pasteField = findViewById(R.id.paste_field)
        importButton = findViewById(R.id.import_button)
        showButton = findViewById(R.id.show_button)
        hideButton = findViewById(R.id.hide_button)
        previewLabel = findViewById(R.id.preview_label)
        previewText = findViewById(R.id.preview_text)

        // Selectable and copyable (long-press -> Copy), but never editable — this
        // field only ever displays a command MainActivity wrote, never accepts
        // typed input.
        recoveryCommand.keyListener = null
    }

    private fun applyPalette() {
        findViewById<View>(R.id.root_layout).setBackgroundColor(Panel.Palette.BG)
        val mono = resources.getFont(R.font.departure_mono)

        val textViews = listOf<TextView>(
            statusRunning, statusGranted, statusDisplay,
            recoveryNote, recoveryCommand, recoveryDisplayAbsent,
            grantButton, signInButton, pasteField, importButton,
            showButton, hideButton, previewLabel, previewText,
        )
        for (v in textViews) v.typeface = mono

        recoveryNote.setTextColor(Panel.Palette.FG)
        recoveryCommand.setTextColor(Panel.Palette.FG)
        recoveryDisplayAbsent.setTextColor(Panel.Palette.FG)
        pasteField.setTextColor(Panel.Palette.FG)
        pasteField.setHintTextColor(Panel.Palette.DIM)
        previewLabel.setTextColor(Panel.Palette.DIM)
        previewText.setTextColor(Panel.Palette.FG)

        listOf(R.id.rule_1, R.id.rule_2, R.id.rule_3).forEach {
            findViewById<View>(it).setBackgroundColor(Panel.Palette.RULE)
        }
    }

    private fun wireActions() {
        grantButton.setOnClickListener {
            graph.bridge.requestPermission(this)
            refreshStatus()
        }
        signInButton.setOnClickListener { signInPersonal() }
        importButton.setOnClickListener { importPastedToken() }
        // Renders whatever the repository already holds — no network call. Placing
        // the panel is a display action, not a data action, so it must not spend a
        // request against an endpoint that has already rate-limited one account.
        //
        // It also used to send "{}", which decodes to an empty state: the panel went
        // blank on placement and stayed blank until the next poll, now up to five
        // minutes away.
        showButton.setOnClickListener {
            graph.bridge.show(StatePayload.encode(graph.repository.state.value))
        }
        hideButton.setOnClickListener { graph.bridge.hide() }
    }

    /**
     * Imports the work credential from a paste. Deliberately NOT from a file on shared
     * storage: /sdcard is readable by any app holding storage permission, so staging a
     * live enterprise refresh token there would expose it to every app on the device.
     * The paste field is the only import route in this app — no file read, no
     * document picker, no storage permission anywhere in the manifest.
     */
    private fun importPastedToken() {
        // Prefer the file the export script writes into our own private storage —
        // macOS and Android share no clipboard, so "copy on the Mac, paste on the
        // phone" cannot actually work. The paste field remains as a manual fallback.
        val staged = java.io.File(filesDir, "work-token.json")
        val source = if (staged.canRead()) staged.readText() else pasteField.text.toString()

        val record = TokenImport.parse(source)
        if (record == null) {
            toast("That doesn't look like a Claude Code credential")
            return
        }
        graph.store.save(Account.WORK, record)
        // Delete the staged file the moment its contents are encrypted in TokenStore —
        // app-private is not a reason to leave a live credential lying in plaintext.
        staged.delete()
        // Clear immediately — leaving it in the field keeps it in the view hierarchy,
        // recoverable from a screenshot, and restorable across a config change.
        pasteField.setText("")
        toast("Work token imported")
        refreshNow()
    }

    /**
     * PKCE login for the personal account (Task 10's flow, wired into the real UI
     * here for the first time). Runs the loopback listener on Dispatchers.IO —
     * ServerSocket.accept() blocks — started *before* the browser opens, so the
     * redirect always has a listener waiting. `code` and `verifier` never appear in
     * a log or a Toast; only OAuthClient.Failure's own already-scrubbed message does.
     */
    private fun signInPersonal() {
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
                    toast("Sign-in failed or timed out")
                    return@launch
                }

                val record = withContext(Dispatchers.IO) {
                    oauthClient.exchange(code, challenge.verifier, redirectUri, challenge.state)
                }
                graph.store.save(Account.PERSONAL, record)
                toast("Personal sign-in complete")
                refreshNow()
            } catch (e: OAuthClient.Failure) {
                toast("Sign-in failed: ${e.message}")
            } catch (e: Exception) {
                toast("Sign-in failed")
            }
        }
    }

    private fun refreshNow() = lifecycleScope.launch {
        graph.repository.refresh()
        graph.bridge.update(StatePayload.encode(graph.repository.state.value))
    }

    private fun refreshStatus() {
        val s = graph.bridge.status()
        setStatusLine(statusRunning, "Shizuku running", s.running)
        setStatusLine(statusGranted, "Permission granted", s.granted)
        setStatusLine(
            statusDisplay, "Rear display", s.rearDisplayId >= 0,
            if (s.rearDisplayId >= 0) "id=${s.rearDisplayId}" else "not found",
        )
        renderRecovery(s)
    }

    private fun setStatusLine(
        view: TextView,
        label: String,
        satisfied: Boolean,
        detail: String = if (satisfied) "yes" else "no",
    ) {
        view.text = "$label: $detail"
        // The satisfied colour is the ramp's own 0%-utilisation stop — the same
        // green the rear panel itself uses to mean "healthy" — not a new literal.
        view.setTextColor(if (satisfied) GOOD_COLOR else Panel.Palette.DIM)
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

    private fun renderPreview(state: DashboardState) {
        val raw = StatePayload.encode(state)
        previewText.text = try {
            prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw))
        } catch (_: Exception) {
            raw
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
            "adb -s f940914b shell pm grant dev.rgarjoaba.claudestatus moe.shizuku.manager.permission.API_V23"
    }
}
