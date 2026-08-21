package dev.kb896.aiusage.ui

import android.view.ViewGroup
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.PanelConfig

/**
 * Release build: diagnostics do not exist.
 *
 * Same signature as the debug implementation in `src/debug`, so MainActivity needs no
 * conditional and no BuildConfig check — the variant decides, and the debug-only views,
 * MascotStripView and the JSON dump are simply not compiled into this build.
 */
object Diagnostics {
    fun install(slot: ViewGroup, onReset: () -> Unit = {}) = Unit
    fun render(state: DashboardState, config: PanelConfig) = Unit
}
