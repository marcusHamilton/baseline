package dev.baseline.ui.ai

import dev.baseline.ai.context.AskScope
import dev.baseline.ai.engine.EngineKind
import dev.baseline.ai.engine.EngineLabel
import dev.baseline.ai.insight.InsightState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class InsightViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val label = EngineLabel(EngineKind.NANO, null)
    private val started = mutableListOf<AskScope>()
    private var cancelled = 0
    private var gate: CompletableDeferred<Unit>? = null

    private fun insight(scope: AskScope): Flow<InsightState> = flow {
        started += scope
        try {
            emit(InsightState.Generating(""))
            gate?.await()
            emit(InsightState.Ready("Insight for $scope", label))
            awaitCancellation()
        } finally {
            cancelled++
        }
    }

    private val a = AskScope.Today(LocalDate.of(2026, 11, 1))
    private val b = AskScope.Today(LocalDate.of(2026, 10, 31))

    /** The collectors live as long as the test body; the ViewModel's own work is in its viewModelScope and needs no cleanup. */
    private fun TestScope.stop() = coroutineContext.job.children.forEach { it.cancel() }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun streamsTheServicesStatesWhileCollected() = runTest(dispatcher) {
        val vm = InsightViewModel(::insight)
        val seen = mutableListOf<InsightState>()
        launch(dispatcher) { vm.state(a).collect { seen += it } }
        advanceUntilIdle()
        assertEquals(InsightState.Ready("Insight for $a", label), seen.last())
        stop()
    }

    @Test
    fun doesNotGenerateUntilCollected() = runTest(dispatcher) {
        val vm = InsightViewModel(::insight)
        vm.state(a)
        advanceUntilIdle()
        assertTrue(started.isEmpty())
        stop()
    }

    @Test
    fun scopeChangeSwitchesAndNeverShowsTheOldDaysText() = runTest(dispatcher) {
        val vm = InsightViewModel(::insight)
        val seen = mutableListOf<InsightState>()
        launch(dispatcher) { vm.state(a).collect { seen += it } }
        advanceUntilIdle()
        gate = CompletableDeferred()
        seen.clear()
        vm.state(b)
        advanceUntilIdle()
        assertEquals(listOf(a, b), started)
        assertEquals("the old run was cancelled", 1, cancelled)
        assertTrue("no stale text while the new day loads: $seen", seen.none { it is InsightState.Ready })
        gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(InsightState.Ready("Insight for $b", label), seen.last())
        stop()
    }

    @Test
    fun sameScopeAgainDoesNotRestart() = runTest(dispatcher) {
        val vm = InsightViewModel(::insight)
        launch(dispatcher) { vm.state(a).collect { } }
        advanceUntilIdle()
        vm.state(a)
        advanceUntilIdle()
        assertEquals(listOf(a), started)
        stop()
    }

    @Test
    fun retryCollectsAgain() = runTest(dispatcher) {
        val vm = InsightViewModel(::insight)
        launch(dispatcher) { vm.state(a).collect { } }
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf(a, a), started)
        assertEquals(1, cancelled)
        stop()
    }

    @Test
    fun leavingThePageCancelsGeneration() = runTest(dispatcher) {
        val vm = InsightViewModel(::insight)
        gate = CompletableDeferred()
        val job = launch(dispatcher) { vm.state(a).collect { } }
        advanceUntilIdle()
        job.cancel()
        advanceTimeBy(6_000)
        advanceUntilIdle()
        assertEquals(1, cancelled)
        stop()
    }
}
