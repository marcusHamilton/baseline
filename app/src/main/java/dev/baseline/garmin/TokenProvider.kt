package dev.baseline.garmin

import dev.baseline.prefs.UserPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.time.Clock
import java.util.Base64

/** The API key is missing, rejected (401/403 on mint) or otherwise unusable. */
class KeyInvalidException(message: String) : Exception(message)

/** The mint endpoint answered 429 or 5xx: a transient server-side condition, not a bad key. */
class MintRateLimitedException(val code: Int) : Exception("token mint rate limited or unavailable: $code")

/** Mints the short-lived Personal API JWT from the API key; keeps it in memory and encrypted in [prefs]. */
class TokenProvider(
    private val baseUrl: HttpUrl,
    private val mintHttp: OkHttpClient,
    private val vault: ApiKeyVault,
    private val prefs: UserPrefs,
    private val encrypt: (ByteArray) -> ByteArray,
    private val decrypt: (ByteArray) -> ByteArray,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var jwt: String? = null
    private var expSec = 0L

    /** A JWT with at least 60 minutes left, minting one if needed. */
    suspend fun token(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!fresh()) loadCached()
            if (!fresh()) mint()
            jwt!!
        }
    }

    suspend fun forceRefresh(): String = mutex.withLock { withContext(Dispatchers.IO) { mint() } }

    /** Drops the in-memory and stored JWT so the next [token] mints with the key currently in the vault. */
    suspend fun clear() = mutex.withLock {
        jwt = null
        expSec = 0
        prefs.setCachedJwt(null)
    }

    suspend fun markKeyInvalid() = vault.markInvalid()

    suspend fun markKeyValid() = vault.markValid()

    private fun fresh() = jwt != null && expSec - clock.instant().epochSecond >= MIN_REMAINING_SEC

    /** Throws IllegalArgumentException unless [token] is a well-formed JWT with a numeric `exp`. */
    private fun accept(token: String) {
        val exp = expOf(token)
        jwt = token
        expSec = exp
    }

    /** Loads the stored JWT; if it cannot be read back (e.g. the Keystore key was lost), clears it. */
    private suspend fun loadCached() {
        try {
            prefs.cachedJwt.first()?.let { accept(String(decrypt(it))) }
        } catch (_: GeneralSecurityException) {
            prefs.setCachedJwt(null)
        } catch (_: IllegalArgumentException) {
            prefs.setCachedJwt(null)
        } catch (_: ProviderException) {
            prefs.setCachedJwt(null)
        }
    }

    private suspend fun mint(): String {
        // Missing key: the vault status is already Missing, so don't mark it Invalid.
        val key = vault.key() ?: throw KeyInvalidException("no API key")
        val token =
            try {
                requestJwt(key)
            } catch (e: KeyInvalidException) {
                vault.markInvalid()
                throw e
            }
        // A garbage 200 body is a transient glitch: never cache it or let it invalidate a good key.
        try {
            accept(token)
        } catch (_: IllegalArgumentException) {
            throw IOException("token mint returned a malformed JWT")
        }
        prefs.setCachedJwt(encrypt(token.toByteArray()))
        return token
    }

    /** A JWT for a key that is not stored yet: touches neither the vault nor the cached token. For testing a candidate key. */
    suspend fun mintFor(key: String): String = withContext(Dispatchers.IO) { requestJwt(key) }

    /** Throws [KeyInvalidException] on 401/403 without recording it anywhere. */
    private fun requestJwt(key: String): String {
        val request =
            Request
                .Builder()
                .url(baseUrl.resolve(MINT_PATH)!!)
                .header("Authorization", "ApiKey $key")
                .header("Accept", "*/*")
                .post(ByteArray(0).toRequestBody())
                .build()
        return mintHttp.newCall(request).execute().use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw KeyInvalidException("token mint rejected: ${r.code}")
                r.code == 429 || r.code in 500..599 -> throw MintRateLimitedException(r.code)
                !r.isSuccessful -> throw IOException("token mint failed: ${r.code}")
                else -> r.body.string().trim()
            }
        }
    }

    private companion object {
        const val MINT_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
        const val MIN_REMAINING_SEC = 60 * 60L

        val JWT_SHAPE = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

        /** `exp` (epoch seconds) of the JWT payload; IllegalArgumentException if malformed. */
        fun expOf(token: String): Long {
            require(JWT_SHAPE.matches(token)) { "not a JWT" }
            try {
                val payload = Base64.getUrlDecoder().decode(token.split('.')[1])
                return Json
                    .parseToJsonElement(String(payload))
                    .jsonObject
                    .getValue("exp")
                    .jsonPrimitive.long
            } catch (e: Exception) {
                throw IllegalArgumentException("unreadable JWT exp", e)
            }
        }
    }
}
