package dev.baseline.ai

import dev.baseline.ai.context.AskScope
import dev.baseline.ai.context.ContextPacks
import dev.baseline.ai.context.PackSize
import dev.baseline.ai.context.Prompts
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
import dev.baseline.ai.engine.chatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * One line of the chat (spec §3.6). A user message has only [text]. An answer has a [label] once it is complete; while it streams,
 * [streaming] is true and [status] is the tool line currently running. A note ([note]) or an error ([error], with empty [text])
 * stands in for an answer. [canMoreDetail] is set by [ChatSession] when an answer completes.
 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val label: EngineLabel? = null,
    val lookedUp: List<String> = emptyList(),
    val status: String? = null,
    val error: String? = null,
    val note: Boolean = false,
    val streaming: Boolean = false,
    val canMoreDetail: Boolean = false,
)

private const val NANO_BUSY_RETRY_MILLIS = 1_000L

/**
 * The in-memory chat behind the Ask sheet (spec §6, §3.6): it routes each turn, streams the answer, escalates `NEEDS_HISTORY`, and
 * applies the fallbacks. The caller (the ViewModel) runs [send], [moreDetail] and [retry] in its own scope and cancels them when the
 * sheet closes; a call made while one is running is ignored.
 */
class ChatSession(
    private val scope: AskScope,
    private val pack: suspend (AskScope, PackSize) -> String,
    private val nano: NanoEngine,
    private val cloud: AiEngine,
    private val mode: () -> AiMode,
    private val keySet: () -> Boolean,
    private val online: () -> Boolean,
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    constructor(
        scope: AskScope,
        packs: ContextPacks,
        nano: NanoEngine,
        cloud: AiEngine,
        mode: () -> AiMode,
        keySet: () -> Boolean,
        online: () -> Boolean,
        delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ) : this(scope, { s, size -> packs.build(s, size).text }, nano, cloud, mode, keySet, online, delay)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _busy = MutableStateFlow(false)

    /** True while a turn runs; the sheet disables its input meanwhile. */
    val busy: StateFlow<Boolean> = _busy

    /** What [retry] re-runs: the turn, and how many messages came before its answer. */
    private class Pending(val question: String, val tag: QuestionTag, val firstTurn: Boolean, val forceCloud: Boolean, val base: Int)

    private sealed interface Outcome {
        data class Answered(val label: EngineLabel?) : Outcome

        data object NeedsHistory : Outcome

        data class Failed(val failure: AiFailure) : Outcome
    }

    private var usedCloud = false
    private var turnCount = 0
    private var pending: Pending? = null
    private var compactPack: String? = null
    private var fullPack: String? = null

    suspend fun send(question: String, tag: QuestionTag) {
        if (question.isBlank() || !_busy.compareAndSet(false, true)) return
        try {
            turnCount++
            _messages.update { it + ChatMessage(Role.USER, question) }
            val turn = Pending(question, tag, firstTurn = turnCount == 1, forceCloud = false, base = _messages.value.size)
            pending = turn
            execute(turn)
        } finally {
            _busy.value = false
        }
    }

    /** Re-asks the question before the answer at [index] on the cloud, with the conversation up to that answer. The chat becomes cloud. */
    suspend fun moreDetail(index: Int) {
        if (!_busy.compareAndSet(false, true)) return
        try {
            val all = _messages.value
            val answer = all.getOrNull(index)
            if (answer == null || answer.role != Role.ASSISTANT || answer.note || answer.error != null) return
            val question = all.subList(0, index).lastOrNull { it.role == Role.USER }?.text ?: return
            _messages.update { list -> list.mapIndexed { i, m -> if (i == index) m.copy(canMoreDetail = false) else m } }
            val turn = Pending(question, QuestionTag.PAGE, firstTurn = false, forceCloud = true, base = index + 1)
            pending = turn
            execute(turn)
        } finally {
            _busy.value = false
        }
    }

    /** Runs the last turn again, dropping whatever it produced (normally an error or a note). */
    suspend fun retry() {
        val turn = pending ?: return
        if (!_busy.compareAndSet(false, true)) return
        try {
            _messages.update { it.take(turn.base) }
            execute(turn)
        } finally {
            _busy.value = false
        }
    }

    private suspend fun execute(turn: Pending) {
        try {
            val route =
                if (turn.forceCloud) {
                    when {
                        !online() -> Route.Error(AiFailure.Offline.chatMessage())
                        !keySet() -> Route.Error("AI isn't available right now.")
                        else -> Route.Cloud
                    }
                } else {
                    AiRouter.route(input(turn, fitsFor(turn)))
                }
            when (route) {
                is Route.Note -> append(ChatMessage(Role.ASSISTANT, route.text, note = true))

                is Route.Error -> append(errorMessage(route.text))

                Route.Nano -> {
                    append(inProgress())
                    runNano(turn)
                }

                Route.Cloud -> {
                    usedCloud = true
                    append(inProgress())
                    runCloud(turn, nanoFallback = !turn.forceCloud)
                }
            }
        } catch (e: CancellationException) {
            if (_messages.value.lastOrNull()?.streaming == true) removeLast()
            throw e
        }
    }

    private suspend fun runNano(turn: Pending) {
        when (val outcome = streamNano(nanoRequest(turn))) {
            is Outcome.Answered -> finish(outcome.label)

            Outcome.NeedsHistory ->
                when (val next = AiRouter.afterNeedsHistory(input(turn, fits = true))) {
                    Route.Cloud -> {
                        usedCloud = true
                        runCloud(turn, nanoFallback = false)
                    }

                    is Route.Note -> replaceLast(ChatMessage(Role.ASSISTANT, next.text, note = true))

                    is Route.Error -> replaceLast(errorMessage(next.text))

                    Route.Nano -> replaceLast(errorMessage("AI isn't available right now."))
                }

            is Outcome.Failed -> {
                val failure = outcome.failure
                when {
                    failure == AiFailure.NanoBackground -> removeLast()

                    mode() == AiMode.HYBRID && keySet() && online() -> {
                        usedCloud = true
                        runCloud(turn, nanoFallback = false)
                    }

                    else -> replaceLast(errorMessage(failure.chatMessage()))
                }
            }
        }
    }

    private suspend fun runCloud(turn: Pending, nanoFallback: Boolean) {
        val outcome = streamCloud(cloudRequest(turn))
        if (outcome !is Outcome.Failed) {
            finish((outcome as Outcome.Answered).label)
            return
        }
        val failure = outcome.failure
        val canFallBack =
            nanoFallback &&
                mode() == AiMode.HYBRID &&
                turn.tag == QuestionTag.PAGE &&
                (failure is AiFailure.NoCredit || failure is AiFailure.Unreachable)
        val request = if (canFallBack) nanoRequest(turn) else null
        if (request == null || !nanoUsable() || !nano.fits(request)) {
            replaceLast(errorMessage(failure.chatMessage()))
            return
        }
        when (val fallback = streamNano(request)) {
            is Outcome.Answered -> {
                val host = (failure as? AiFailure.Unreachable)?.host
                finish(fallback.label, suffix = host?.let { "\n\n(answered on-device; couldn't reach $it)" }.orEmpty())
            }

            Outcome.NeedsHistory -> replaceLast(errorMessage(failure.chatMessage()))

            is Outcome.Failed ->
                if (fallback.failure == AiFailure.NanoBackground) removeLast() else replaceLast(errorMessage(failure.chatMessage()))
        }
    }

    /**
     * Streams Nano into the in-progress (last) message. The text is held back while it could still become `NEEDS_HISTORY`, and a
     * `BUSY` is retried once after a second.
     */
    private suspend fun streamNano(req: AiRequest): Outcome {
        var retried = false
        while (true) {
            replaceLast(inProgress())
            val text = StringBuilder()
            var released = false
            var label: EngineLabel? = null
            try {
                nano.run(req).collect { event ->
                    when (event) {
                        is AiEvent.Text -> {
                            text.append(event.delta)
                            if (!released && !Prompts.NEEDS_HISTORY.startsWith(escalationForm(text))) released = true
                            if (released) updateLast { it.copy(text = text.toString()) }
                        }

                        is AiEvent.Done -> label = event.label

                        is AiEvent.ToolStatus -> Unit
                    }
                }
                if (!released && escalationForm(text) == Prompts.NEEDS_HISTORY) return Outcome.NeedsHistory
                updateLast { it.copy(text = text.toString()) }
                return Outcome.Answered(label)
            } catch (e: AiFailure) {
                if (e == AiFailure.NanoBusy && !retried) {
                    retried = true
                    delay(NANO_BUSY_RETRY_MILLIS)
                    continue
                }
                return Outcome.Failed(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Outcome.Failed(AiFailure.Malformed)
            }
        }
    }

    /** The reply as compared with `NEEDS_HISTORY`: without surrounding whitespace or the full stop or "!" Nano may add. */
    private fun escalationForm(text: CharSequence) = text.toString().trim().trimEnd('.', '!').trimEnd()

    /** Streams the cloud into the in-progress (last) message, with the tool lines: running in [ChatMessage.status], then in [ChatMessage.lookedUp]. */
    private suspend fun streamCloud(req: AiRequest): Outcome {
        replaceLast(inProgress())
        var label: EngineLabel? = null
        var afterTool = false
        try {
            cloud.run(req).collect { event ->
                when (event) {
                    is AiEvent.Text ->
                        updateLast { m ->
                            val gap = if (afterTool && m.text.isNotEmpty()) "\n\n" else ""
                            afterTool = false
                            settle(m).copy(text = m.text + gap + event.delta)
                        }

                    is AiEvent.ToolStatus -> {
                        afterTool = true
                        updateLast { m -> settle(m).copy(status = event.label) }
                    }

                    is AiEvent.Done -> label = event.label
                }
            }
            return Outcome.Answered(label)
        } catch (e: AiFailure) {
            return Outcome.Failed(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Failed(AiFailure.Malformed)
        }
    }

    /** Moves a finished tool line from [ChatMessage.status] to [ChatMessage.lookedUp]. */
    private fun settle(m: ChatMessage): ChatMessage {
        val s = m.status ?: return m
        return m.copy(status = null, lookedUp = if (s in m.lookedUp) m.lookedUp else m.lookedUp + s)
    }

    private fun finish(label: EngineLabel?, suffix: String = "") = updateLast { m ->
        settle(m).copy(
            text = m.text + suffix,
            label = label,
            streaming = false,
            canMoreDetail = label?.kind == EngineKind.NANO && mode() == AiMode.HYBRID && keySet() && online(),
        )
    }

    private fun input(turn: Pending, fits: Boolean) = RouteInput(
        mode = mode(),
        tag = turn.tag,
        firstTurn = turn.firstTurn,
        usedCloud = usedCloud,
        nanoUsable = nanoUsable(),
        fits = fits,
        keySet = keySet(),
        online = online(),
    )

    private fun nanoUsable() = nano.status.value is NanoStatus.Ready

    /** Whether Nano could hold this turn. Skipped when the router will not look at it. */
    private suspend fun fitsFor(turn: Pending): Boolean {
        if (!nanoUsable()) return false
        val m = mode()
        if (m != AiMode.ON_DEVICE && m != AiMode.HYBRID) return false
        val cloudDecides = m == AiMode.HYBRID && keySet() && online() && (usedCloud || !turn.firstTurn || turn.tag != QuestionTag.PAGE)
        return !cloudDecides && nano.fits(nanoRequest(turn))
    }

    private suspend fun nanoRequest(turn: Pending): AiRequest {
        val text = compactPack ?: pack(scope, PackSize.COMPACT).also { compactPack = it }
        return AiRequest(RequestKind.CHAT, Prompts.chat(EngineKind.NANO), text, turnsFor(turn))
    }

    private suspend fun cloudRequest(turn: Pending): AiRequest {
        val text = fullPack ?: pack(scope, PackSize.FULL).also { fullPack = it }
        return AiRequest(RequestKind.CHAT, Prompts.chat(EngineKind.CLOUD), text, turnsFor(turn))
    }

    /**
     * The completed conversation before the answer, as turns, ending with the question. Notes, errors and unfinished answers are
     * skipped; of two turns in a row by the same role (an unanswered question, or an answer that "More detail" replaced) the later wins.
     */
    private fun turnsFor(turn: Pending): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        for (m in _messages.value.take(turn.base)) {
            if (m.note || m.error != null || m.streaming || m.text.isBlank()) continue
            if (turns.lastOrNull()?.role == m.role) turns.removeAt(turns.lastIndex)
            turns += ChatTurn(m.role, m.text)
        }
        if (turns.lastOrNull()?.role != Role.USER) turns += ChatTurn(Role.USER, turn.question)
        return turns
    }

    private fun inProgress() = ChatMessage(Role.ASSISTANT, "", streaming = true)

    private fun errorMessage(text: String) = ChatMessage(Role.ASSISTANT, "", error = text)

    private fun append(m: ChatMessage) = _messages.update { it + m }

    private fun removeLast() = _messages.update { it.dropLast(1) }

    private fun replaceLast(m: ChatMessage) = _messages.update { it.dropLast(1) + m }

    private fun updateLast(f: (ChatMessage) -> ChatMessage) = _messages.update { list -> list.dropLast(1) + f(list.last()) }
}
