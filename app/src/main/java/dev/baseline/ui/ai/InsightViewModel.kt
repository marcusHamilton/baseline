package dev.baseline.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.ai.context.AskScope
import dev.baseline.ai.insight.InsightService
import dev.baseline.ai.insight.InsightState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * The insight card's state for one page (spec §3.8). The page names its [AskScope] with [state]; a new scope (Today's ‹ › day change)
 * switches to it and cancels the old run. Generation only runs while the flow is collected, so it stops when the page leaves the
 * screen, and [InsightService] writes nothing when it is cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightViewModel(insightFor: (AskScope) -> Flow<InsightState>) : ViewModel() {
    constructor(service: InsightService) : this(service::insight)

    private val scope = MutableStateFlow<AskScope?>(null)
    private val retries = MutableStateFlow(0)

    private val states: StateFlow<InsightState> =
        combine(scope, retries) { scope, _ -> scope }
            .flatMapLatest { scope ->
                if (scope == null) {
                    flowOf(InsightState.Hidden)
                } else {
                    // Hidden first, so the previous day's text never shows under the new day's header.
                    insightFor(scope).onStart { emit(InsightState.Hidden) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), InsightState.Hidden)

    fun state(scope: AskScope): StateFlow<InsightState> {
        this.scope.value = scope
        return states
    }

    /** Collects the same page again (the card's Retry link). */
    fun retry() {
        retries.value++
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
