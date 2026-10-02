package dev.offgridmesh.billing

import android.app.Activity
import android.content.Context
import androidx.core.content.edit

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Debug builds only (see MeshApp): stands in for Google Play so every billing path —
 * success, pending → completed, cancel, error, refund, reinstall-restore — can be walked on a
 * sideloaded APK. "Play's" side of the ledger lives in its own prefs file, separate from the
 * app's entitlement cache, so wiping the cache is a faithful reinstall.
 */
class FakeBillingGateway(context: Context) : BillingGateway {
    enum class Outcome { SUCCESS, PENDING, CANCEL, ERROR }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8)
    override val updates: Flow<PurchaseUpdate> = _updates.asSharedFlow()

    var nextOutcome: Outcome
        get() = runCatching { Outcome.valueOf(prefs.getString(K_NEXT, null)!!) }.getOrDefault(Outcome.SUCCESS)
        set(v) = prefs.edit { putString(K_NEXT, v.name) }

    /** "Play is unreachable": queries fail and purchases can't start. */
    var offline: Boolean
        get() = prefs.getBoolean(K_OFFLINE, false)
        set(v) = prefs.edit { putBoolean(K_OFFLINE, v) }

    private var owned: PurchaseState?
        get() = prefs.getString(K_OWNED, null)?.let(PurchaseState::valueOf)
        set(v) = prefs.edit { putString(K_OWNED, v?.name) }

    private var acked: Boolean
        get() = prefs.getBoolean(K_ACKED, false)
        set(v) = prefs.edit { putBoolean(K_ACKED, v) }

    private fun current(): List<OwnedPurchase> =
        owned?.let { listOf(OwnedPurchase(listOf(Products.PRO_UNLOCK), TOKEN, it, acked)) }.orEmpty()

    override suspend fun queryOwned(): List<OwnedPurchase>? = if (offline) null else current()

    override suspend fun proPrice(): String? = if (offline) null else "$9.99"

    override suspend fun launchPurchase(activity: Activity): LaunchResult {
        if (offline) return LaunchResult.Unavailable("Can't reach Google Play right now (fake offline).")
        if (owned != null) {
            _updates.emit(PurchaseUpdate.AlreadyOwned)
            return LaunchResult.Started
        }
        delay(600) // the Play sheet would be on screen here
        when (nextOutcome) {
            Outcome.SUCCESS -> { owned = PurchaseState.PURCHASED; acked = false; _updates.emit(PurchaseUpdate.Purchased(current())) }
            Outcome.PENDING -> { owned = PurchaseState.PENDING; acked = false; _updates.emit(PurchaseUpdate.Purchased(current())) }
            Outcome.CANCEL -> _updates.emit(PurchaseUpdate.Canceled)
            Outcome.ERROR -> _updates.emit(PurchaseUpdate.Failed("Fake billing error (test). Nothing was charged."))
        }
        return LaunchResult.Started
    }

    override suspend fun acknowledge(token: String): Boolean {
        if (offline || token != TOKEN || owned != PurchaseState.PURCHASED) return false
        acked = true
        return true
    }

    /** The slow payment method cleared; Play tells the app through the purchase listener. */
    suspend fun completePending() {
        if (owned != PurchaseState.PENDING) return
        owned = PurchaseState.PURCHASED
        _updates.emit(PurchaseUpdate.Purchased(current()))
    }

    /** Refunded / revoked in Play Console. The app only learns on its next query. */
    fun refund() {
        owned = null
        acked = false
    }

    fun describe(): String = buildString {
        append(owned?.let { "owned=${it.name.lowercase()}" + if (acked) ", acknowledged" else "" } ?: "not owned")
        append(" · next buy: ${nextOutcome.name.lowercase()}")
        if (offline) append(" · OFFLINE")
    }

    companion object {
        private const val PREFS = "fake_billing"
        private const val K_NEXT = "next_outcome"
        private const val K_OFFLINE = "offline"
        private const val K_OWNED = "owned"
        private const val K_ACKED = "acked"
        private const val TOKEN = "fake-token-pro"
        private const val K_ENABLED = "enabled"

        /** Debug builds start on the fake: a sideloaded APK can't buy from Play anyway. */
        fun enabled(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ENABLED, true)

        fun setEnabled(context: Context, on: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putBoolean(K_ENABLED, on) }
    }
}
