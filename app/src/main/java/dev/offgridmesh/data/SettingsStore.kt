package dev.offgridmesh.data

import android.content.Context
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

    /** Relay TTL for objects this phone creates (§7.7: 1–5, default 3). */
    val ttlHops: Flow<Int> = store.data.map {
        (it[ttlKey] ?: MeshConfig.DEFAULT_TTL_HOPS).coerceIn(MeshConfig.TTL_RANGE)
    }

    suspend fun setTtlHops(hops: Int) {
        store.edit { it[ttlKey] = hops.coerceIn(MeshConfig.TTL_RANGE) }
    }
}
