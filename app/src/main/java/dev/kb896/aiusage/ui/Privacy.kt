package dev.kb896.aiusage.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * What this app tells the user about their data, in one place: the notice shown once, the
 * link to the policy, and the few sentences said before each settings page or grant the
 * app sends them to.
 *
 * In one file rather than beside each button because these are promises, not labels. A
 * sentence about what is sent where has to stay true as the code changes, and a promise
 * scattered over a 2,000-line activity is one nobody re-reads when the code under it moves.
 * Anything here must match what the code does — the album in the lyrics request was left
 * out of the note under the switch once, and that is exactly the kind of drift this guards.
 */
object Privacy {

    /** The privacy policy, on the panel's site: the General tab's row and the notice. */
    const val POLICY_URL = "https://rear.896.ro/en/privacy/"

    /**
     * The notice's version. Bump it when [NOTICE] changes in a way someone who has read the
     * old one should see: an update that brings a new one shows it once more, and an update
     * that leaves this alone does not. Stored as the last version seen, not a "seen" flag,
     * so the first notice and every later one are the same mechanism.
     */
    const val NOTICE_VERSION = 1

    /**
     * Short on purpose: what leaves the phone, what stays, and what is not there at all.
     * The detail is in the policy, one button away.
     */
    const val NOTICE =
        "What leaves your phone:\n" +
            "• Your Claude usage and plan, read from Anthropic with your own Claude account — " +
            "and the sign-in itself, in your browser.\n" +
            "• A check for a new version, on GitLab.\n" +
            "• Only if you turn them on: the weather, from Open-Meteo (the place you type, or " +
            "your approximate location), and lyrics, from lrclib.net (the song's name, artist, " +
            "album and length).\n\n" +
            "What stays on it: your sign-in tokens, encrypted. Notifications shown on the back " +
            "are never stored or sent. Captures of the rear screen are kept in memory only.\n\n" +
            "No ads, no analytics, no trackers."

    /**
     * Under the two ways to connect an account (AccountSettingsSheet.chooseConnection),
     * which is the screen right before the browser opens claude.ai — so the note is there
     * rather than in one more dialog after the choice.
     */
    const val SIGN_IN =
        "Either way, the account's token is kept on this phone, encrypted, and used only to " +
            "read its usage and profile (plan, name, email) from Anthropic. The browser " +
            "sign-in happens on claude.ai."

    /**
     * Its own preferences file, not the panel config: "Reset every setting" puts the config
     * back to how it shipped, and having read the notice is not a setting.
     */
    private const val PREFS = "privacy"
    private const val KEY_SEEN = "notice_version_seen"

    /** Whether this install has not yet seen the notice as it now reads. */
    fun noticeDue(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SEEN, 0) < NOTICE_VERSION

    /**
     * Shows the notice. Closing it any way at all — OK, Back, a tap beside it — counts as
     * seen and runs [onDone]: it is information, not consent to anything, and a notice that
     * cannot be closed would be one more dialog standing between someone and the setup it is
     * in front of. "Privacy policy" opens the page and leaves the notice where it is, so
     * coming back from the browser finds it still there to be closed.
     */
    fun showNotice(context: Context, onDone: () -> Unit): AlertDialog {
        val dialog = Modal.show(Modal.builder(context)
            .setTitle("Your privacy")
            .setMessage(NOTICE)
            .setNeutralButton("Privacy policy", null)   // rewired below so it does not close
            .setPositiveButton("OK", null))
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { openPolicy(context) }
        dialog.setOnDismissListener {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_SEEN, NOTICE_VERSION).apply()
            onDone()
        }
        return dialog
    }

    /** The policy in the browser — the only copy of it; the app carries none. */
    fun openPolicy(context: Context) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(POLICY_URL))) }
            .onFailure { Toast.makeText(context, "Could not open $POLICY_URL", Toast.LENGTH_LONG).show() }
    }

    /**
     * What is said right before the app sends someone to a settings page or a grant: what
     * that access is for, and what does and does not leave the phone because of it.
     *
     * One or two sentences each, and exact. "Nothing is sent" appears only where nothing
     * is; where something is, it says what and to whom. The Shizuku text is the longest
     * because it is the widest grant — shell rights — and a list of what they are used for
     * is the only honest way to describe them.
     */
    enum class Access(val title: String, val text: String) {
        NOTIFICATIONS(
            "Allow notification access?",
            "With notification access the back can show other apps' notifications and control " +
                "what is playing. It shows a notification's app and title — the message text " +
                "only if you turn that on — and the back faces away from you, so people behind " +
                "you can read it; with \"match the front notifications\" on, swiping a card away " +
                "on the back clears it on the phone too. Notifications are never stored or sent " +
                "off the phone; what is playing leaves it only for lyrics, if you turn them on.",
        ),
        SHIZUKU(
            "About Shizuku",
            "Shizuku lets this app run a helper with Android's shell (adb) rights, which the " +
                "rear display needs. The helper puts the panel and the apps you pin on the " +
                "back and moves or closes them there, reads the rear touchscreen, captures the " +
                "rear screen in memory for the loading cover and the camera strip's colour, " +
                "reads which app is in front (for \"Send to back\"), sets the rear's brightness " +
                "and density, turns Wi-Fi, Bluetooth and mobile data on or off when you tap them " +
                "on the back, and hides Xiaomi's rear-screen app if you ask. It never captures " +
                "the front screen, saves no captures and makes no network connections.",
        ),
        BATTERY(
            "No battery restrictions",
            "Unrestricted, the service that keeps the panel current is not stopped in the " +
                "background. It checks your Claude usage every 2 minutes, and only while the " +
                "panel on the back is awake. This gives the app no access to anything else.",
        ),
        AUTOSTART(
            "Autostart",
            "Autostart lets HyperOS restart this app's service after a reboot, or after the " +
                "system has stopped it, so the panel comes back on its own. It gives the app " +
                "no access to anything else.",
        ),
        LOCATION(
            "Use the phone's location?",
            "For the weather, the phone's approximate location is read before each update — " +
                "every 5 minutes while the panel on the back is awake — and sent to Open-Meteo " +
                "for the forecast. It is not saved, and precise location is never asked for.",
        ),
        TILE(
            "Add the \"Send to back\" tile?",
            "Tapped in Quick Settings, the tile moves the app on this screen to the back, " +
                "through Shizuku. It reads only which app is in front, and nothing leaves " +
                "the phone.",
        ),
    }

    /**
     * [access]'s disclosure; Continue runs [open], the page or grant itself. [lead] goes
     * before the text — which feature asked, when there is one — and [next] after it — what
     * the next screen is, when that is not obvious. Cancel, Back or a tap beside it run
     * [onCancel]: a switch that was flipped on to get here has to go back off when the
     * person decides not to go on, or it would read as on with nothing behind it.
     */
    fun disclose(
        context: Context,
        access: Access,
        lead: String? = null,
        next: String? = null,
        onCancel: () -> Unit = {},
        open: () -> Unit,
    ) {
        var went = false
        val text = listOfNotNull(lead, access.text, next).joinToString("\n\n")
        val dialog = Modal.show(Modal.builder(context)
            .setTitle(access.title)
            .setMessage(text)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> went = true; open() })
        // On dismiss rather than on the Cancel button alone, so Back and a tap outside
        // decline the same way Cancel does.
        dialog.setOnDismissListener { if (!went) onCancel() }
    }
}
