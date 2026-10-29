package dev.kb896.aiusage.service

import android.content.Context
import dev.kb896.aiusage.auth.OAuthClient
import dev.kb896.aiusage.auth.TokenStore
import dev.kb896.aiusage.data.ProfileApi
import dev.kb896.aiusage.data.ProfileStore
import dev.kb896.aiusage.data.UsageApi
import dev.kb896.aiusage.data.UsageHistory
import dev.kb896.aiusage.data.UsageRepository
import dev.kb896.aiusage.data.WeatherApi
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.StatePayload
import dev.kb896.aiusage.rear.DpiEdits
import dev.kb896.aiusage.rear.ShizukuBridge
import dev.kb896.aiusage.ui.PortalGuide
import okhttp3.OkHttpClient

/**
 * Hand-rolled singleton graph — the app is far too small for a DI framework.
 *
 * Constructed from whatever Context first calls [of]; only `context.applicationContext`
 * is ever retained (by [store] and [bridge]), so it makes no difference whether the
 * first caller passes an Activity or the Service itself — nothing here can leak one.
 */
class Graph private constructor(context: Context) {
    private val http = OkHttpClient()
    val weatherApi = WeatherApi(http)
    val store = TokenStore(context.applicationContext)
    val repository = UsageRepository(
        UsageApi(http), OAuthClient(http), store,
        ProfileApi(http), ProfileStore(context.applicationContext),
        UsageHistory(context.applicationContext),
    )
    val bridge = ShizukuBridge(context.applicationContext).also { b ->
        // the onboarding decides whether the panel may be placed at all (Setup.panelAllowed)
        b.gate = { status -> dev.kb896.aiusage.ui.Setup.panelAllowed(context.applicationContext, status) }
        // An app's DPI set on the back: saved as the front's own rows save it, and the
        // payload pushed, so the shell sees its value come back (DpiEdits). Only for a
        // pinned app — the back shows no others.
        b.onPortalDpi = { pkg, dpi ->
            val store = ConfigStore(context.applicationContext)
            val config = store.load()
            if (pkg in config.portalApps) {
                val next = config.copy(portalDpi = DpiEdits.applied(config.portalDpi, pkg, dpi))
                store.save(next)
                if (!b.calibrating) b.update(StatePayload.encode(repository.state.value, next, WeatherPoll.latest))
            }
        }
        // The sheet opened on the back: the Portal tab's last step ticks. The front's alone —
        // nothing on the back reads it, so no payload goes.
        b.onPortalOpened = {
            val store = ConfigStore(context.applicationContext)
            val config = store.load()
            if (!config.portalSheetSeen) store.save(PortalGuide.settled(config.copy(portalSheetSeen = true)))
        }
    }

    companion object {
        @Volatile private var instance: Graph? = null
        fun of(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context).also { instance = it } }
    }
}
