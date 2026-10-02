package dev.offgridmesh.ui

/** "just now", "4 min ago", "2 h ago", "3 d ago" — staleness is shown, never hidden (§9). */
fun ago(thenMs: Long, nowMs: Long): String {
    val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${(s + 30) / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

fun hopLabel(hops: Int): String = when (hops) {
    0 -> "Direct"
    1 -> "via 1 relay"
    else -> "via $hops relays"
}

data class NodeAccounting(val direct: Int, val viaRelay: Int) {
    val total get() = direct + viaRelay
}

/** "N nodes — X direct, Y via relay" (§9): direct = connected now; relay = heard via relay recently. */
fun nodeAccounting(
    mesh: dev.offgridmesh.mesh.MeshState,
    nodes: List<dev.offgridmesh.data.Node>,
    now: Long,
): NodeAccounting {
    val direct = mesh.connectedDeviceIds
    val viaRelay = nodes.count {
        it.deviceId !in direct && it.hopCount > 0 && now - it.lastSeenMs < dev.offgridmesh.mesh.MeshConfig.OFFLINE_AFTER_MS
    }
    return NodeAccounting(direct.size, viaRelay)
}
