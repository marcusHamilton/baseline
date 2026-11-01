package dev.baseline.ai.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
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
internal data class Chunk(val text: String?, val toolCalls: List<ToolCallDelta>, val finishReason: String?)

/** A fragment of one tool call. [index] says which call it belongs to; [id] and [name] arrive once, [arguments] in pieces. */
internal data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val arguments: String?)

/** A complete tool call, assembled from its deltas. */
internal data class ToolCall(val id: String, val name: String, val arguments: String)

/** Adds up [ToolCallDelta]s by index across chunks. */
internal class ToolCallAssembler {
    private class Partial(var id: String? = null, var name: String? = null, val arguments: StringBuilder = StringBuilder())

    private val partials = sortedMapOf<Int, Partial>()

    val isEmpty: Boolean get() = partials.isEmpty()

    fun add(delta: ToolCallDelta) {
        val p = partials.getOrPut(delta.index) { Partial() }
        delta.id?.let { p.id = it }
        delta.name?.let { p.name = it }
        delta.arguments?.let { p.arguments.append(it) }
    }

    /** The calls in index order. Throws [WireException] for a call with no function name. Empty arguments become "{}". */
    fun build(): List<ToolCall> = partials.map { (index, p) ->
        ToolCall(
            id = p.id?.takeIf { it.isNotEmpty() } ?: "call_$index",
            name = p.name?.takeIf { it.isNotEmpty() } ?: throw WireException(),
            arguments = p.arguments.toString().ifBlank { "{}" },
        )
    }
}

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
    val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return Chunk(null, emptyList(), null)
    val delta = choice["delta"] as? JsonObject
    val toolCalls = (delta?.get("tool_calls") as? JsonArray).orEmpty().mapIndexed { position, element ->
        val call = element as? JsonObject ?: throw WireException()
        val function = call["function"] as? JsonObject
        ToolCallDelta(
            index = (call["index"] as? JsonPrimitive)?.intOrNull ?: position,
            id = call["id"].stringOrNull(),
            name = function?.get("name").stringOrNull(),
            arguments = function?.get("arguments").stringOrNull(),
        )
    }
    return Chunk(
        text = delta?.get("content").stringOrNull()?.takeIf { it.isNotEmpty() },
        toolCalls = toolCalls,
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

/** `{"role":"assistant","tool_calls":[…]}`, carrying any [text] streamed alongside the calls. */
internal fun assistantToolMessage(text: String, calls: List<ToolCall>): JsonObject = buildJsonObject {
    put("role", "assistant")
    if (text.isNotEmpty()) put("content", text)
    put(
        "tool_calls",
        buildJsonArray {
            for (c in calls) {
                add(
                    buildJsonObject {
                        put("id", c.id)
                        put("type", "function")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", c.name)
                                put("arguments", c.arguments)
                            },
                        )
                    },
                )
            }
        },
    )
}

internal fun toolMessage(callId: String, content: String): JsonObject = buildJsonObject {
    put("role", "tool")
    put("tool_call_id", callId)
    put("content", content)
}

internal const val CONTEXT_BUDGET_TOKENS = 60_000
internal const val REMOVED_LOOKUP = "[earlier lookup removed to save space]"

/**
 * Keeps the conversation under [CONTEXT_BUDGET_TOKENS] (characters ÷ 4) by replacing the oldest tool results with [REMOVED_LOOKUP],
 * oldest first, until it fits. Everything else stays.
 */
internal fun fitBudget(messages: MutableList<JsonObject>) {
    fun size() = messages.sumOf { (it["content"] as? JsonPrimitive)?.takeIf { c -> c.isString }?.content?.length ?: 0 }
    var total = size()
    for (i in messages.indices) {
        if (total / 4 <= CONTEXT_BUDGET_TOKENS) return
        val m = messages[i]
        if (m["role"].stringOrNull() != "tool") continue
        val length = m["content"].stringOrNull()?.length ?: continue
        if (length <= REMOVED_LOOKUP.length) continue
        messages[i] = buildJsonObject {
            for ((k, v) in m) put(k, if (k == "content") JsonPrimitive(REMOVED_LOOKUP) else v)
        }
        total -= length - REMOVED_LOOKUP.length
    }
}

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
