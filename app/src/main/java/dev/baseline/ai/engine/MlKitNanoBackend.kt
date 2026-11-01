package dev.baseline.ai.engine

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.content
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [NanoBackend] on ML Kit's GenAI Prompt API (`genai-prompt` 1.0.0-beta, running on AICore). This is the only file that imports
 * `com.google.mlkit`; ML Kit can't load on the JVM, so it is exercised on a device only (spec: AI assistant §2.1, §4.1).
 */
class MlKitNanoBackend : NanoBackend {
    private val model: GenerativeModel by lazy { Generation.getClient() }

    override suspend fun availability(): NanoAvailability = guarded {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoAvailability.AVAILABLE
            FeatureStatus.DOWNLOADING -> NanoAvailability.DOWNLOADING
            FeatureStatus.DOWNLOADABLE -> NanoAvailability.DOWNLOADABLE
            else -> NanoAvailability.UNAVAILABLE
        }
    }

    override fun download(): Flow<Float?> = flow {
        var total = 0L
        model.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadStarted -> {
                    total = status.bytesToDownload
                    emit(null)
                }

                is DownloadStatus.DownloadProgress -> emit(if (total > 0) (status.totalBytesDownloaded.toFloat() / total).coerceIn(0f, 1f) else null)

                is DownloadStatus.DownloadCompleted -> emit(1f)

                is DownloadStatus.DownloadFailed -> throw NanoBackendException(errorOf(status.e))
            }
        }
    }.catch { throw if (it is GenAiException) NanoBackendException(errorOf(it)) else it }

    override suspend fun baseModelName(): String = guarded { model.getBaseModelName() }

    override suspend fun tokenLimit(): Int = guarded { model.getTokenLimit() }

    override suspend fun systemPromptSupported(): Boolean = guarded { model.isSystemPromptAvailable() }

    override suspend fun countTokens(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean): Int =
        guarded { model.countTokens(request(system, turns, systemAsInstruction)).totalTokens }

    override fun stream(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int, temperature: Float): Flow<String> = model
        .generateContentStream(request(system, turns, systemAsInstruction, maxOutput, temperature))
        .map { it.candidates.firstOrNull()?.text.orEmpty() }
        .filter { it.isNotEmpty() }
        .catch { throw if (it is GenAiException) NanoBackendException(errorOf(it)) else it }

    override suspend fun warmup() = guarded { model.warmup() }

    private fun request(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int? = null, temperature: Float? = null) =
        GenerateContentRequest
            .Builder(
                turns.map { turn ->
                    content(if (turn.role == Role.USER) Content.Role.USER else Content.Role.MODEL) { text(turn.text) }
                },
            ).apply {
                if (systemAsInstruction) systemInstruction = SystemInstruction(system)
                if (maxOutput != null) maxOutputTokens = maxOutput
                if (temperature != null) this.temperature = temperature
            }.build()

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: GenAiException) {
        throw NanoBackendException(errorOf(e))
    }

    private fun errorOf(e: GenAiException): NanoError = when (e.errorCode) {
        GenAiException.ErrorCode.BUSY -> NanoError.BUSY

        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> NanoError.QUOTA

        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> NanoError.BACKGROUND

        GenAiException.ErrorCode.NOT_AVAILABLE,
        GenAiException.ErrorCode.NOT_SUPPORTED,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
        -> NanoError.NOT_AVAILABLE

        else -> NanoError.OTHER
    }
}
