package dev.kb896.aiusage.data

import dev.kb896.aiusage.auth.OAuthClient
import dev.kb896.aiusage.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Orchestrates a refresh of both accounts' usage. Owns no wire format (that's
 * UsageApi/UsageParser) and no crypto (that's TokenStore) — this class only decides,
 * per account, what Health the failure or success maps to, and keeps the last good
 * snapshot around when a fetch degrades. Each account is independent: one account's
 * failure never touches the other's status.
 */
class UsageRepository(
    private val api: UsageApi,
    private val oauth: OAuthClient,
    private val store: TokenStore,
) {
    private val _state = MutableStateFlow(DashboardState.EMPTY)
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val previous = _state.value.statuses.associateBy { it.account }
        val statuses = Account.entries.map { account ->
            fetchOne(account, previous[account]?.snapshot)
        }
        _state.value = DashboardState(System.currentTimeMillis(), statuses)
    }

    private fun fetchOne(account: Account, last: UsageSnapshot?): AccountStatus {
        val stored = store.load(account)
            ?: return AccountStatus(account, Health.AUTH, null)

        val now = System.currentTimeMillis()
        if (stored.refreshExpired(now)) return AccountStatus(account, Health.AUTH, last)

        val usable = if (stored.accessExpired(now)) {
            try {
                oauth.refresh(stored.refreshToken).also { store.save(account, it) }
            } catch (_: Exception) {
                return AccountStatus(account, Health.AUTH, last)
            }
        } else stored

        return try {
            AccountStatus(account, Health.OK, api.fetch(usable.accessToken))
        } catch (e: UsageApi.Failure) {
            when (e.kind) {
                // Keep the last good numbers, but never present them as live.
                UsageApi.Kind.UNAUTHORIZED -> AccountStatus(account, Health.AUTH, last)
                UsageApi.Kind.RATE_LIMITED,
                UsageApi.Kind.NETWORK -> AccountStatus(account, Health.STALE, last)
                UsageApi.Kind.SERVER -> AccountStatus(account, Health.ERROR, last)
            }
        }
    }
}
