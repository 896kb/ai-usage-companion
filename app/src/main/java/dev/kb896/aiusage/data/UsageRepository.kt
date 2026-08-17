package dev.kb896.aiusage.data

import android.util.Log
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
    /**
     * Optional so existing tests construct this unchanged, and so the plan tag is a
     * strictly additive feature: with these null the repository behaves exactly as
     * before and simply never reports a tier.
     */
    private val profileApi: ProfileApi? = null,
    private val profiles: ProfileStore? = null,
    private val history: UsageHistory? = null,
) {
    /** Set by the poll service so a completed refresh can be remembered across restarts. */
    var onFetchCompleted: (() -> Unit)? = null

    /**
     * Seeds state from the last persisted snapshot, without spending a request.
     *
     * A restart otherwise leaves the panel empty until the next poll — up to a full
     * interval of blankness for data we already had. Restoring means the panel renders
     * the last known values immediately and the next fetch still happens on the
     * original schedule.
     *
     * Only ever called before polling begins; it must not clobber fresher data.
     */
    fun restore(cached: DashboardState) {
        if (_state.value.statuses.isEmpty() && cached.statuses.isNotEmpty()) {
            _state.value = cached
        }
    }
    private val _state = MutableStateFlow(DashboardState.EMPTY)
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    /**
     * Minimum spacing between automatic refreshes — a structural floor, not a cadence.
     *
     * The cadence lives in Cadence.kt (5 min fast / 15 min slow); this is far below it
     * and never shapes normal polling. It exists because bunched requests have now
     * twice gotten an account 429'd, and both times the burst came from a caller that
     * looked harmless in isolation: first every service restart fetching immediately,
     * then a DisplayManager listener firing on every brightness and refresh-rate change
     * and fetching on each one — up to three requests a second.
     *
     * Rate was never the problem; bunching was. Fixing each trigger individually only
     * holds until the next trigger is added, so the invariant belongs here, at the one
     * point every request must pass through.
     */
    private var lastRefreshAtElapsed = 0L

    /**
     * @param force skips the burst floor. Reserved for the two moments a user has just
     * supplied a credential and is watching for it to take effect — an imported work
     * token and a completed personal sign-in. Never pass true from an automatic path.
     */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        val since = android.os.SystemClock.elapsedRealtime() - lastRefreshAtElapsed
        if (!force && lastRefreshAtElapsed != 0L && since < MIN_REFRESH_INTERVAL_MS) {
            Log.i(TAG, "refresh suppressed — only ${since / 1000}s since the last one")
            return@withContext
        }
        lastRefreshAtElapsed = android.os.SystemClock.elapsedRealtime()
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
                CallLog.record(account, "skipped, backoff ${(until - nowElapsed) / 1000}s")
                return AccountStatus(account, Health.STALE, last, profiles?.tier(account), moodStale(last))
            }
        }
        val stored = store.load(account)
            ?: run {
                Log.i(TAG, "${account.slug}: no stored credential")
                return AccountStatus(account, Health.AUTH, null, profiles?.tier(account), moodStale(null))
            }

        val now = System.currentTimeMillis()
        if (stored.refreshExpired(now)) {
            Log.w(TAG, "${account.slug}: refresh token expired")
            return AccountStatus(account, Health.AUTH, last, profiles?.tier(account), moodStale(last))
        }

        val usable = if (stored.accessExpired(now)) {
            try {
                oauth.refresh(stored.refreshToken).also { store.save(account, it) }
            } catch (e: Exception) {
                Log.w(TAG, "${account.slug}: token refresh failed: ${e.message}")
                CallLog.record(account, "refresh failed: ${e.message}")
                return AccountStatus(account, Health.AUTH, last, profiles?.tier(account), moodStale(last))
            }
        } else stored

        return try {
            val snapshot = api.fetch(usable.accessToken)
            CallLog.record(account, "ok")
            backoffUntil.remove(account)
            backoffStep.remove(account)
            AccountStatus(account, Health.OK, snapshot,
                planTier(account, usable.accessToken), moodFor(account, snapshot))
        } catch (e: UsageApi.Failure) {
            CallLog.record(account, e.message ?: e.kind.name)
            if (e.kind == UsageApi.Kind.RATE_LIMITED) applyBackoff(account, e.retryAfterSec)
            // Keep the last good numbers, but never present them as live.
            Log.w(TAG, "${account.slug}: usage fetch failed: ${e.message}")
            AccountStatus(account, healthFor(e.kind), last, profiles?.tier(account), moodStale(last))
        } catch (e: Exception) {
            // api.fetch is documented to only throw UsageApi.Failure, but that is an
            // implementation detail of a class this repository does not own. One
            // account's fetch must never be able to abort the sibling account's — see
            // the map over Account.entries in refresh(). Degrade this account only.
            Log.e(TAG, "${account.slug}: unexpected fetch error: ${e.javaClass.simpleName}")
            AccountStatus(account, Health.ERROR, last, profiles?.tier(account), moodStale(last))
        }
    }


    /**
     * Honours Retry-After when the server sends one, otherwise doubles from 5 minutes
     * up to an hour. Capped because a status panel showing hour-old numbers is still
     * useful, whereas one that never recovers is not.
     */
    /**
     * The account's plan tier, fetched at most once a week and otherwise read from
     * cache.
     *
     * Called only on the SUCCESS path, deliberately. A usage fetch that just failed is
     * the worst moment to spend another request on decoration — if the account is
     * rate-limited, the profile call is one more request against an endpoint family
     * that is already refusing, and if the token is bad this would fail too. Failure
     * paths reuse whatever tier is already cached.
     */
    private fun planTier(account: Account, accessToken: String): String? {
        val store = profiles ?: return null
        val api = profileApi ?: return null
        val now = System.currentTimeMillis()
        if (!store.isStale(account, now)) return store.tier(account)
        val fetched = api.tier(accessToken)
        if (fetched != null) {
            store.save(account, fetched, now)
            Log.i(TAG, "${account.slug}: plan tier $fetched")
        } else {
            // Do not retry on the next poll; see ProfileStore.markAttempted.
            store.markAttempted(account, now)
        }
        return fetched ?: store.tier(account)
    }

    /**
     * Records this reading and picks the account's mascot.
     *
     * Only on the SUCCESS path: a degraded fetch reuses the previous snapshot, and
     * recording that again would fabricate a flat trail — ten minutes of "no burn"
     * assembled from one real reading repeated, which would silently stop the lifting
     * animation whenever the network was unreliable.
     */
    /**
     * Mood for an account whose fetch DEGRADED, from the snapshot it is still showing.
     *
     * Only the value-based outcomes are available here — an empty window celebrates, a
     * spent one flies the flag. The burst deliberately cannot fire: it compares against
     * a baseline, and a degraded account is re-showing an old reading, so any delta
     * measured against it is between a number and itself.
     *
     * Skipping this entirely was the previous behaviour and it was wrong on screen: the
     * panel still DISPLAYS a degraded account's last numbers, so leaving the mascot at
     * its default meant a bar reading 0% with a mascot that was not celebrating —
     * exactly the inconsistency a reader would take as a rendering bug rather than as
     * stale data.
     */
    private fun moodStale(last: UsageSnapshot?): String {
        val session = last?.windows?.firstOrNull { it.kind == WindowKind.SESSION }
            ?: return MascotMood.WALK
        return MascotMood.of(
            session.utilization, null, MascotMood.allSpent(last.windows.map { it.utilization }))
    }

    private fun moodFor(account: Account, snapshot: UsageSnapshot): String {
        val session = snapshot.windows.firstOrNull { it.kind == WindowKind.SESSION }
            ?: return MascotMood.WALK
        val h = history ?: return MascotMood.of(session.utilization, null)
        val now = System.currentTimeMillis()
        val earlier = h.before(account, MascotMood.WINDOW_MS, now)
        h.record(account, session.utilization, now)
        val spent = MascotMood.allSpent(snapshot.windows.map { it.utilization })
        val decision =
            MascotMood.decide(session.utilization, earlier, h.holdUntil(account), now, spent)
        h.saveHoldUntil(account, decision.holdUntil)
        return decision.clip
    }

    private fun applyBackoff(account: Account, retryAfterSec: Long?) {
        val step = retryAfterSec?.times(1_000L)
            ?: ((backoffStep[account]?.times(2) ?: FIRST_BACKOFF_MS).coerceAtMost(MAX_BACKOFF_MS))
        backoffStep[account] = step
        backoffUntil[account] = android.os.SystemClock.elapsedRealtime() + step
        Log.w(TAG, "${account.slug}: rate limited, backing off ${step / 1000}s")
    }

    companion object {
        /** First backoff after a 429, when the server sends no Retry-After. */
        /**
         * Burst floor. Must stay BELOW Cadence.FAST_MS or it suppresses the very polls
         * it is meant to protect — at 60s against a 60s cadence, jitter alone would
         * drop every other tick and halve the effective rate silently.
         *
         * It exists to stop BUNCHING, not to set the rate: both 429s this app has taken
         * came from many requests in seconds, which this still refuses.
         */
        const val MIN_REFRESH_INTERVAL_MS = 90_000L
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
