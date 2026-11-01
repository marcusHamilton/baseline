package dev.baseline.ai.engine

import kotlinx.coroutines.flow.Flow

enum class EngineKind { NANO, CLOUD }

/** Which engine answered, as shown under an answer: "On-device" or "Cloud · <model id after the last '/'>". */
data class EngineLabel(val kind: EngineKind, val model: String?) {
    val text: String
        get() =
            when (kind) {
                EngineKind.NANO -> "On-device"
                EngineKind.CLOUD -> "Cloud · ${model.orEmpty().substringAfterLast('/')}"
            }
}

enum class Role { USER, ASSISTANT }

data class ChatTurn(val role: Role, val text: String)

enum class RequestKind { CHAT, INSIGHT }

/** Engines send [system] + "\n\n" + [pack] as the system message, then [turns]. */
data class AiRequest(val kind: RequestKind, val system: String, val pack: String, val turns: List<ChatTurn>)

sealed interface AiEvent {
    data class Text(val delta: String) : AiEvent

    data class ToolStatus(val label: String) : AiEvent

    data class Done(val label: EngineLabel) : AiEvent
}

/** Thrown by engines (spec: AI assistant §8). Built from status codes only: [message] is never set, so no provider data or key can leak through it. */
sealed class AiFailure : Exception() {
    data object KeyRejected : AiFailure()

    data class NoCredit(val host: String) : AiFailure()

    data object ModelGone : AiFailure()

    data class Busy(val host: String) : AiFailure()

    data class Unreachable(val host: String) : AiFailure()

    data object Offline : AiFailure()

    data object Malformed : AiFailure()

    data object NanoBusy : AiFailure()

    data object NanoQuota : AiFailure()

    /** The user left; the request is cancelled silently, so there is nothing to show. */
    data object NanoBackground : AiFailure()

    data object NanoUnavailable : AiFailure()
}

private const val MODEL_GONE = "This model is no longer available. Pick another in Settings."
private const val NANO_BUSY = "On-device AI is busy. Try again in a moment."
private const val NANO_QUOTA = "On-device AI has reached today's limit."
private const val NANO_UNAVAILABLE = "On-device AI isn't available on this phone right now."

fun AiFailure.chatMessage(): String = when (this) {
    AiFailure.KeyRejected -> "Your API key was rejected. Check it in Settings."
    is AiFailure.NoCredit -> "No credit left at $host."
    AiFailure.ModelGone -> MODEL_GONE
    is AiFailure.Busy -> "$host is busy. Try again shortly."
    is AiFailure.Unreachable -> "Couldn't reach $host."
    AiFailure.Offline -> "You're offline."
    AiFailure.Malformed -> "Something went wrong reading the answer."
    AiFailure.NanoBusy -> NANO_BUSY
    AiFailure.NanoQuota -> NANO_QUOTA
    AiFailure.NanoBackground -> ""
    AiFailure.NanoUnavailable -> NANO_UNAVAILABLE
}

fun AiFailure.insightMessage(): String = when (this) {
    AiFailure.KeyRejected -> "API key rejected"
    is AiFailure.NoCredit -> "No credit left at $host"
    AiFailure.ModelGone -> MODEL_GONE
    is AiFailure.Busy -> "$host is busy. Try again shortly."
    is AiFailure.Unreachable -> "Couldn't reach $host"
    AiFailure.Offline -> "You're offline"
    AiFailure.Malformed -> "Couldn't generate"
    AiFailure.NanoBusy -> NANO_BUSY
    AiFailure.NanoQuota -> NANO_QUOTA
    AiFailure.NanoBackground -> ""
    AiFailure.NanoUnavailable -> NANO_UNAVAILABLE
}

/** Errors are thrown as [AiFailure]. */
interface AiEngine {
    fun run(req: AiRequest): Flow<AiEvent>
}
