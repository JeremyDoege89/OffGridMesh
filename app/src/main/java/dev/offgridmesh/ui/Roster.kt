package dev.offgridmesh.ui

import dev.offgridmesh.billing.Entitlements
import dev.offgridmesh.billing.ProState
import dev.offgridmesh.data.MemberStatusEntity
import dev.offgridmesh.data.Node
import dev.offgridmesh.mesh.MeshConfig
import dev.offgridmesh.mesh.MeshState
import dev.offgridmesh.sync.MemberState

/** One row of the roll-call (§7.6): everyone the phone knows, with live and derived state. */
data class Member(
    val deviceId: String,
    val name: String,
    val isSelf: Boolean,
    val node: Node?,
    val status: MemberStatusEntity?,
    val connected: Boolean,
    /** Derived, never stored (§5.5): not heard from for OFFLINE_AFTER_MS. Never true for this phone. */
    val offline: Boolean,
) {
    val needsHelp get() = status?.state == MemberState.NEED_HELP
}

fun buildRoster(
    selfId: String,
    selfName: String,
    nodes: List<Node>,
    statuses: List<MemberStatusEntity>,
    mesh: MeshState,
    now: Long,
): List<Member> {
    val byId = statuses.associateBy { it.deviceId }
    val connected = mesh.connectedDeviceIds
    val others = nodes.filter { it.deviceId != selfId }.map { n ->
        Member(
            deviceId = n.deviceId,
            name = n.displayName.ifBlank { byId[n.deviceId]?.displayName ?: "Unnamed" },
            isSelf = false,
            node = n,
            status = byId[n.deviceId],
            connected = n.deviceId in connected,
            offline = n.deviceId !in connected && now - n.lastSeenMs >= MeshConfig.OFFLINE_AFTER_MS,
        )
    }
    val me = Member(selfId, selfName.ifBlank { "You" }, true, null, byId[selfId], connected = false, offline = false)
    // NEED_HELP sorts first on every phone (§7.6), then you, then who's reachable, then most recently seen.
    return (listOf(me) + others).sortedWith(
        compareByDescending<Member> { it.needsHelp }
            .thenByDescending { it.isSelf }
            .thenByDescending { it.connected }
            .thenBy { it.offline }
            .thenByDescending { it.node?.lastSeenMs ?: Long.MAX_VALUE },
    )
}

/**
 * The free-tier node cap (§2), applied in the UI only (§11): this phone plus 7 others are listed. Members
 * who need help are always shown, whatever the tier — safety is never behind the paywall.
 * Returns the visible rows and how many were collapsed.
 */
fun capRoster(members: List<Member>, pro: ProState): Pair<List<Member>, Int> {
    val limit = Entitlements.visibleOthers(pro)
    var othersShown = 0
    val visible = members.filter { m ->
        when {
            m.isSelf || m.needsHelp -> true
            othersShown < limit -> { othersShown++; true }
            else -> false
        }
    }
    return visible to (members.size - visible.size)
}

/** Members the mesh can currently see (connected or recently relayed), this phone included. */
fun activeNodeCount(members: List<Member>) = members.count { it.isSelf || !it.offline }
