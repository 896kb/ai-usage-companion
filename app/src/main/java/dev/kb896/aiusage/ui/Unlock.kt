package dev.kb896.aiusage.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.CancellationSignal
import android.os.SystemClock

/**
 * A device-credential gate in front of an account's identity.
 *
 * The panel deliberately never carries a name, an e-mail or an organisation — it faces
 * outward and anyone behind the user can read it. The settings screen shows them, because
 * telling two accounts apart is the whole reason that data is fetched. But "in the user's
 * hand" is not the same as "only the user": a phone gets passed across a desk unlocked.
 *
 * So the same standard a banking app uses, and for the same reason — the screen being
 * open is not evidence that the person looking at it is the owner.
 *
 * BiometricPrompt via KeyguardManager rather than androidx.biometric: this needs the
 * device credential, biometrics included, which is exactly what createConfirmDeviceCredentialIntent
 * offers, and it adds no dependency to a 1.4MB release for one dialog.
 */
object Unlock {

    /**
     * How long an unlock lasts, at most.
     *
     * Thirty seconds, at the user's direction. It is long enough to read a name and an
     * organisation and short enough that a phone put down mid-screen does not sit there
     * displaying them — which is the whole failure this gate addresses, and the one case
     * the other triggers miss: no lock, no hide, no navigation, just a phone left face-up
     * on a desk.
     *
     * The other triggers — backgrounding, screen-off, leaving the account — all still
     * fire earlier when they happen. This is the ceiling when none of them does.
     */
    const val WINDOW_MS = 30 * 1000L

    private var unlockedUntil = 0L

    fun isUnlocked(): Boolean = SystemClock.elapsedRealtime() < unlockedUntil

    /**
     * Re-locks when the screen goes off, and tells the caller so it can repaint.
     *
     * onPause is not enough on its own. A screen lock while this app is foreground does
     * not reliably pause the activity on every device — and the screen going dark with
     * a name still on it, waiting to be revealed again by whoever wakes the phone, is
     * exactly the case this gate exists for.
     *
     * @return a registered receiver the caller must unregister
     */
    fun observeScreenOff(context: Context, onLocked: () -> Unit): android.content.BroadcastReceiver {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                lock()
                onLocked()
            }
        }
        context.registerReceiver(receiver,
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
        return receiver
    }

    /**
     * What is left of the window, for a caller re-arming its own timer.
     *
     * Rebuilding the rows must not extend the unlock: an edit that happens to rebuild
     * them every keystroke would otherwise hold the details open indefinitely.
     */
    fun remainingMs(): Long = (unlockedUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    /**
     * Forget the unlock: on leaving the screen, on backgrounding, on screen-off — and tell
     * whoever is showing what it unlocked ([onLock]), so nothing stays on the screen that the
     * lock no longer covers.
     */
    fun lock() {
        unlockedUntil = 0L
        lockListeners.toList().forEach { it() }
    }

    /**
     * [lock], for the app going to the background — unless the unlock is only just given.
     *
     * The prompt is the system's, and on some phones putting it up or taking it down pauses the
     * activity under it. A pause landing just after the prompt succeeded would lock again at
     * once, hide what was just shown, and the next tap would ask for the code a second time.
     * Not seen on the phone this was written on; guarded because the cost of being wrong is
     * that loop, and the cost of the guard is [SETTLE_MS] in which a real backgrounding leaves
     * the window to run out on its own (WINDOW_MS, and the page's own timer).
     */
    fun lockForBackground() {
        if (SystemClock.elapsedRealtime() - grantedAt < SETTLE_MS) return
        lock()
    }

    private var grantedAt = -SETTLE_MS
    private const val SETTLE_MS = 800L

    private val lockListeners = LinkedHashSet<() -> Unit>()

    /**
     * [listener] is called at every [lock], on the thread that locks (the main one). Returns
     * what removes it again.
     *
     * A SCREEN THAT SHOWS UNLOCKED DETAILS MUST FOLLOW THE LOCK. The account page did not:
     * backgrounding the app locked (MainActivity.onPause) while the page kept the name on show
     * under a button reading "Hide" — and that button, asking the lock and not the screen,
     * took "locked" to mean "reveal" and put up the credential prompt. To the person holding
     * the phone that was the lock code asked for twice, to hide what the first had shown
     * (reported by users; found by reading, 2026-11-10).
     */
    fun onLock(listener: () -> Unit): () -> Unit {
        lockListeners += listener
        return { lockListeners -= listener }
    }

    /** A prompt is up and not yet answered: a second [require] is not a second prompt. */
    private var prompting = false

    /**
     * @param onResult true if the user authenticated, false if they cancelled or the
     *   device has no credential set at all.
     *
     * A device with NO lock screen authenticates trivially — there is nothing to prove.
     * That is reported as success rather than as failure: refusing to show an account
     * name on an unsecured phone protects nothing, since anything else on that phone is
     * equally open, and it would leave the user unable to use their own settings.
     */
    fun require(activity: Activity, reason: String, onResult: (Boolean) -> Unit) {
        if (isUnlocked()) {
            onResult(true)
            return
        }
        // ONE PROMPT AT A TIME. A second tap before the first prompt was up started a second
        // one: the system drops the first for it, or on some phones stacks them, and either
        // way the code is asked for again.
        if (prompting) return
        val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            grant()
            onResult(true)
            return
        }
        // Wrapped, because this call reaches a system service that can refuse for
        // reasons this app cannot see — a missing permission took it down once, and a
        // settings screen must not die trying to hide a name.
        prompting = true
        try {
        android.hardware.biometrics.BiometricPrompt.Builder(activity)
            .setTitle("Confirm it's you")
            .setSubtitle(reason)
            .setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
            .authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?
                    ) {
                        prompting = false
                        grant()
                        onResult(true)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence?) {
                        prompting = false
                        onResult(false)
                    }
                },
            )
        } catch (e: Exception) {
            android.util.Log.w("AiUsageUnlock", "credential prompt unavailable", e)
            prompting = false
            onResult(false)
        }
    }

    private fun grant() {
        grantedAt = SystemClock.elapsedRealtime()
        unlockedUntil = SystemClock.elapsedRealtime() + WINDOW_MS
    }
}
