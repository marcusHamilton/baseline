package dev.baseline.ai.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

sealed interface NanoStatus {
    data object Unavailable : NanoStatus

    data object Downloadable : NanoStatus

    data class Downloading(val progress: Float?) : NanoStatus

    data class Ready(val modelName: String, val tokenLimit: Int) : NanoStatus

    /** The daily quota is used up; ends at [until]. */
    data class Resting(val until: Instant) : NanoStatus
}

private const val CHAT_MAX_OUTPUT = 256
private const val INSIGHT_MAX_OUTPUT = 160
private const val TEMPERATURE = 0.2f
private const val TIMEOUT_MILLIS = 60_000L

/** The request as the backend takes it: [system] either goes in as an instruction or is already part of the first user turn. */
private class Prepared(val system: String, val turns: List<ChatTurn>, val asInstruction: Boolean)

/**
 * Gemini Nano behind [NanoBackend] (spec: AI assistant §4.1). It does no retries: `ChatSession` owns the `BUSY` retry.
 */
class NanoEngine(
    private val backend: NanoBackend,
    private val clock: Clock,
    private val zone: StateFlow<ZoneId>,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) : AiEngine {
    private val _status = MutableStateFlow<NanoStatus>(NanoStatus.Unavailable)
    private val _known = MutableStateFlow(false)

    /** Starts [NanoStatus.Unavailable] until [refresh] has run. */
    val status: StateFlow<NanoStatus> = _status

    /** False until the first [refresh] completes, so callers can tell "not asked yet" from "not supported". */
    val known: StateFlow<Boolean> = _known

    private val _lastCheckFailed = MutableStateFlow(false)

    /**
     * True when the latest [refresh] threw instead of getting an answer. [status] is then [NanoStatus.Unavailable] so nothing uses
     * Nano, but that is not a verdict on the phone: callers must not change the user's settings because of it.
     */
    val lastCheckFailed: StateFlow<Boolean> = _lastCheckFailed

    // Kept across Resting, which has no model name of its own.
    @Volatile private var modelName: String? = null

    suspend fun refresh() {
        val resting = _status.value as? NanoStatus.Resting
        if (resting != null && clock.instant() < resting.until) {
            _known.value = true
            return
        }
        var failed = false
        _status.value =
            try {
                when (backend.availability()) {
                    NanoAvailability.UNAVAILABLE -> NanoStatus.Unavailable
                    NanoAvailability.DOWNLOADABLE -> NanoStatus.Downloadable
                    NanoAvailability.DOWNLOADING -> NanoStatus.Downloading(null)
                    NanoAvailability.AVAILABLE -> NanoStatus.Ready(backend.baseModelName(), backend.tokenLimit()).also { modelName = it.modelName }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // AICore missing or misbehaving (a beta library): treat as not supported rather than crash.
                failed = true
                NanoStatus.Unavailable
            }
        _lastCheckFailed.value = failed
        _known.value = true
    }

    /** Drives [status] through [NanoStatus.Downloading] and back to [NanoStatus.Ready] once the model is there. */
    fun download(): Flow<Float?> = backend
        .download()
        .onStart { _status.value = NanoStatus.Downloading(null) }
        .onEach { _status.value = NanoStatus.Downloading(it) }
        .onCompletion { cause -> if (cause == null || cause is NanoBackendException) refresh() }

    /** True when the request plus room for the answer fits the model's combined input and output limit. */
    suspend fun fits(req: AiRequest): Boolean {
        val ready = _status.value as? NanoStatus.Ready ?: return false
        val tokens =
            try {
                val prepared = prepare(req)
                backend.countTokens(prepared.system, prepared.turns, prepared.asInstruction)
            } catch (e: NanoBackendException) {
                return false
            }
        return tokens + maxOutput(req.kind) <= ready.tokenLimit
    }

    suspend fun warmup() {
        if (_status.value !is NanoStatus.Ready) return
        try {
            backend.warmup()
        } catch (e: NanoBackendException) {
            // Only a head start; the real request reports any problem.
        }
    }

    override fun run(req: AiRequest): Flow<AiEvent> = flow {
        if (!_known.value) refresh()
        val resting = _status.value as? NanoStatus.Resting
        if (resting != null) {
            if (clock.instant() < resting.until) throw AiFailure.NanoQuota
            refresh()
        }
        if (_status.value !is NanoStatus.Ready) throw AiFailure.NanoUnavailable
        try {
            val prepared = prepare(req)
            withTimeout(timeoutMillis) {
                backend
                    .stream(prepared.system, prepared.turns, prepared.asInstruction, maxOutput(req.kind), TEMPERATURE)
                    .collect { emit(AiEvent.Text(it)) }
            }
        } catch (e: TimeoutCancellationException) {
            throw AiFailure.NanoBusy
        } catch (e: NanoBackendException) {
            throw failureFor(e.error)
        }
        emit(AiEvent.Done(EngineLabel(EngineKind.NANO, modelName ?: backend.baseModelName())))
    }

    private fun failureFor(error: NanoError): AiFailure = when (error) {
        NanoError.BUSY -> AiFailure.NanoBusy

        NanoError.QUOTA -> {
            _status.value = NanoStatus.Resting(nextLocalMidnight())
            AiFailure.NanoQuota
        }

        NanoError.BACKGROUND -> AiFailure.NanoBackground

        NanoError.NOT_AVAILABLE -> AiFailure.NanoUnavailable

        NanoError.OTHER -> AiFailure.Malformed
    }

    private fun nextLocalMidnight(): Instant {
        val z = zone.value
        return clock
            .instant()
            .atZone(z)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(z)
            .toInstant()
    }

    private fun maxOutput(kind: RequestKind) = if (kind == RequestKind.CHAT) CHAT_MAX_OUTPUT else INSIGHT_MAX_OUTPUT

    private suspend fun prepare(req: AiRequest): Prepared {
        val system = req.system + "\n\n" + req.pack
        if (backend.systemPromptSupported()) return Prepared(system, req.turns, true)
        val first = req.turns.firstOrNull()
        val turns =
            if (first != null && first.role == Role.USER) {
                listOf(first.copy(text = system + "\n\n" + first.text)) + req.turns.drop(1)
            } else {
                listOf(ChatTurn(Role.USER, system)) + req.turns
            }
        return Prepared(system, turns, false)
    }
}
