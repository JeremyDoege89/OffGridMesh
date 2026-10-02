package dev.offgridmesh.billing

import android.app.Activity

import kotlinx.coroutines.flow.Flow

/** Result of the purchase listener, mapped off the billing library's response codes. */
sealed interface PurchaseUpdate {
    data class Purchased(val purchases: List<OwnedPurchase>) : PurchaseUpdate
    data object Canceled : PurchaseUpdate
    data object AlreadyOwned : PurchaseUpdate
    data class Failed(val message: String) : PurchaseUpdate
}

sealed interface LaunchResult {
    data object Started : LaunchResult
    data class Unavailable(val message: String) : LaunchResult
}

/**
 * The seam between the app and Google Play. [PlayBillingGateway] is the real one;
 * [FakeBillingGateway] lets debug builds walk every purchase path on a sideloaded APK,
 * which Play itself refuses to sell to (the app has to be on a test track for that).
 */
interface BillingGateway {
    /** Everything the purchase listener reports, including purchases finished outside the app. */
    val updates: Flow<PurchaseUpdate>

    /** Every purchase Play knows about, or null if Play couldn't be reached (offline, no Play Store). */
    suspend fun queryOwned(): List<OwnedPurchase>?

    /** Localised Pro price ("$9.99"), or null when Play has no product details to give. */
    suspend fun proPrice(): String?

    suspend fun launchPurchase(activity: Activity): LaunchResult

    suspend fun acknowledge(token: String): Boolean
}
