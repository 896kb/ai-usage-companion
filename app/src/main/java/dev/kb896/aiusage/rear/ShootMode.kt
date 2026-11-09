package dev.kb896.aiusage.rear

import dev.kb896.aiusage.BuildConfig

/**
 * DEBUG BUILDS ONLY: `adb shell setprop debug.magicrear.showcaptures 1` lets the layers that keep
 * themselves out of screenshots and recordings — the recording pill, the screenshot's polaroid —
 * into them, so they can be shot for the site (`screencap` sees only what a screenshot would).
 * "" or anything else: out again, as always. A release build never lets them in.
 */
object ShootMode {
    fun capturesShown(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.magicrear.showcaptures") as? String
        }.getOrNull()?.trim() == "1"
    }
}
