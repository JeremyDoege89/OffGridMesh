package dev.offgridmesh.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.offgridmesh.mesh.MeshConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsStore
    private val ttlKey = intPreferencesKey("ttl_hops")
    private val shareLocationKey = booleanPreferencesKey("share_location")
    private val noMapAckKey = booleanPreferencesKey("no_map_acknowledged")

    /** Off by default: position is shared only when the member chooses to (§6, §10). */
    val shareLocation: Flow<Boolean> = store.data.map { it[shareLocationKey] ?: false }

    suspend fun setShareLocation(on: Boolean) {
        store.edit { it[shareLocationKey] = on }
    }

    /** Pre-trip setup was finished without a map pack after the explicit "no map offline" confirmation (§6). */
    val noMapAcknowledged: Flow<Boolean> = store.data.map { it[noMapAckKey] ?: false }

    suspend fun setNoMapAcknowledged(on: Boolean) {
        store.edit { it[noMapAckKey] = on }
    }

    /** Relay TTL for objects this phone creates (§7.7: 1–5, default 3). */
    val ttlHops: Flow<Int> = store.data.map {
        (it[ttlKey] ?: MeshConfig.DEFAULT_TTL_HOPS).coerceIn(MeshConfig.TTL_RANGE)
    }

    suspend fun setTtlHops(hops: Int) {
        store.edit { it[ttlKey] = hops.coerceIn(MeshConfig.TTL_RANGE) }
    }
}
