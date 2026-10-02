package dev.offgridmesh.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Local-only identity (§4): a generated device UUID plus a user-chosen display name. No accounts. */
data class Identity(val deviceId: String, val displayName: String, val onboarded: Boolean)

private val Context.identityStore by preferencesDataStore("identity")

class IdentityStore(context: Context) {
    private val store = context.applicationContext.identityStore

    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val displayName = stringPreferencesKey("display_name")
        val onboarded = booleanPreferencesKey("onboarded")
    }

    /** Emits once the device id exists; the first read on a fresh install creates it. */
    val identity: Flow<Identity> = store.data.map { p ->
        Identity(
            deviceId = p[Keys.deviceId] ?: "",
            displayName = p[Keys.displayName] ?: "",
            onboarded = p[Keys.onboarded] ?: false,
        )
    }

    suspend fun ensureDeviceId(): String {
        store.data.first()[Keys.deviceId]?.let { return it }
        var id = ""
        store.edit { p -> id = p[Keys.deviceId] ?: UUID.randomUUID().toString().also { p[Keys.deviceId] = it } }
        return id
    }

    suspend fun current(): Identity {
        ensureDeviceId()
        return identity.first()
    }

    suspend fun setDisplayName(name: String) {
        store.edit { it[Keys.displayName] = name.trim().take(MAX_NAME) }
    }

    suspend fun setOnboarded(done: Boolean) {
        store.edit { it[Keys.onboarded] = done }
    }

    companion object {
        const val MAX_NAME = 40
    }
}
