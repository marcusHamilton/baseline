package dev.baseline.garmin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor

sealed interface GarminResult {
    data class Ok(val body: ByteArray, val contentType: String?) : GarminResult

    data object Empty : GarminResult

    data class RateLimited(val code: Int) : GarminResult

    data class BadRequest(val body: String) : GarminResult

    data object KeyInvalid : GarminResult

    data class Failed(val code: Int?, val message: String) : GarminResult
}

/**
 * Raw GETs against the Personal API. [http] must carry a [GarminAuthenticator]; [IOException]s propagate
 * to the caller (the sync engine treats them as offline).
 */
class GarminClient(private val baseUrl: HttpUrl, private val http: OkHttpClient, private val tokens: TokenProvider) {
    /** One request in flight at a time, whoever asks (sync run, key test in Settings): Garmin is rate limited. */
    private val requestLock = Mutex()

    suspend fun fetch(endpoint: GarminEndpoint, key: String): GarminResult = call(endpoint, key, http, tokens::token, storedKey = true)

    /**
     * Tests [candidate], a key that is not stored: mints from it and fetches PROFILE with that JWT. Never touches the
     * vault, the cached token or the key's valid/invalid flag, and no authenticator retries with the stored key.
     * [onValid] runs on a passing test while the request lock is still held, so no request with the old key can run
     * between the pass and the caller saving the new one.
     */
    suspend fun testKey(candidate: String, onValid: suspend () -> Unit): GarminResult = call(
        GarminEndpoint.PROFILE,
        "",
        http.newBuilder().authenticator(Authenticator.NONE).build(),
        { tokens.mintFor(candidate) },
        storedKey = false,
        onValid,
    )

    private suspend fun call(
        endpoint: GarminEndpoint,
        key: String,
        http: OkHttpClient,
        bearer: suspend () -> String,
        storedKey: Boolean,
        onValid: suspend () -> Unit = {},
    ): GarminResult = try {
        // shortcut: execute() is not cancelled with the coroutine, so it ends within the client's callTimeout.
        requestLock.withLock {
            withContext(Dispatchers.IO) {
                val spec = endpoint.request(key)
                val url =
                    baseUrl
                        .newBuilder()
                        .encodedPath(spec.path)
                        .apply {
                            spec.query.forEach { (k, v) -> addQueryParameter(k, v) }
                        }.build()
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("Authorization", "Bearer ${bearer()}")
                        .header("Accept", spec.accept)
                        .build()
                http.newCall(request).execute().use { r ->
                    when (val code = r.code) {
                        200 -> {
                            if (storedKey) tokens.markKeyValid()
                            val body = r.body.bytes() // read first: a failed read must not leave the key saved
                            onValid()
                            classify(body, r.header("Content-Type"), endpoint)
                        }

                        204 -> {
                            onValid()
                            GarminResult.Empty
                        }

                        400 -> {
                            GarminResult.BadRequest(r.body.string())
                        }

                        401 -> {
                            if (storedKey) tokens.markKeyInvalid()
                            GarminResult.KeyInvalid
                        }

                        429, in 500..599 -> {
                            GarminResult.RateLimited(code)
                        }

                        // 403 with a valid token: this endpoint is not permitted for the key, not a bad key.
                        else -> {
                            GarminResult.Failed(code, r.message)
                        }
                    }
                }
            }
        }
    } catch (_: KeyInvalidException) {
        GarminResult.KeyInvalid
    } catch (e: MintRateLimitedException) {
        GarminResult.RateLimited(e.code)
    }

    private fun classify(body: ByteArray, contentType: String?, endpoint: GarminEndpoint): GarminResult =
        if (isEmpty(body, endpoint)) GarminResult.Empty else GarminResult.Ok(body, contentType)

    private fun isEmpty(body: ByteArray, endpoint: GarminEndpoint): Boolean {
        if (body.isEmpty()) return true
        // Empty payloads are tiny; skip parsing real data (and FIT zips).
        if (endpoint == GarminEndpoint.FIT || body.size > MAX_EMPTY_BODY) return false
        val json =
            try {
                Json.parseToJsonElement(String(body))
            } catch (_: Exception) {
                return false
            }
        return when (json) {
            is JsonArray -> json.isEmpty()
            is JsonObject -> json.size == 1 && (json["data"] as? JsonArray)?.isEmpty() == true
            else -> false
        }
    }

    companion object {
        private const val MAX_EMPTY_BODY = 256

        /** Debug-only: logs headers but never the Authorization value (API key / JWT). */
        fun loggingInterceptor(logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT): HttpLoggingInterceptor =
            HttpLoggingInterceptor(logger).apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                redactHeader("Authorization")
            }
    }
}
