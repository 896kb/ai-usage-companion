package dev.kb896.aiusage.service

/**
 * Whether locking the front lights the back again, into the panel's always-on.
 *
 * HyperOS sleeps both displays on the power button, so with always-on switched on the back is
 * woken a moment after the lock and the panel, woken with always-on on, dims into it. That
 * relight asked only whether the back went dark WITH the front — and so it also lit whatever
 * else was on the back at the lock:
 *
 *  - a GUEST app on top (App Portal), shown at full brightness on a locked phone, in a hand or
 *    a pocket, where the panel's always-on would have been a dimmed page under a slider;
 *  - a panel that was already letting the back go — dimming after a double tap, asleep in the
 *    pocket — which the relight woke straight back up.
 *
 * Both are read AT the lock, not when the relight is due: by then the sleep itself may have
 * changed them (the panel answers its display going dark). Not knowing either — a new app
 * process the panel has not spoken to yet, no service to ask — is no relight: a back left dark
 * is the safe way to be wrong on a locked phone.
 */
object LockRelight {
    fun relight(wentDarkTogether: Boolean, dozingAtLock: Boolean?, guestOnTopAtLock: Boolean?): Boolean =
        wentDarkTogether && dozingAtLock == false && guestOnTopAtLock == false
}
