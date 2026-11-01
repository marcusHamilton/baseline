package dev.baseline.ai.engine

import kotlinx.coroutines.flow.Flow

enum class NanoAvailability { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

enum class NanoError { BUSY, QUOTA, BACKGROUND, NOT_AVAILABLE, OTHER }

/** What a [NanoBackend] throws. Carries only the category, never provider text (spec: AI assistant §4.1, §8). */
class NanoBackendException(val error: NanoError) : Exception()

/** A thin seam over ML Kit's GenAI Prompt API, so tests use a fake and ML Kit is never loaded on the JVM (spec: AI assistant §4.1). */
interface NanoBackend {
    suspend fun availability(): NanoAvailability

    /** Progress 0..1, or null while unknown; completes when the model is downloaded. */
    fun download(): Flow<Float?>

    suspend fun baseModelName(): String

    /** Input plus output tokens combined. */
    suspend fun tokenLimit(): Int

    suspend fun systemPromptSupported(): Boolean

    /** [system] is used only when [systemAsInstruction] is true; otherwise it is already inside the first of [turns]. */
    suspend fun countTokens(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean): Int

    /** Each emission is the next piece of text, not the text so far. */
    fun stream(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int, temperature: Float): Flow<String>

    suspend fun warmup()
}
