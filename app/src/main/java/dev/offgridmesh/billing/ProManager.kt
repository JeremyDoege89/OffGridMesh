package dev.offgridmesh.billing

import android.app.Activity
import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Offline copy of the last known entitlement (DataStore in the app, in-memory in tests). */
interface EntitlementCache {
    suspend fun load(): ProState
    suspend fun save(state: ProState)
}

data class ProStatus(
    val state: ProState = ProState.FREE,
    /** Localised price from Play; the paywall falls back to the list price without it. */
    val price: String? = null,
    /** False until the cache has been read, so gates don't flash "free" for a Pro user. */
    val loaded: Boolean = false,
) {
    val isPro get() = state == ProState.PRO
}

/** One-shot outcomes the UI reports (snackbar / paywall text). */
sealed interface ProEvent {
    data object Unlocked : ProEvent
    data object Pending : ProEvent
    data class Failed(val message: String) : ProEvent
    data class Restored(val state: ProState) : ProEvent
    data object RestoreOffline : ProEvent
}

/**
 * Owns the Pro entitlement (Build Guide §11):
 *  - cache first, so Pro works offline from the first frame;
 *  - then Play is the source of truth: a successful query replaces the cache (refunds revoke);
 *  - every PURCHASED-but-unacknowledged purchase is acknowledged here, on every pass, so a
 *    failed acknowledgement retries on the next cold start instead of being refunded in 3 days.
 */
class ProManager(
    private val gateway: BillingGateway,
    private val cache: EntitlementCache,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(ProStatus())
    val status: StateFlow<ProStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<ProEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ProEvent> = _events.asSharedFlow()

    private val lock = Mutex()

    fun start() {
        scope.launch {
            val cached = cache.load()
            _status.update { it.copy(state = cached, loaded = true) }
            scope.launch { gateway.updates.collect { onUpdate(it) } }
            refresh()
            gateway.proPrice()?.let { p -> _status.update { it.copy(price = p) } }
        }
    }

    /** Re-query Play. Returns false (and keeps the cached state) when Play can't be reached. */
    suspend fun refresh(): Boolean {
        val owned = gateway.queryOwned() ?: return false
        apply(owned)
        return true
    }

    suspend fun buy(activity: Activity) {
        if (_status.value.price == null) {
            gateway.proPrice()?.let { p -> _status.update { it.copy(price = p) } }
        }
        when (val r = gateway.launchPurchase(activity)) {
            LaunchResult.Started -> Unit // the listener reports the outcome
            is LaunchResult.Unavailable -> _events.emit(ProEvent.Failed(r.message))
        }
    }

    suspend fun restore() {
        val before = _status.value.state
        if (refresh()) {
            val now = _status.value.state
            // apply() already announced a fresh unlock; only report the "nothing changed" outcomes.
            if (!(before != ProState.PRO && now == ProState.PRO)) _events.emit(ProEvent.Restored(now))
        } else {
            _events.emit(ProEvent.RestoreOffline)
        }
    }

    private suspend fun onUpdate(u: PurchaseUpdate) {
        when (u) {
            is PurchaseUpdate.Purchased -> {
                // The listener only lists what just changed; a full query is the whole truth.
                if (!refresh()) apply(u.purchases, authoritative = false)
            }
            PurchaseUpdate.AlreadyOwned -> refresh()
            PurchaseUpdate.Canceled -> Unit // "Not now" is a valid answer; say nothing
            is PurchaseUpdate.Failed -> _events.emit(ProEvent.Failed(u.message))
        }
    }

    /**
     * [authoritative]: the list is everything Play knows (a query), so it may also take Pro away.
     * Otherwise it's a partial listener batch and can only move the state forward.
     */
    private suspend fun apply(purchases: List<OwnedPurchase>, authoritative: Boolean = true) = lock.withLock {
        val acked = Entitlements.needingAcknowledgement(purchases)
            .filter { p ->
                gateway.acknowledge(p.token).also { ok ->
                    // Log, don't crash: the next cold start retries (§10).
                    if (!ok) Log.w(TAG, "acknowledge failed; will retry on next refresh")
                }
            }
            .map { it.token }.toSet()
        val resolved = Entitlements.resolve(Entitlements.markAcknowledged(purchases, acked))
        val before = _status.value.state
        val next = if (authoritative) resolved else maxOf(before, resolved)
        if (next != before) {
            _status.update { it.copy(state = next) }
            cache.save(next)
            when (next) {
                ProState.PRO -> _events.emit(ProEvent.Unlocked)
                ProState.PENDING -> _events.emit(ProEvent.Pending)
                ProState.FREE -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "ProManager"
    }
}
