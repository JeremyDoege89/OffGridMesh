package dev.offgridmesh.billing

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

import kotlinx.coroutines.flow.first

private val Context.entitlementStore by preferencesDataStore("entitlement")

/** Keeps Pro working offline between Play checks (Build Guide §11). Wiped with app data, as it should be. */
class DataStoreEntitlementCache(private val context: Context) : EntitlementCache {
    private val key = stringPreferencesKey("pro_state")

    override suspend fun load(): ProState =
        context.entitlementStore.data.first()[key]?.let { runCatching { ProState.valueOf(it) }.getOrNull() } ?: ProState.FREE

    override suspend fun save(state: ProState) {
        context.entitlementStore.edit { it[key] = state.name }
    }
}
