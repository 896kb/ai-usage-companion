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
     * How long an unlock lasts.
     *
     * Cleared on background and on screen-off too — see [lock]. This is the ceiling for
     * the case where neither happens: the phone stays awake on the settings screen while
     * someone else picks it up.
     */
    private const val WINDOW_MS = 2 * 60 * 1000L

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

    /** Forget the unlock: on leaving the screen, on backgrounding, on screen-off. */
    fun lock() {
        unlockedUntil = 0L
    }

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
        val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            grant()
            onResult(true)
            return
        }
        // Wrapped, because this call reaches a system service that can refuse for
        // reasons this app cannot see — a missing permission took it down once, and a
        // settings screen must not die trying to hide a name.
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
                        grant()
                        onResult(true)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence?) {
                        onResult(false)
                    }
                },
            )
        } catch (e: Exception) {
            android.util.Log.w("AiUsageUnlock", "credential prompt unavailable", e)
            onResult(false)
        }
    }

    private fun grant() {
        unlockedUntil = SystemClock.elapsedRealtime() + WINDOW_MS
    }
}
