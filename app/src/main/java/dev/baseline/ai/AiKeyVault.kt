package dev.baseline.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.GeneralSecurityException
import java.util.Base64

/** A usable cloud configuration. Holds the secret: never log it or put it in a message. */
data class AiConfig(val baseUrl: HttpUrl, val key: String, val model: String, val toolsSupported: Boolean) {
    override fun toString(): String = "AiConfig(baseUrl=$baseUrl, model=$model, toolsSupported=$toolsSupported)"
}

sealed interface AiKeyStatus {
    data object Missing : AiKeyStatus

    data class Set(val host: String, val model: String, val toolsSupported: Boolean, val invalid: Boolean) : AiKeyStatus
}

/** Stores the cloud AI key (encrypted via the injected cipher) with its base URL, model and validity flag. */
class AiKeyVault(private val store: DataStore<Preferences>, private val encrypt: (ByteArray) -> ByteArray, private val decrypt: (ByteArray) -> ByteArray) {
    /** Writes everything in one edit and clears the invalid flag. */
    suspend fun save(baseUrl: HttpUrl, key: String, model: String, toolsSupported: Boolean) {
        // Keystore work can block; keep it off the caller's (main) thread.
        val blob = withContext(Dispatchers.IO) { Base64.getEncoder().encodeToString(encrypt(key.toByteArray())) }
        store.edit {
            it[BASE_URL] = baseUrl.toString()
            it[KEY] = blob
            it[MODEL] = model
            it[TOOLS] = toolsSupported
            it[INVALID] = false
        }
    }

    suspend fun setModel(model: String, toolsSupported: Boolean) {
        store.edit {
            it[MODEL] = model
            it[TOOLS] = toolsSupported
        }
    }

    /** Null if absent or undecryptable (lost Keystore key, corrupt blob or URL); the latter also resets to Missing. */
    suspend fun config(): AiConfig? {
        val p = store.data.first()
        val blob = p[KEY] ?: return null
        val model = p[MODEL]
        val baseUrl = p[BASE_URL]?.toHttpUrlOrNull()
        if (model == null || baseUrl == null) {
            clear()
            return null
        }
        return try {
            val key = withContext(Dispatchers.IO) { String(decrypt(Base64.getDecoder().decode(blob))) }
            AiConfig(baseUrl, key, model, p[TOOLS] ?: false)
        } catch (_: GeneralSecurityException) {
            clear()
            null
        } catch (_: IllegalArgumentException) {
            clear()
            null
        }
    }

    suspend fun clear() {
        store.edit {
            it.remove(BASE_URL)
            it.remove(KEY)
            it.remove(MODEL)
            it.remove(TOOLS)
            it.remove(INVALID)
        }
    }

    suspend fun markInvalid() {
        store.edit { it[INVALID] = true }
    }

    /** A cloud call succeeded, so the key works after all. Writes only if the flag is set. */
    suspend fun markValid() {
        if (store.data.first()[INVALID] == true) store.edit { if (it[INVALID] == true) it[INVALID] = false }
    }

    val status: Flow<AiKeyStatus> =
        store.data
            .map { p ->
                val host = p[BASE_URL]?.toHttpUrlOrNull()?.host
                val model = p[MODEL]
                if (p[KEY] == null || host == null || model == null) {
                    AiKeyStatus.Missing
                } else {
                    AiKeyStatus.Set(host, model, p[TOOLS] ?: false, p[INVALID] == true)
                }
            }.distinctUntilChanged()

    private companion object {
        val BASE_URL = stringPreferencesKey("ai_base_url")
        val KEY = stringPreferencesKey("ai_key")
        val MODEL = stringPreferencesKey("ai_model")
        val TOOLS = booleanPreferencesKey("ai_tools_supported")
        val INVALID = booleanPreferencesKey("ai_key_invalid")
    }
}
