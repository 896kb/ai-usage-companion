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
import dev.kb896.aiusage.rear.ShizukuBridge
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
    }

    companion object {
        @Volatile private var instance: Graph? = null
        fun of(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context).also { instance = it } }
    }
}
