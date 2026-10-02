package dev.offgridmesh.mesh

/**
 * Every timing/range constant the field tests (Build Guide §14) are expected to retune lives here.
 * Values are starting points, not measurements.
 */
object MeshConfig {
    /** Nearby service id. Bump the suffix only for a wire-incompatible change (Appendix B is an API). */
    const val SERVICE_ID = "dev.offgridmesh.mesh.v1"

    /** Default relay TTL (§3.4); Settings allows 1..5 from M2. */
    const val DEFAULT_TTL_HOPS = 3

    /** How often a presence beacon goes to every connected endpoint. */
    const val PRESENCE_INTERVAL_MS = 60_000L

    /** How often MeshCore re-evaluates pending connections. */
    const val MAINTENANCE_TICK_MS = 2_000L

    /**
     * Only the device with the lower deviceId initiates a connection, so two phones don't request each
     * other at once. If the higher id still has no connection after this long (asymmetric discovery),
     * it initiates too.
     */
    const val CONNECT_FALLBACK_MS = 8_000L

    /** Minimum gap between connection attempts to the same endpoint. */
    const val RETRY_BACKOFF_MS = 5_000L

    /** A connection request with no result after this long is treated as failed. */
    const val CONNECT_TIMEOUT_MS = 30_000L

    /** A member not heard from directly or via relay for this long is shown as offline (§5.5). */
    const val OFFLINE_AFTER_MS = 10 * 60_000L

    /** Planning range quoted in empty states (§3.4, §9). Replace with measured medians after the walk test. */
    const val PLANNING_RANGE_TEXT = "~30–50 m"
}
