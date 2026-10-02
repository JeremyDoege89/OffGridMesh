package dev.offgridmesh.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Play Billing Library 9 (Build Guide §11). Serverless: purchases the library hands back are
 * already signature-checked by Play, which is as far as MVP goes against pirates.
 */
class PlayBillingGateway(context: Context) : BillingGateway {
    private val _updates = MutableSharedFlow<PurchaseUpdate>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val updates: Flow<PurchaseUpdate> = _updates.asSharedFlow()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            _updates.tryEmit(
                when (result.responseCode) {
                    BillingResponseCode.OK -> PurchaseUpdate.Purchased(purchases.orEmpty().map { it.toOwned() })
                    BillingResponseCode.USER_CANCELED -> PurchaseUpdate.Canceled
                    BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseUpdate.AlreadyOwned
                    else -> PurchaseUpdate.Failed(result.userMessage())
                },
            )
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private val connectLock = Mutex()
    private var proDetails: ProductDetails? = null

    /** Connects on first use; false when there's no Play Store, or it won't answer in time. */
    private suspend fun ready(): Boolean = connectLock.withLock {
        if (client.isReady) return true
        val done = CompletableDeferred<Boolean>()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingResponseCode.OK) Log.w(TAG, "setup: ${result.debugMessage}")
                done.complete(result.responseCode == BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() {
                done.complete(false) // auto-reconnection handles later calls
            }
        })
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { done.await() } ?: false
    }

    override suspend fun queryOwned(): List<OwnedPurchase>? {
        if (!ready()) return null
        val r = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        )
        if (r.billingResult.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "queryPurchases: ${r.billingResult.debugMessage}")
            return null
        }
        return r.purchasesList.map { it.toOwned() }
    }

    private suspend fun details(): ProductDetails? {
        proDetails?.let { return it }
        if (!ready()) return null
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(Products.PRO_UNLOCK)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val r = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build())
        if (r.billingResult.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "productDetails: ${r.billingResult.debugMessage}")
        }
        return r.productDetailsList.orEmpty().firstOrNull { it.productId == Products.PRO_UNLOCK }
            .also { proDetails = it }
    }

    override suspend fun proPrice(): String? = details()?.oneTimePurchaseOfferDetails?.formattedPrice

    override suspend fun launchPurchase(activity: Activity): LaunchResult {
        if (!ready()) return LaunchResult.Unavailable(OFFLINE)
        val d = details() ?: return LaunchResult.Unavailable("Pro isn't available from Google Play on this device yet.")
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d)
        d.oneTimePurchaseOfferDetails?.offerToken?.let { params.setOfferToken(it) }
        val r = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params.build())).build(),
        )
        return when (r.responseCode) {
            BillingResponseCode.OK -> LaunchResult.Started
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _updates.tryEmit(PurchaseUpdate.AlreadyOwned)
                LaunchResult.Started
            }
            else -> LaunchResult.Unavailable(r.userMessage())
        }
    }

    override suspend fun acknowledge(token: String): Boolean {
        if (!ready()) return false
        val r = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build())
        if (r.responseCode != BillingResponseCode.OK) Log.w(TAG, "acknowledge: ${r.responseCode} ${r.debugMessage}")
        return r.responseCode == BillingResponseCode.OK
    }

    private fun Purchase.toOwned() = OwnedPurchase(
        productIds = products,
        token = purchaseToken,
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            else -> PurchaseState.UNKNOWN
        },
        acknowledged = isAcknowledged,
    )

    private fun BillingResult.userMessage(): String = when (responseCode) {
        BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.SERVICE_DISCONNECTED,
        BillingResponseCode.SERVICE_TIMEOUT, BillingResponseCode.NETWORK_ERROR,
        -> OFFLINE
        BillingResponseCode.BILLING_UNAVAILABLE -> "Google Play billing isn't available on this device or account."
        BillingResponseCode.ITEM_UNAVAILABLE -> "Pro isn't available from Google Play right now."
        // Not "you weren't charged": a generic error can't promise that. Restore picks up a charge that did land.
        else -> "Google Play couldn't finish the purchase. Try again later, or tap Restore purchases if you were charged."
    }

    private companion object {
        const val TAG = "PlayBilling"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val OFFLINE = "Can't reach Google Play right now. The mesh keeps working; try again when you're online."
    }
}
