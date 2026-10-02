package dev.offgridmesh

import dev.offgridmesh.billing.ProState
import dev.offgridmesh.data.MemberStatusEntity
import dev.offgridmesh.data.Node
import dev.offgridmesh.mesh.MeshState
import dev.offgridmesh.sync.MemberState
import dev.offgridmesh.ui.activeNodeCount
import dev.offgridmesh.ui.buildRoster
import dev.offgridmesh.ui.capRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterTest {
    private val now = 1_000_000_000L
    private fun node(i: Int, ago: Long = 0) = Node("n$i", "N$i", now - ago, 0, null)

    @Test fun needHelpSortsFirstThenSelf() {
        val nodes = (1..3).map { node(it) }
        val help = MemberStatusEntity("n3", "N3", MemberState.NEED_HELP, 5, "ankle", null, null)
        val r = buildRoster("me", "Me", nodes, listOf(help), MeshState(), now)
        assertEquals(listOf("n3", "me"), r.take(2).map { it.deviceId })
    }

    @Test fun offlineIsDerivedNotStored() {
        val r = buildRoster("me", "Me", listOf(node(1, ago = 11 * 60_000)), emptyList(), MeshState(), now)
        assertTrue(r.first { it.deviceId == "n1" }.offline)
        assertEquals(1, activeNodeCount(r))
    }

    @Test fun freeTierShowsEightButNeverHidesAHelpCall() {
        val nodes = (1..12).map { node(it, ago = it * 1_000L) }
        val help = MemberStatusEntity("n12", "N12", MemberState.NEED_HELP, 5, null, null, null)
        val (visible, hidden) = capRoster(buildRoster("me", "Me", nodes, listOf(help), MeshState(), now), ProState.FREE)
        assertTrue(visible.any { it.deviceId == "n12" })
        assertEquals("me + 7 others + the help call", 9, visible.size)
        assertEquals(4, hidden)
        val (all, none) = capRoster(buildRoster("me", "Me", nodes, emptyList(), MeshState(), now), ProState.PRO)
        assertEquals(13, all.size)
        assertEquals(0, none)
    }
}
