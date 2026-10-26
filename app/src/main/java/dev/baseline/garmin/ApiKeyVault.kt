package dev.baseline.garmin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Base64

sealed interface KeyStatus {
    data object Missing : KeyStatus

    data class Ok(val expires: LocalDate, val daysLeft: Int) : KeyStatus

    data class ExpiringSoon(val expires: LocalDate, val daysLeft: Int) : KeyStatus

    data class Expired(val expires: LocalDate) : KeyStatus

    data object Invalid : KeyStatus
}

/** Stores the Garmin API key encrypted (via the injected cipher) plus its expiry and validity flag. */
class ApiKeyVault(
    private val store: DataStore<Preferences>,
    private val encrypt: (ByteArray) -> ByteArray,
    private val decrypt: (ByteArray) -> ByteArray,
    private val clock: Clock,
    private val today: Flow<LocalDate> =
        flow {
            while (true) {
                emit(LocalDate.now(clock))
                delay(TICK_MS)
            }
        },
) {
    suspend fun save(key: String, lifetimeDays: Int) {
        // Keystore work can block; keep it off the caller's (main) thread.
        val blob = withContext(Dispatchers.IO) { Base64.getEncoder().encodeToString(encrypt(key.toByteArray())) }
        val expires = LocalDate.now(clock).plusDays(lifetimeDays.toLong())
        store.edit {
            it[KEY] = blob
            it[EXPIRES] = expires.toString()
            it[INVALID] = false
        }
    }

    /** Null if absent or undecryptable (lost Keystore key, corrupt blob); the latter also resets to Missing. */
    suspend fun key(): String? {
        val blob = store.data.first()[KEY] ?: return null
        return try {
            withContext(Dispatchers.IO) { String(decrypt(Base64.getDecoder().decode(blob))) }
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
            it.remove(KEY)
            it.remove(EXPIRES)
            it.remove(INVALID)
        }
    }

    suspend fun markInvalid() {
        store.edit { it[INVALID] = true }
    }

    /** A data call succeeded, so the key works after all (a 401 can be transient). Writes only if the flag is set. */
    suspend fun markValid() {
        if (store.data.first()[INVALID] == true) store.edit { if (it[INVALID] == true) it[INVALID] = false }
    }

    val status: Flow<KeyStatus> =
        combine(store.data, today.distinctUntilChanged()) { p, today ->
            val expires = p[EXPIRES]?.let(LocalDate::parse)
            if (p[KEY] == null || expires == null) return@combine KeyStatus.Missing
            if (p[INVALID] == true) return@combine KeyStatus.Invalid
            val daysLeft = ChronoUnit.DAYS.between(today, expires).toInt()
            when {
                today.isAfter(expires) -> KeyStatus.Expired(expires)
                daysLeft <= 7 -> KeyStatus.ExpiringSoon(expires, daysLeft)
                else -> KeyStatus.Ok(expires, daysLeft)
            }
        }

    private companion object {
        const val TICK_MS = 60_000L
        val KEY = stringPreferencesKey("api_key_enc")
        val EXPIRES = stringPreferencesKey("api_key_expires")
        val INVALID = booleanPreferencesKey("api_key_invalid")
    }
}
