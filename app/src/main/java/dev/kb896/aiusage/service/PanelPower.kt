package dev.kb896.aiusage.service

/**
 * The rear display's power as last seen, and what a new reading changes about it.
 *
 * SEEDED FROM THE REAL STATE, and that is the whole reason this is a class. It used to start
 * as "off" whatever the display was, so after every start of the poll service the first
 * property change of a back that had been lit all along — and DisplayListener.onDisplayChanged
 * fires on every one, HyperOS dropping the rear from 120 to 60 Hz three seconds after the last
 * touch among them — read as the back waking up. That cleared the dozing flag and ran
 * supervision in the middle of a dim, as if somebody had lit the panel.
 */
class PanelPower(private var on: Boolean) {

    enum class Change {
        /** Dark to lit: the back woke, whoever woke it. */
        WOKE,
        /** Lit to dark. */
        WENT_DARK,
        /** A property change of a display whose power did not change. */
        NONE,
    }

    fun see(on: Boolean): Change {
        val was = this.on
        this.on = on
        return when {
            on && !was -> Change.WOKE
            !on && was -> Change.WENT_DARK
            else -> Change.NONE
        }
    }
}
