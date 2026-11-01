package dev.baseline.ai

import dev.baseline.ai.engine.namesModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext

const val DEFAULT_AI_BASE_URL = "https://openrouter.ai/api/v1"
const val SUGGESTED_MODEL = "deepseek/deepseek-v4.1-flash"

private const val TYPICAL_INPUT_TOKENS = 10_000
private const val TYPICAL_OUTPUT_TOKENS = 500
private const val ERROR_PEEK_BYTES = 8L * 1024

/** Room for a reasoning model to think before it calls ping: at 16 tokens DeepSeek v4.1 ran out first and read as "no tools". */
private const val TEST_MAX_TOKENS = 1024
private val JSON_TYPE = "application/json".toMediaType()
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost")

/**
 * Trims [input], drops one trailing '/', and accepts only `https://` URLs; with [allowLoopbackHttp] (test builds) also `http://` to
 * 127.0.0.1 or localhost. Null for anything else (spec: AI assistant §3.3).
 */
fun parseBaseUrl(input: String, allowLoopbackHttp: Boolean): HttpUrl? {
    val url = input.trim().removeSuffix("/").toHttpUrlOrNull() ?: return null
    return url.takeIf { it.isHttps || (allowLoopbackHttp && it.host in LOOPBACK_HOSTS) }
}

/** One row of the model list. Everything but [id] is null when the provider doesn't say (only OpenRouter does). Prices are USD per token. */
data class ModelInfo(val id: String, val contextLength: Int?, val promptPrice: Double?, val completionPrice: Double?, val tools: Boolean?) {
    /** The price of a typical question: 10,000 input tokens plus 500 output tokens. Null when either price is unknown. */
    val pricePerQuestion: Double? =
        if (promptPrice != null && completionPrice != null) {
            TYPICAL_INPUT_TOKENS * promptPrice + TYPICAL_OUTPUT_TOKENS * completionPrice
        } else {
            null
        }
}

/** "≈ $0.0007 per question": two significant digits, no trailing zeros. */
fun formatPricePerQuestion(usd: Double): String {
    val digits = BigDecimal.valueOf(usd).round(MathContext(2)).stripTrailingZeros().toPlainString()
    return "≈ $$digits per question"
}

sealed interface ModelsResult {
    data class Listed(val models: List<ModelInfo>) : ModelsResult

    /** `/models` failed, or listed nothing: ask for a model ID instead. */
    data object FreeText : ModelsResult

    data object KeyRejected : ModelsResult

    data class Unreachable(val host: String) : ModelsResult
}

sealed interface TestOutcome {
    data class Saved(val toolsSupported: Boolean) : TestOutcome

    /** [message] is our own wording; never provider text and never the key. */
    data class Failed(val message: String) : TestOutcome
}

/** The three shapes of the test request (spec §4.3), tried in this order. */
private enum class TestShape(val toolChoice: String?) {
    REQUIRED("required"),
    AUTO("auto"),
    NO_TOOLS(null),
}

private sealed interface Probe {
    data class Passed(val toolsSupported: Boolean) : Probe

    data class Failed(val message: String) : Probe
}

/**
 * Lists a provider's models and tests a key (spec: AI assistant §3.3, §4.3). A key under test never touches the vault: only a passing
 * test saves it. No response body text, and never the key, reaches a result.
 */
class AiKeyEntry(
    private val http: OkHttpClient,
    private val vault: AiKeyVault,
    private val prefs: AiPrefs,
    private val nanoSupported: suspend () -> Boolean,
    private val allowLoopbackHttp: Boolean,
) {
    /** [parseBaseUrl] with this build's loopback rule. */
    fun parseUrl(input: String): HttpUrl? = parseBaseUrl(input, allowLoopbackHttp)

    /** The key trimmed; null when it is empty or has whitespace inside (a half-copied or doubled paste). */
    fun cleanKey(input: String): String? = input.trim().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }

    suspend fun listModels(baseUrl: HttpUrl, key: String): ModelsResult = withContext(Dispatchers.IO) {
        // A raw paste (trailing newline) would make the header builder throw.
        val clean = cleanKey(key) ?: return@withContext ModelsResult.KeyRejected
        val request = authorized(baseUrl, clean, baseUrl.newBuilder().addPathSegment("models").build()).get().build()
        try {
            http.newCall(request).await().use { r ->
                when (r.code) {
                    200 -> parseModels(r.body.string())
                    401, 403 -> ModelsResult.KeyRejected
                    else -> ModelsResult.FreeText
                }
            }
        } catch (_: IOException) {
            ModelsResult.Unreachable(baseUrl.host)
        }
    }

    /** Tests [key] against [model] and, only if that works, saves everything in one edit and applies the mode transition. */
    suspend fun saveAndTest(baseUrl: HttpUrl, key: String, model: String): TestOutcome {
        val clean = cleanKey(key) ?: return INCOMPLETE_KEY
        return when (val probe = probe(baseUrl, clean, model)) {
            is Probe.Failed -> TestOutcome.Failed(probe.message)

            is Probe.Passed -> {
                vault.save(baseUrl, clean, model, probe.toolsSupported)
                prefs.setMode(AiModes.afterKeySaved(prefs.mode.first(), nanoSupported()))
                TestOutcome.Saved(probe.toolsSupported)
            }
        }
    }

    /** Re-tests the stored URL and key with [model]. On success only the model and its tools flag change; the key is never rewritten. */
    suspend fun changeModel(model: String): TestOutcome {
        val config = vault.config() ?: return TestOutcome.Failed(KEY_REJECTED)
        return when (val probe = probe(config.baseUrl, config.key, model)) {
            is Probe.Failed -> TestOutcome.Failed(probe.message)

            is Probe.Passed -> {
                vault.setModel(model, probe.toolsSupported)
                TestOutcome.Saved(probe.toolsSupported)
            }
        }
    }

    suspend fun remove() {
        vault.clear()
        prefs.setMode(AiModes.afterKeyRemoved(prefs.mode.first(), nanoSupported()))
    }

    /** Sends the §4.3 request, falling back from `required` to `auto` to no tools while the provider answers 400 or 422. */
    private suspend fun probe(baseUrl: HttpUrl, key: String, model: String): Probe = withContext(Dispatchers.IO) {
        val host = baseUrl.host
        val url = baseUrl.newBuilder().addPathSegment("chat").addPathSegment("completions").build()
        for (shape in TestShape.entries) {
            val request = authorized(baseUrl, key, url).post(testBody(model, shape).toRequestBody(JSON_TYPE)).build()
            val answer =
                try {
                    http.newCall(request).await().use { r ->
                        when (r.code) {
                            200 -> Probe.Passed(shape != TestShape.NO_TOOLS && hasToolCall(r.body.string()))
                            401, 403 -> Probe.Failed(KEY_REJECTED)
                            402 -> Probe.Failed("No credit left on this account.")
                            404 -> Probe.Failed(MODEL_GONE)
                            400, 422 -> if (namesModel(r.peekBody(ERROR_PEEK_BYTES).string())) Probe.Failed(MODEL_GONE) else null
                            else -> Probe.Failed("Couldn't reach $host.")
                        }
                    }
                } catch (_: IOException) {
                    Probe.Failed("Couldn't reach $host.")
                }
            if (answer != null) return@withContext answer
        }
        // Even the plain request was refused as malformed, so the model can't be used the way we need.
        Probe.Failed(MODEL_GONE)
    }

    private fun authorized(baseUrl: HttpUrl, key: String, url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        // The key goes only to the configured host.
        if (url.host == baseUrl.host) builder.header("Authorization", "Bearer $key")
        return builder
    }

    private fun testBody(model: String, shape: TestShape): String = buildJsonObject {
        put("model", model)
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", "Call the ping tool.")
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", "ping")
                    },
                )
            },
        )
        if (shape.toolChoice != null) {
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "function")
                            put(
                                "function",
                                buildJsonObject {
                                    put("name", "ping")
                                    put("description", "Health check")
                                    put(
                                        "parameters",
                                        buildJsonObject {
                                            put("type", "object")
                                            put("properties", buildJsonObject { })
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put("tool_choice", shape.toolChoice)
        }
        put("stream", false)
        put("max_tokens", TEST_MAX_TOKENS)
    }.toString()

    /** True when `choices[0].message.tool_calls` is a non-empty array. A body we can't read counts as no tool call: the 200 still proves the key. */
    private fun hasToolCall(body: String): Boolean {
        val root = parseObject(body) ?: return false
        val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        return (message?.get("tool_calls") as? JsonArray)?.isNotEmpty() == true
    }

    private fun parseModels(body: String): ModelsResult {
        val data = parseObject(body)?.get("data") as? JsonArray ?: return ModelsResult.FreeText
        val models =
            data.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val pricing = obj["pricing"] as? JsonObject
                ModelInfo(
                    id = id,
                    contextLength = (obj["context_length"] as? JsonPrimitive)?.intOrNull,
                    promptPrice = price(pricing?.get("prompt")),
                    completionPrice = price(pricing?.get("completion")),
                    tools = (obj["supported_parameters"] as? JsonArray)?.any { (it as? JsonPrimitive)?.content == "tools" },
                )
            }
        if (models.isEmpty()) return ModelsResult.FreeText
        return ModelsResult.Listed(models.sortedWith(compareBy<ModelInfo> { it.tools != true }.thenBy { it.id }))
    }

    /** OpenRouter sends prices as strings of USD per token. A negative price means "varies" there, so it is unknown. */
    private fun price(element: JsonElement?): Double? = (element as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }?.takeIf { it >= 0 }

    private fun parseObject(body: String): JsonObject? = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val KEY_REJECTED = "Key rejected."
        const val MODEL_GONE = "This model isn't available here."
        val INCOMPLETE_KEY = TestOutcome.Failed("That doesn't look like a complete key.")
    }
}

/** Suspends until the response headers arrive; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) response.close() else cont.resumeWith(Result.success(response))
            }
        },
    )
}
