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
