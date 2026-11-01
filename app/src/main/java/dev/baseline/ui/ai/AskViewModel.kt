package dev.baseline.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.ai.AiMode
import dev.baseline.ai.ChatMessage
import dev.baseline.ai.ChatSession
import dev.baseline.ai.QuestionTag
import dev.baseline.ai.context.AskScope
import dev.baseline.ai.context.ContextPacks
import dev.baseline.ai.context.PackFacts
import dev.baseline.ai.context.PackSize
import dev.baseline.ai.context.Suggestion
import dev.baseline.ai.context.Suggestions
import dev.baseline.ai.context.subject
import dev.baseline.ai.context.title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Everything the Ask sheet draws (spec §3.6). [suggestions] are always filled in; the sheet shows them only before the first message. */
data class AskUiState(
    val title: String,
    val hint: String,
    val placeholder: String,
    val suggestions: List<Suggestion>,
    val messages: List<ChatMessage>,
    val busy: Boolean,
)

private val NO_FACTS = PackFacts(null, null, null, null)

/**
 * The Ask sheet's ViewModel. The chat lives in [session], so it survives rotation; clearing the ViewModel (the sheet's back stack owner
 * going away) cancels [viewModelScope] and with it any request in flight, which discards the chat (spec §3.6).
 */
class AskViewModel(
    private val scope: AskScope,
    private val session: ChatSession,
    private val facts: suspend (AskScope) -> PackFacts,
    mode: Flow<AiMode?>,
    online: Flow<Boolean>,
    today: StateFlow<LocalDate>,
) : ViewModel() {
    constructor(
        scope: AskScope,
        session: ChatSession,
        packs: ContextPacks,
        mode: Flow<AiMode?>,
        online: Flow<Boolean>,
        today: StateFlow<LocalDate>,
    ) : this(scope, session, { packs.build(it, PackSize.COMPACT).facts }, mode, online, today)

    /** Null until the numbers are in, so the chips don't flash the fact-free versions first. */
    private val packFacts = MutableStateFlow<PackFacts?>(null)

    init {
        viewModelScope.launch {
            packFacts.value =
                try {
                    facts(scope)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NO_FACTS
                }
        }
    }

    val state: StateFlow<AskUiState> =
        combine(session.messages, session.busy, mode, online, combine(today, packFacts) { d, f -> d to f }) { messages, busy, m, on, (day, f) ->
            val subject = scope.subject(day)
            val cloudSlots = m == AiMode.CLOUD || (m == AiMode.HYBRID && on)
            AskUiState(
                title = scope.title(day),
                hint = "Ask anything about $subject, or how it fits your training and longer trends.",
                placeholder = if (messages.isEmpty()) "Ask about $subject…" else "Ask a follow-up…",
                suggestions = f?.let { Suggestions.forScope(scope, it, cloudSlots) }.orEmpty(),
                messages = messages,
                busy = busy,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState(today.value))

    private fun initialState(day: LocalDate): AskUiState = AskUiState(
        title = scope.title(day),
        hint = "Ask anything about ${scope.subject(day)}, or how it fits your training and longer trends.",
        placeholder = "Ask about ${scope.subject(day)}…",
        suggestions = emptyList(),
        messages = session.messages.value,
        busy = session.busy.value,
    )

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty()) return
        viewModelScope.launch { session.send(question, QuestionTag.PAGE) }
    }

    fun pick(s: Suggestion) {
        viewModelScope.launch { session.send(s.text, s.tag) }
    }

    fun moreDetail(index: Int) {
        viewModelScope.launch { session.moreDetail(index) }
    }

    fun retry() {
        viewModelScope.launch { session.retry() }
    }
}
