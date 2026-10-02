package dev.offgridmesh.crypto

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** How this phone talks to its team. [Unset] blocks the mesh until the user chooses (§10, D4). */
sealed interface TeamMode {
    data object Unset : TeamMode
    data object Open : TeamMode
    class Passphrase(val key: ByteArray) : TeamMode

    fun frames(): FrameCrypto? = when (this) {
        Unset -> null
        Open -> OpenFrames
        is Passphrase -> AesGcmFrames(key)
    }
}

private val Context.teamStore by preferencesDataStore("team")

/**
 * Stores the derived key, never the passphrase. App-private storage; a rooted phone can read it, which is
 * within the stated limits of team passphrase encryption.
 */
class TeamStore(context: Context) {
    private val store = context.applicationContext.teamStore
    private val modeKey = stringPreferencesKey("mode")
    private val keyKey = stringPreferencesKey("key")

    val mode: Flow<TeamMode> = store.data.map { p ->
        when (p[modeKey]) {
            "open" -> TeamMode.Open
            "passphrase" -> p[keyKey]?.let { TeamMode.Passphrase(Base64.decode(it, Base64.NO_WRAP)) } ?: TeamMode.Unset
            else -> TeamMode.Unset
        }
    }

    suspend fun current(): TeamMode = mode.first()

    suspend fun setPassphrase(passphrase: String) {
        val key = withContext(Dispatchers.Default) { TeamCrypto.deriveKey(passphrase) }
        store.edit {
            it[modeKey] = "passphrase"
            it[keyKey] = Base64.encodeToString(key, Base64.NO_WRAP)
        }
    }

    suspend fun setOpen() {
        store.edit {
            it[modeKey] = "open"
            it.remove(keyKey)
        }
    }
}
