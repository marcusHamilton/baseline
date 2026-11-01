package dev.baseline.ai.insight

import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiMode
import dev.baseline.ai.AiRouter
import dev.baseline.ai.QuestionTag
import dev.baseline.ai.Route
import dev.baseline.ai.RouteInput
import dev.baseline.ai.context.AskScope
import dev.baseline.ai.context.ContextPacks
import dev.baseline.ai.context.Pack
import dev.baseline.ai.context.PackSize
import dev.baseline.ai.context.Prompts
import dev.baseline.ai.context.insightKey
import dev.baseline.ai.engine.AiEngine
import dev.baseline.ai.engine.AiEvent
import dev.baseline.ai.engine.AiFailure
import dev.baseline.ai.engine.AiRequest
import dev.baseline.ai.engine.ChatTurn
import dev.baseline.ai.engine.EngineKind
import dev.baseline.ai.engine.EngineLabel
import dev.baseline.ai.engine.NanoEngine
import dev.baseline.ai.engine.NanoStatus
import dev.baseline.ai.engine.RequestKind
import dev.baseline.ai.engine.Role
import dev.baseline.ai.engine.insightMessage
import dev.baseline.data.db.AiInsightDao
import dev.baseline.data.db.AiInsightEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.security.MessageDigest
import java.time.Clock

/** What an insight card shows (spec §3.8). */
sealed interface InsightState {
    /** No card: mode Off, no engine usable, or nothing to say about the page. */
    data object Hidden : InsightState

    data class Generating(val partial: String) : InsightState

    data class Ready(val text: String, val label: EngineLabel) : InsightState

    data class Failed(val message: String) : InsightState
}

/** Lowercase hex SHA-256 of the prompt version and the pack text. A new version or new data gives a new fingerprint (spec §7.1). */
fun fingerprint(pack: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest("${Prompts.PROMPT_VERSION}\n$pack".toByteArray())
    .joinToString("") { "%02x".format(it) }

private const val NANO_BUSY_RETRY_MILLIS = 1_000L
private const val INSIGHT_USER_TURN = "Write the insight."

/**
 * The insight cards' source (spec §7.1): a cached insight per page, regenerated when the page's data or the prompt version changes.
 * [insight] reads the mode, key and connectivity once when collected; the card collects again on Retry or when the page reopens.
 */
class InsightService(
    private val pack: suspend (AskScope, PackSize) -> Pack,
    private val nano: NanoEngine,
    private val cloud: AiEngine,
    private val dao: AiInsightDao,
    private val mode: Flow<AiMode?>,
    private val keyStatus: Flow<AiKeyStatus>,
    private val online: Flow<Boolean>,
    private val clock: Clock,
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    constructor(
        packs: ContextPacks,
        nano: NanoEngine,
        cloud: AiEngine,
        dao: AiInsightDao,
        mode: Flow<AiMode?>,
        keyStatus: Flow<AiKeyStatus>,
        online: Flow<Boolean>,
        clock: Clock,
    ) : this({ scope, size -> packs.build(scope, size) }, nano, cloud, dao, mode, keyStatus, online, clock)

    private sealed interface Attempt {
        data class Done(val text: String, val label: EngineLabel) : Attempt

        data class Failed(val failure: AiFailure) : Attempt
    }

    /** One generation: the pack and request for one engine. */
    private class Plan(val kind: EngineKind, val pack: Pack, val request: AiRequest)

    /** Cold. Cancelling the collector cancels generation, and nothing is written after that. */
    fun insight(scope: AskScope): Flow<InsightState> = flow {
        val key = scope.insightKey()
        val aiMode = mode.first()
        if (key == null || aiMode == null || aiMode == AiMode.OFF) {
            emit(InsightState.Hidden)
            return@flow
        }
        val keySet = keyStatus.first() is AiKeyStatus.Set
        val isOnline = online.first()
        val system = Prompts.insight(scope)
        val turns = listOf(ChatTurn(Role.USER, INSIGHT_USER_TURN))
        val compact = pack(scope, PackSize.COMPACT)
        val full = pack(scope, PackSize.FULL)
        val nanoPlan = Plan(EngineKind.NANO, compact, AiRequest(RequestKind.INSIGHT, system, compact.text, turns))
        val cloudPlan = Plan(EngineKind.CLOUD, full, AiRequest(RequestKind.INSIGHT, system, full.text, turns))
        val nanoUsable = nano.status.value is NanoStatus.Ready
        val fits = nanoUsable && nano.fits(nanoPlan.request)
        val route =
            AiRouter.route(
                RouteInput(
                    aiMode,
                    QuestionTag.PAGE,
                    firstTurn = true,
                    usedCloud = false,
                    nanoUsable = nanoUsable,
                    fits = fits,
                    keySet = keySet,
                    online = isOnline,
                ),
            )
        if ((!compact.hasData && !full.hasData) || route is Route.Note || route is Route.Error) {
            emit(InsightState.Hidden)
            return@flow
        }

        // The cached text is shown whichever pack it came from, so a mode change alone doesn't regenerate it (spec §7.1).
        val cached = dao.get(key.first, key.second)
        if (cached != null && (cached.fingerprint == fingerprint(compact.text) || cached.fingerprint == fingerprint(full.text))) {
            emit(InsightState.Ready(cached.text, EngineLabel(if (cached.engine == "nano") EngineKind.NANO else EngineKind.CLOUD, cached.model)))
            return@flow
        }

        val hybrid = aiMode == AiMode.HYBRID
        emit(InsightState.Generating(""))
        val first = if (route == Route.Nano) nanoPlan else cloudPlan
        var attempt = run(first)
        if (attempt is Attempt.Failed) {
            val failure = attempt.failure
            val second =
                when {
                    failure == AiFailure.NanoBackground -> null

                    !hybrid -> null

                    first.kind == EngineKind.NANO -> if (keySet && isOnline) cloudPlan else null

                    failure is AiFailure.NoCredit || failure is AiFailure.Unreachable || failure == AiFailure.KeyRejected ->
                        if (fits) nanoPlan else null

                    else -> null
                }
            if (second != null) {
                emit(InsightState.Generating(""))
                val next = run(second)
                // Nano is the engine the user preferred; when both fail, the cloud's reason is the one worth showing.
                attempt = if (next is Attempt.Failed && second.kind == EngineKind.NANO) attempt else next
                if (next is Attempt.Failed && next.failure == AiFailure.NanoBackground) return@flow
            }
        }
        when (attempt) {
            is Attempt.Done -> {
                val used = if (attempt.label.kind == EngineKind.NANO) nanoPlan else cloudPlan
                dao.put(
                    AiInsightEntity(
                        key.first,
                        key.second,
                        fingerprint(used.pack.text),
                        attempt.text,
                        if (attempt.label.kind == EngineKind.NANO) "nano" else "cloud",
                        attempt.label.model,
                        clock.instant(),
                    ),
                )
                emit(InsightState.Ready(attempt.text, attempt.label))
            }

            is Attempt.Failed -> if (attempt.failure != AiFailure.NanoBackground) emit(InsightState.Failed(attempt.failure.insightMessage()))
        }
    }

    suspend fun clearAll() = dao.clear()

    /** Runs [plan] on its engine, streaming `Generating(partial)`. A Nano `BUSY` is retried once after a second. */
    private suspend fun FlowCollector<InsightState>.run(plan: Plan): Attempt {
        var retried = false
        while (true) {
            val attempt = stream(plan)
            if (attempt is Attempt.Failed && attempt.failure == AiFailure.NanoBusy && !retried) {
                retried = true
                delay(NANO_BUSY_RETRY_MILLIS)
                emit(InsightState.Generating(""))
                continue
            }
            return attempt
        }
    }

    private suspend fun FlowCollector<InsightState>.stream(plan: Plan): Attempt {
        val engine: AiEngine = if (plan.kind == EngineKind.NANO) nano else cloud
        val text = StringBuilder()
        var label: EngineLabel? = null
        var failure: AiFailure? = null
        engine
            .run(plan.request)
            .catch { e ->
                // Only what the engine threw: errors from emit() below pass through untouched.
                if (e is CancellationException) throw e
                failure = e as? AiFailure ?: AiFailure.Malformed
            }.collect { event ->
                when (event) {
                    is AiEvent.Text -> {
                        text.append(event.delta)
                        emit(InsightState.Generating(text.toString()))
                    }

                    is AiEvent.Done -> label = event.label

                    is AiEvent.ToolStatus -> Unit
                }
            }
        failure?.let { return Attempt.Failed(it) }
        val answer = text.toString().trim()
        if (answer.isEmpty()) return Attempt.Failed(AiFailure.Malformed)
        return Attempt.Done(answer, label ?: EngineLabel(plan.kind, null))
    }
}
