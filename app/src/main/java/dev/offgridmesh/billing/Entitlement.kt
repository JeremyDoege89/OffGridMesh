package dev.offgridmesh.billing

/** The one Play product (Build Guide §11): a non-consumable, one-time Pro unlock. */
object Products {
    const val PRO_UNLOCK = "pro_unlock"
}

enum class ProState { FREE, PENDING, PRO }

enum class PurchaseState { PURCHASED, PENDING, UNKNOWN }

/** A billing-library-free view of one purchase, so the rules below stay unit-testable. */
data class OwnedPurchase(
    val productIds: List<String>,
    val token: String,
    val state: PurchaseState,
    val acknowledged: Boolean,
)

/** What the paywall was opened for; drives its headline. Null request = opened from Settings. */
enum class ProFeature { NODES, MAP_PACKS, TTL, EXPORT }

/**
 * The free/Pro boundary (Build Guide §2): a capability boundary, never a timer. Gates live in the UI and
 * repositories — never in MeshService, so a billing problem can't stop an active mesh (§11).
 */
object Entitlements {
    /** Free: up to 8 concurrent nodes, this phone included. Deliberately generous; don't lower it (§2). */
    const val FREE_NODES = 8
    const val FREE_MAP_PACKS = 1
    /** Free TTL range is 1–3; Pro unlocks 4–5. */
    const val FREE_MAX_TTL = 3

    fun canAddMapPack(state: ProState, packs: Int) = state == ProState.PRO || packs < FREE_MAP_PACKS
    fun canUseTtl(state: ProState, ttl: Int) = state == ProState.PRO || ttl <= FREE_MAX_TTL

    /** Other members visible in rosters on the free tier (the cap counts this phone too). */
    fun visibleOthers(state: ProState): Int = if (state == ProState.PRO) Int.MAX_VALUE else FREE_NODES - 1

    private fun OwnedPurchase.isPro() = Products.PRO_UNLOCK in productIds

    /**
     * Pro only after PURCHASED + acknowledged (§10). A purchase that is paid but not yet
     * acknowledged, or still PENDING at the payment provider, shows as "payment pending".
     */
    fun resolve(purchases: List<OwnedPurchase>): ProState {
        val pro = purchases.filter { it.isPro() }
        return when {
            pro.any { it.state == PurchaseState.PURCHASED && it.acknowledged } -> ProState.PRO
            pro.any { it.state == PurchaseState.PENDING || it.state == PurchaseState.PURCHASED } -> ProState.PENDING
            else -> ProState.FREE
        }
    }

    /** Purchases this app must acknowledge itself — never left to Play Console (§10). */
    fun needingAcknowledgement(purchases: List<OwnedPurchase>): List<OwnedPurchase> =
        purchases.filter { it.isPro() && it.state == PurchaseState.PURCHASED && !it.acknowledged }

    /** Same token, now acknowledged: lets [resolve] grant Pro without a second round trip. */
    fun markAcknowledged(purchases: List<OwnedPurchase>, tokens: Set<String>): List<OwnedPurchase> =
        purchases.map { if (it.token in tokens) it.copy(acknowledged = true) else it }
}
