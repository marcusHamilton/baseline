package dev.baseline.ai.engine

import dev.baseline.ai.AiConfig
import dev.baseline.ai.AiKeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay as coroutineDelay

/** Runs the model's tool calls (implemented by `HealthTools`). */
interface ToolRunner {
    /** The OpenAI `tools` array. */
    val definitions: JsonArray

    suspend fun call(name: String, argumentsJson: String): ToolResult
}

data class ToolResult(val statusLabel: String, val content: String)

object NoTools : ToolRunner {
    override val definitions: JsonArray = JsonArray(emptyList())

    override suspend fun call(name: String, argumentsJson: String) = ToolResult("", """{"error":"unknown tool"}""")
}

private const val MAX_TOOL_ROUNDS = 6
private const val DEFAULT_RETRY_SECONDS = 2L
private const val MAX_RETRY_SECONDS = 10L
private const val ERROR_PEEK_BYTES = 8L * 1024
private val JSON_TYPE = "application/json".toMediaType()

/** What one streamed response added up to. */
private class Round(val text: String, val calls: List<ToolCall>) {
    val gotText: Boolean get() = text.isNotEmpty()
}

private sealed interface Exchange {
    class Answer(val round: Round) : Exchange

    class RetryAfter(val seconds: Long) : Exchange
}

/**
 * An OpenAI-compatible streaming client (spec: AI assistant §4.2, §8). Errors are mapped from status codes only: no response body text,
 * and never the key, reaches an exception, the UI or a log.
 */
class CloudEngine(
    private val http: OkHttpClient,
    private val vault: AiKeyVault,
    private val tools: ToolRunner,
    private val online: () -> Boolean,
    private val delay: suspend (Long) -> Unit = { coroutineDelay(it) },
) : AiEngine {
    override fun run(req: AiRequest): Flow<AiEvent> = flow {
        if (!online()) throw AiFailure.Offline
        val config = vault.config() ?: throw AiFailure.KeyRejected
        val label = EngineLabel(EngineKind.CLOUD, config.model)
        val toolDefs = tools.definitions.takeIf { config.toolsSupported && req.kind == RequestKind.CHAT && it.isNotEmpty() }
        val messages = initialMessages(req).map { it as JsonObject }.toMutableList()
        var rounds = 0
        while (true) {
            // After the sixth round of tools, one last request forbids them.
            val forced = toolDefs != null && rounds >= MAX_TOOL_ROUNDS
            fitBudget(messages)
            val body = chatBody(config.model, req.kind, JsonArray(messages), toolDefs, if (forced) "none" else "auto")
            val round = roundTrip(config, body) { emit(it) }
            // Without offered tools a tool call can't be run, and a forced round's calls are ignored.
            if (round.calls.isNotEmpty() && toolDefs == null) throw AiFailure.Malformed
            if (round.calls.isEmpty() || forced) {
                if (!round.gotText) throw AiFailure.Malformed
                emit(AiEvent.Done(label))
                return@flow
            }
            rounds++
            messages += assistantToolMessage(round.text, round.calls)
            for (call in round.calls) {
                val result = tools.call(call.name, call.arguments)
                if (result.statusLabel.isNotBlank()) emit(AiEvent.ToolStatus(result.statusLabel))
                messages += toolMessage(call.id, result.content)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** One request, with a single retry after a 429. */
    private suspend fun roundTrip(config: AiConfig, body: String, emit: suspend (AiEvent) -> Unit): Round {
        var retried = false
        while (true) {
            when (val outcome = exchange(config, body, emit)) {
                is Exchange.Answer -> return outcome.round

                is Exchange.RetryAfter -> {
                    if (retried) throw AiFailure.Busy(config.baseUrl.host)
                    retried = true
                    delay(outcome.seconds * 1000)
                }
            }
        }
    }

    private fun buildRequest(config: AiConfig, body: String): Request {
        val url = config.baseUrl.newBuilder().addPathSegment("chat").addPathSegment("completions").build()
        val builder = Request.Builder().url(url).header("Accept", "text/event-stream").post(body.toRequestBody(JSON_TYPE))
        // The key goes only to the configured host.
        if (url.host == config.baseUrl.host) builder.header("Authorization", "Bearer ${config.key}")
        return builder.build()
    }

    /** One request: the streamed round, or the seconds to wait before retrying a 429. */
    private suspend fun exchange(config: AiConfig, body: String, emit: suspend (AiEvent) -> Unit): Exchange = coroutineScope {
        val host = config.baseUrl.host
        val call = http.newCall(buildRequest(config, body))
        // Blocking reads don't see coroutine cancellation, so cancel the call from here.
        val watcher =
            launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        try {
            val response =
                try {
                    call.execute()
                } catch (_: IOException) {
                    coroutineContext.ensureActive()
                    throw AiFailure.Unreachable(host)
                }
            response.use { r ->
                when (r.code) {
                    200 -> {
                        vault.markValid()
                        Exchange.Answer(stream(r, host, emit))
                    }

                    401, 403 -> {
                        vault.markInvalid()
                        throw AiFailure.KeyRejected
                    }

                    402 -> throw AiFailure.NoCredit(host)

                    404 -> throw AiFailure.ModelGone

                    400 -> throw if (namesModel(r.peekBody(ERROR_PEEK_BYTES).string())) AiFailure.ModelGone else AiFailure.Malformed

                    429 -> Exchange.RetryAfter(retryAfterSeconds(r))

                    in 500..599 -> throw AiFailure.Unreachable(host)

                    else -> throw AiFailure.Malformed
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    /** Streams one response: text deltas go out as they arrive, tool-call deltas are gathered into whole calls. */
    private suspend fun stream(r: Response, host: String, emit: suspend (AiEvent) -> Unit): Round {
        val source = r.body.source()
        val text = StringBuilder()
        val assembler = ToolCallAssembler()
        while (true) {
            val line =
                try {
                    source.readUtf8Line()
                } catch (_: IOException) {
                    coroutineContext.ensureActive()
                    throw AiFailure.Unreachable(host)
                } ?: break
            when (val parsed = parseSseLine(line)) {
                SseLine.Skip -> Unit

                SseLine.Done -> break

                is SseLine.Data -> {
                    val chunk =
                        try {
                            parseChunk(parsed.json)
                        } catch (_: WireException) {
                            throw AiFailure.Malformed
                        }
                    chunk.toolCalls.forEach(assembler::add)
                    chunk.text?.let {
                        text.append(it)
                        emit(AiEvent.Text(it))
                    }
                    // A tool-call finish with nothing to call is as unreadable as a call without a name.
                    if (chunk.finishReason == "tool_calls" && assembler.isEmpty) throw AiFailure.Malformed
                }
            }
        }
        val calls =
            try {
                assembler.build()
            } catch (_: WireException) {
                throw AiFailure.Malformed
            }
        return Round(text.toString(), calls)
    }

    private fun retryAfterSeconds(r: Response): Long = (r.header("Retry-After")?.trim()?.toLongOrNull() ?: DEFAULT_RETRY_SECONDS).coerceIn(0, MAX_RETRY_SECONDS)
}
