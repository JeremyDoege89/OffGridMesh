package dev.offgridmesh.billing

import android.app.Activity




import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProManagerTest {
    private class MemCache(var value: ProState = ProState.FREE) : EntitlementCache {
        override suspend fun load() = value
        override suspend fun save(state: ProState) { value = state }
    }

    /** "Play": [owned] is what queries return; null owned list = unreachable. */
    private class FakePlay : BillingGateway {
        var owned: MutableList<OwnedPurchase>? = mutableListOf()
        var ackWorks = true
        val acked = mutableListOf<String>()
        override val updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8)
        override suspend fun queryOwned() = owned?.toList()
        override suspend fun proPrice() = "$9.99"
        override suspend fun launchPurchase(activity: Activity) = LaunchResult.Started
        override suspend fun acknowledge(token: String): Boolean {
            if (!ackWorks) return false
            acked += token
            owned = owned?.map { if (it.token == token) it.copy(acknowledged = true) else it }?.toMutableList()
            return true
        }
    }

    private fun pro(state: PurchaseState, acked: Boolean) =
        OwnedPurchase(listOf(Products.PRO_UNLOCK), "tok", state, acked)

    private fun TestScope.manager(play: FakePlay, cache: MemCache): Pair<ProManager, MutableList<ProEvent>> {
        val m = ProManager(play, cache, backgroundScope)
        val events = mutableListOf<ProEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { events += it } }
        m.start()
        return m to events
    }

    @Test fun `cold start restores an owned purchase on a fresh install`() = runTest(UnconfinedTestDispatcher()) {
        val play = FakePlay().apply { owned = mutableListOf(pro(PurchaseState.PURCHASED, acked = true)) }
        val cache = MemCache(ProState.FREE) // reinstall: cache wiped
        val (m, _) = manager(play, cache)
        assertEquals(ProState.PRO, m.status.value.state)
        assertEquals(ProState.PRO, cache.value)
        assertEquals("$9.99", m.status.value.price)
    }

    @Test fun `offline keeps the cached entitlement`() = runTest(UnconfinedTestDispatcher()) {
        val play = FakePlay().apply { owned = null }
        val (m, _) = manager(play, MemCache(ProState.PRO))
        assertEquals(ProState.PRO, m.status.value.state)
        assertTrue(m.status.value.loaded)
    }

    @Test fun `purchase is acknowledged by the app before pro is granted`() = runTest(UnconfinedTestDispatcher()) {
        val play = FakePlay()
        val cache = MemCache()
        val (m, events) = manager(play, cache)
        assertEquals(ProState.FREE, m.status.value.state)

        play.owned = mutableListOf(pro(PurchaseState.PURCHASED, acked = false))
        play.updates.emit(PurchaseUpdate.Purchased(play.owned!!))

        assertEquals(listOf("tok"), play.acked)
        assertEquals(ProState.PRO, m.status.value.state)
        assertEquals(ProState.PRO, cache.value)
        assertEquals(listOf<ProEvent>(ProEvent.Unlocked), events)
    }

    @Test fun `failed acknowledgement stays pending and retries on next refresh`() = runTest(UnconfinedTestDispatcher()) {
        val play = FakePlay().apply { ackWorks = false; owned = mutableListOf(pro(PurchaseState.PURCHASED, acked = false)) }
        val (m, _) = manager(play, MemCache())
        assertEquals(ProState.PENDING, m.status.value.state)

        play.ackWorks = true
        m.refresh()
        assertEquals(listOf("tok"), play.acked)
        assertEquals(ProState.PRO, m.status.value.state)
    }

    @Test fun `pending payment unlocks when it completes`() = runTest(UnconfinedTestDispatcher()) {
        val play = FakePlay()
        val (m, events) = manager(play, MemCache())
        play.owned = mutableListOf(pro(PurchaseState.PENDING, acked = false))
        play.updates.emit(PurchaseUpdate.Purchased(play.owned!!))
        assertEquals(ProState.PENDING, m.status.value.state)
        assertTrue(play.acked.isEmpty()) // never acknowledge a pending purchase

        play.owned = mutableListOf(pro(PurchaseState.PURCHASED, acked = false))
        play.updates.emit(PurchaseUpdate.Purchased(play.owned!!))
        assertEquals(ProState.PRO, m.status.value.state)
        assertEquals(listOf(ProEvent.Pending, ProEvent.Unlocked), events)
    }

    @Test fun `refund revokes on the next successful check`() = runTest(UnconfinedTestDispatcher()) {
        val play = FakePlay().apply { owned = mutableListOf(pro(PurchaseState.PURCHASED, acked = true)) }
        val cache = MemCache(ProState.PRO)
        val (m, _) = manager(play, cache)
        play.owned = mutableListOf()
        m.refresh()
        assertEquals(ProState.FREE, m.status.value.state)
        assertEquals(ProState.FREE, cache.value)
    }

    @Test fun `listener batch can't revoke when play is unreachable`() = runTest(UnconfinedTestDispatcher()) {
        val play = FakePlay().apply { owned = null }
        val (m, _) = manager(play, MemCache(ProState.PRO))
        play.updates.emit(PurchaseUpdate.Purchased(emptyList()))
        assertEquals(ProState.PRO, m.status.value.state)
    }

    @Test fun `cancel changes nothing and says nothing`() = runTest(UnconfinedTestDispatcher()) {
        val play = FakePlay()
        val (m, events) = manager(play, MemCache())
        play.updates.emit(PurchaseUpdate.Canceled)
        assertEquals(ProState.FREE, m.status.value.state)
        assertTrue(events.isEmpty())
    }

    @Test fun `errors surface as a message`() = runTest(UnconfinedTestDispatcher()) {
        val play = FakePlay()
        val (_, events) = manager(play, MemCache())
        play.updates.emit(PurchaseUpdate.Failed("nope"))
        assertEquals(listOf<ProEvent>(ProEvent.Failed("nope")), events)
    }

    @Test fun `restore reports the outcome`() = runTest(UnconfinedTestDispatcher()) {
        val play = FakePlay()
        val (m, events) = manager(play, MemCache())
        m.restore()
        play.owned = null
        m.restore()
        assertEquals(listOf(ProEvent.Restored(ProState.FREE), ProEvent.RestoreOffline), events)
    }
}
