package dev.baseline.ai

/** What a question is for, which decides where the router sends it (spec §6). A typed question is [PAGE]. */
enum class QuestionTag { PAGE, TRAINING, PATTERN }

/** Everything the routing table looks at for one turn (spec §6). An `Invalid` key still counts as [keySet], so it can heal. */
data class RouteInput(
    val mode: AiMode,
    val tag: QuestionTag,
    val firstTurn: Boolean,
    val usedCloud: Boolean,
    val nanoUsable: Boolean,
    val fits: Boolean,
    val keySet: Boolean,
    val online: Boolean,
) {
    val cloudUsable get() = keySet && online
}

sealed interface Route {
    data object Nano : Route

    data object Cloud : Route

    /** Shown as a muted line in place of an answer. */
    data class Note(val text: String) : Route

    /** Shown in place of an answer, with Try again. */
    data class Error(val text: String) : Route
}

/** The routing table of spec §6, in order. */
object AiRouter {
    private const val UNAVAILABLE = "AI isn't available right now."
    private const val OFFLINE = "You're offline."
    private const val ON_DEVICE_OVERFLOW = "This needs more room than on-device AI allows. Try a shorter question, or add an API key in Settings."
    private const val HYBRID_OFFLINE_OVERFLOW = "You're offline, and this needs more than on-device AI can hold."
    private const val HISTORY_ON_DEVICE = "On-device AI can only see this page. Add an API key in Settings for questions about your history"
    private const val HISTORY_OFFLINE = "You're offline; this needs your history"

    fun route(i: RouteInput): Route = when (i.mode) {
        // The UI never asks while Off.
        AiMode.OFF -> Route.Error(UNAVAILABLE)

        AiMode.ON_DEVICE ->
            when {
                !i.nanoUsable -> Route.Error(UNAVAILABLE)
                i.fits -> Route.Nano
                else -> Route.Note(ON_DEVICE_OVERFLOW)
            }

        AiMode.CLOUD ->
            when {
                !i.online -> Route.Error(OFFLINE)
                !i.keySet -> Route.Error(UNAVAILABLE)
                else -> Route.Cloud
            }

        AiMode.HYBRID -> hybrid(i)
    }

    private fun hybrid(i: RouteInput): Route = when {
        // 1. A chat that has used the cloud stays there. 2. So does any follow-up. 3. Training and pattern questions go there too.
        i.cloudUsable && (i.usedCloud || !i.firstTurn || i.tag != QuestionTag.PAGE) -> Route.Cloud

        i.nanoUsable && i.fits -> Route.Nano

        i.cloudUsable -> Route.Cloud

        i.nanoUsable -> Route.Note(HYBRID_OFFLINE_OVERFLOW)

        else -> Route.Error(UNAVAILABLE)
    }

    /** Where a turn goes once Nano has answered `NEEDS_HISTORY`. */
    fun afterNeedsHistory(i: RouteInput): Route = when {
        i.mode == AiMode.HYBRID && i.cloudUsable -> Route.Cloud
        i.mode == AiMode.HYBRID -> Route.Note(HISTORY_OFFLINE)
        i.mode == AiMode.ON_DEVICE -> Route.Note(HISTORY_ON_DEVICE)
        else -> Route.Error(UNAVAILABLE)
    }
}
