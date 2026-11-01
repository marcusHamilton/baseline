package dev.baseline.ai.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The OpenAI-compatible chat-completions wire format (spec: AI assistant §4.2), built and read by hand: no compiler plugin, no vendor SDK. */
internal const val CHAT_MAX_TOKENS = 1024
internal const val INSIGHT_MAX_TOKENS = 200
internal const val CLOUD_TEMPERATURE = 0.3

sealed interface SseLine {
    data class Data(val json: String) : SseLine

    data object Done : SseLine

    data object Skip : SseLine
}

/** `data: [DONE]` ends the stream, `data: {…}` carries a chunk; blank lines, `:` comments and other fields (`event:`, `id:`) are skipped. */
fun parseSseLine(line: String): SseLine {
    if (!line.startsWith("data:")) return SseLine.Skip
    val payload = line.substring(5).trim()
    return when {
        payload.isEmpty() -> SseLine.Skip
        payload == "[DONE]" -> SseLine.Done
        else -> SseLine.Data(payload)
    }
}

/** What one streamed chunk adds up to. */
internal data class Chunk(val text: String?, val hasToolCalls: Boolean, val finishReason: String?)

/** Thrown for a chunk we can't read, and for an in-stream `error` object. Carries no body text. */
internal class WireException : Exception()

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Parses one `data:` payload. Throws [WireException] (never with provider text) when it isn't a well-formed chunk. */
internal fun parseChunk(json: String): Chunk {
    val root =
        try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw WireException()
    if (root["error"] != null && root["error"] !is JsonNull) throw WireException()
    val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return Chunk(null, false, null)
    val delta = choice["delta"] as? JsonObject
    val toolCalls = delta?.get("tool_calls") as? JsonArray
    return Chunk(
        text = delta?.get("content").stringOrNull()?.takeIf { it.isNotEmpty() },
        hasToolCalls = toolCalls != null && toolCalls.isNotEmpty(),
        finishReason = choice["finish_reason"].stringOrNull(),
    )
}

/** `[system + "\n\n" + pack, …turns]`. */
internal fun initialMessages(req: AiRequest): JsonArray = buildJsonArray {
    add(
        buildJsonObject {
            put("role", "system")
            put("content", req.system + "\n\n" + req.pack)
        },
    )
    for (turn in req.turns) {
        add(
            buildJsonObject {
                put("role", if (turn.role == Role.USER) "user" else "assistant")
                put("content", turn.text)
            },
        )
    }
}

/** The request body. [tools] (with `tool_choice`) is left out entirely when null. */
internal fun chatBody(model: String, kind: RequestKind, messages: JsonArray, tools: JsonArray?, toolChoice: String = "auto"): String = buildJsonObject {
    put("model", model)
    put("messages", messages)
    put("stream", true)
    put("temperature", CLOUD_TEMPERATURE)
    put("max_tokens", if (kind == RequestKind.CHAT) CHAT_MAX_TOKENS else INSIGHT_MAX_TOKENS)
    if (tools != null) {
        put("tools", tools)
        put("tool_choice", toolChoice)
    }
}.toString()

/** True when a 400 error body names the model in `error.code` or `error.param`. Looks at nothing else, and the text goes no further. */
internal fun namesModel(body: String): Boolean {
    val error =
        try {
            (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
    return listOf("code", "param").any { error[it].stringOrNull()?.contains("model", ignoreCase = true) == true }
}
