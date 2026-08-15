package dev.rgarjoaba.claudestatus.service

import android.content.Context
import dev.rgarjoaba.claudestatus.auth.OAuthClient
import dev.rgarjoaba.claudestatus.auth.TokenStore
import dev.rgarjoaba.claudestatus.data.ProfileApi
import dev.rgarjoaba.claudestatus.data.ProfileStore
import dev.rgarjoaba.claudestatus.data.UsageApi
import dev.rgarjoaba.claudestatus.data.UsageHistory
import dev.rgarjoaba.claudestatus.data.UsageRepository
import dev.rgarjoaba.claudestatus.rear.ShizukuBridge
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
    val store = TokenStore(context.applicationContext)
    val repository = UsageRepository(
        UsageApi(http), OAuthClient(http), store,
        ProfileApi(http), ProfileStore(context.applicationContext),
        UsageHistory(context.applicationContext),
    )
    val bridge = ShizukuBridge(context.applicationContext)

    companion object {
        @Volatile private var instance: Graph? = null
        fun of(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context).also { instance = it } }
    }
}
