package dev.rgarjoaba.claudestatus.data

import dev.rgarjoaba.claudestatus.auth.OAuthClient
import dev.rgarjoaba.claudestatus.auth.TokenStore
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
            // Keep the last good numbers, but never present them as live.
            AccountStatus(account, healthFor(e.kind), last)
        } catch (e: Exception) {
            // api.fetch is documented to only throw UsageApi.Failure, but that is an
            // implementation detail of a class this repository does not own. One
            // account's fetch must never be able to abort the sibling account's — see
            // the map over Account.entries in refresh(). Degrade this account only.
            AccountStatus(account, Health.ERROR, last)
        }
    }

    companion object {
        /**
         * `when` over an enum class used as an expression is exhaustive by construction:
         * Kotlin refuses to compile this function if UsageApi.Kind gains a fifth entry
         * without a matching branch here (verified: temporarily removing the SERVER
         * branch produces "'when' expression must be exhaustive" at compile time — see
         * task-11-report.md). No `else` branch, so no new Kind can silently fall through.
         */
        internal fun healthFor(kind: UsageApi.Kind): Health = when (kind) {
            UsageApi.Kind.UNAUTHORIZED -> Health.AUTH
            UsageApi.Kind.RATE_LIMITED, UsageApi.Kind.NETWORK -> Health.STALE
            UsageApi.Kind.SERVER -> Health.ERROR
        }
    }
}
