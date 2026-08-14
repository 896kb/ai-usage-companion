package dev.rgarjoaba.claudestatus.data

import android.util.Log
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
    /** Set by the poll service so a completed refresh can be remembered across restarts. */
    var onFetchCompleted: (() -> Unit)? = null
    private val _state = MutableStateFlow(DashboardState.EMPTY)
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val previous = _state.value.statuses.associateBy { it.account }
        val statuses = Account.entries.map { account ->
            fetchOne(account, previous[account]?.snapshot)
        }
        _state.value = DashboardState(System.currentTimeMillis(), statuses)
        onFetchCompleted?.invoke()
    }

    /**
     * Earliest elapsed-time at which each account may be fetched again. Per-account
     * deliberately: one account being throttled must not stop the other refreshing.
     */
    private val backoffUntil = mutableMapOf<Account, Long>()
    private val backoffStep = mutableMapOf<Account, Long>()

    private fun fetchOne(account: Account, last: UsageSnapshot?): AccountStatus {
        // Respect an active backoff before spending a request. Without this a 429 is
        // answered by polling again at the same cadence, which keeps the account
        // throttled indefinitely rather than letting it recover — exactly what
        // happened to the work account.
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        backoffUntil[account]?.let { until ->
            if (nowElapsed < until) {
                Log.i(TAG, "${account.slug}: backing off ${(until - nowElapsed) / 1000}s more")
                return AccountStatus(account, Health.STALE, last)
            }
        }
        val stored = store.load(account)
            ?: run {
                Log.i(TAG, "${account.slug}: no stored credential")
                return AccountStatus(account, Health.AUTH, null)
            }

        val now = System.currentTimeMillis()
        if (stored.refreshExpired(now)) {
            Log.w(TAG, "${account.slug}: refresh token expired")
            return AccountStatus(account, Health.AUTH, last)
        }

        val usable = if (stored.accessExpired(now)) {
            try {
                oauth.refresh(stored.refreshToken).also { store.save(account, it) }
            } catch (e: Exception) {
                Log.w(TAG, "${account.slug}: token refresh failed: ${e.message}")
                return AccountStatus(account, Health.AUTH, last)
            }
        } else stored

        return try {
            val snapshot = api.fetch(usable.accessToken)
            backoffUntil.remove(account)
            backoffStep.remove(account)
            AccountStatus(account, Health.OK, snapshot)
        } catch (e: UsageApi.Failure) {
            if (e.kind == UsageApi.Kind.RATE_LIMITED) applyBackoff(account, e.retryAfterSec)
            // Keep the last good numbers, but never present them as live.
            Log.w(TAG, "${account.slug}: usage fetch failed: ${e.message}")
            AccountStatus(account, healthFor(e.kind), last)
        } catch (e: Exception) {
            // api.fetch is documented to only throw UsageApi.Failure, but that is an
            // implementation detail of a class this repository does not own. One
            // account's fetch must never be able to abort the sibling account's — see
            // the map over Account.entries in refresh(). Degrade this account only.
            Log.e(TAG, "${account.slug}: unexpected fetch error: ${e.javaClass.simpleName}")
            AccountStatus(account, Health.ERROR, last)
        }
    }


    /**
     * Honours Retry-After when the server sends one, otherwise doubles from 5 minutes
     * up to an hour. Capped because a status panel showing hour-old numbers is still
     * useful, whereas one that never recovers is not.
     */
    private fun applyBackoff(account: Account, retryAfterSec: Long?) {
        val step = retryAfterSec?.times(1_000L)
            ?: ((backoffStep[account]?.times(2) ?: FIRST_BACKOFF_MS).coerceAtMost(MAX_BACKOFF_MS))
        backoffStep[account] = step
        backoffUntil[account] = android.os.SystemClock.elapsedRealtime() + step
        Log.w(TAG, "${account.slug}: rate limited, backing off ${step / 1000}s")
    }

    companion object {
        /** First backoff after a 429, when the server sends no Retry-After. */
        const val FIRST_BACKOFF_MS = 300_000L

        /** Ceiling on the doubling. Hour-old numbers still inform; never recovering does not. */
        const val MAX_BACKOFF_MS = 3_600_000L

        /**
         * Failure messages only — UsageApi.Failure and OAuthClient.Failure are both
         * built from status codes, exception class names and static strings, never
         * from a token or a response body. Without this a dimmed panel gives the user
         * no way at all to find out why.
         */
        const val TAG = "ClaudeStatusRepo"

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
