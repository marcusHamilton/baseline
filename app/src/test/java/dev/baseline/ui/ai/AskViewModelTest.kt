package dev.baseline.ui.ai

import dev.baseline.ai.AiMode
import dev.baseline.ai.ChatSession
import dev.baseline.ai.QuestionTag
import dev.baseline.ai.context.AskScope
import dev.baseline.ai.context.PackFacts
import dev.baseline.ai.context.Suggestion
import dev.baseline.ai.engine.AiEngine
import dev.baseline.ai.engine.AiEvent
import dev.baseline.ai.engine.AiRequest
import dev.baseline.ai.engine.ChatTurn
import dev.baseline.ai.engine.EngineKind
import dev.baseline.ai.engine.EngineLabel
import dev.baseline.ai.engine.NanoAvailability
import dev.baseline.ai.engine.NanoBackend
import dev.baseline.ai.engine.NanoEngine
import dev.baseline.ai.engine.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

private class UnusedNano : NanoBackend {
    override suspend fun availability() = NanoAvailability.UNAVAILABLE

    override fun download(): Flow<Float?> = flow { }

    override suspend fun baseModelName() = "nano"

    override suspend fun tokenLimit() = 4096

    override suspend fun systemPromptSupported() = true

    override suspend fun countTokens(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean) = 1

    override fun stream(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int, temperature: Float): Flow<String> = flow { }

    override suspend fun warmup() = Unit
}

private class FakeCloud : AiEngine {
    val questions = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    val label = EngineLabel(EngineKind.CLOUD, "deepseek/deepseek-v4.1-flash")

    override fun run(req: AiRequest): Flow<AiEvent> = flow {
        questions += req.turns.last().text
        gate?.await()
        emit(AiEvent.Text("Answer to ${req.turns.last().text}"))
        emit(AiEvent.Done(label))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val cloud = FakeCloud()
    private val mode = MutableStateFlow<AiMode?>(AiMode.CLOUD)
    private val online = MutableStateFlow(true)
    private val today = MutableStateFlow(LocalDate.of(2026, 11, 1))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(scope: AskScope = AskScope.Today(today.value)): AskViewModel {
        val nano = NanoEngine(UnusedNano(), Clock.systemUTC(), MutableStateFlow(ZoneOffset.UTC))
        val session = ChatSession(
            scope = scope,
            pack = { _, _ -> "PACK" },
            nano = nano,
            cloud = cloud,
            mode = { AiMode.CLOUD },
            keySet = { true },
            online = { online.value },
        )
        return AskViewModel(scope, session, { PackFacts(71, null, null, null) }, mode, online, today)
    }

    private fun TestScope.watch(vm: AskViewModel) {
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        advanceUntilIdle()
    }

    @Test
    fun stateCarriesTitleHintAndPlaceholder() = runTest(dispatcher) {
        val vm = vm(AskScope.Sleep(today.value))
        watch(vm)
        val s = vm.state.value
        assertEquals("Ask about last night", s.title)
        assertEquals("Ask anything about last night, or how it fits your training and longer trends.", s.hint)
        assertEquals("Ask about last night…", s.placeholder)
        assertTrue(s.messages.isEmpty())
        assertFalse(s.busy)
    }

    @Test
    fun suggestionsUseTheFactsAndCloudSlots() = runTest(dispatcher) {
        val vm = vm()
        watch(vm)
        val tags = vm.state.value.suggestions
        assertEquals("Why is my recovery 71%?", tags.first().text)
        assertEquals(listOf(QuestionTag.PAGE, QuestionTag.PAGE, QuestionTag.TRAINING, QuestionTag.PATTERN), tags.map { it.tag })
    }

    @Test
    fun suggestionsFollowModeAndConnectivity() = runTest(dispatcher) {
        val vm = vm()
        watch(vm)
        fun tags() = vm.state.value.suggestions.map { it.tag }
        val pageOnly = List(4) { QuestionTag.PAGE }

        mode.value = AiMode.ON_DEVICE
        advanceUntilIdle()
        assertEquals(pageOnly, tags())

        mode.value = AiMode.HYBRID
        online.value = false
        advanceUntilIdle()
        assertEquals(pageOnly, tags())

        online.value = true
        advanceUntilIdle()
        assertEquals(QuestionTag.PATTERN, tags().last())

        mode.value = null
        advanceUntilIdle()
        assertEquals(pageOnly, tags())
    }

    @Test
    fun sendTrimsAndTagsTheQuestionAsPage() = runTest(dispatcher) {
        val vm = vm()
        watch(vm)
        vm.send("  Why was it low?  ")
        advanceUntilIdle()
        assertEquals(listOf("Why was it low?"), cloud.questions)
        val s = vm.state.value
        assertEquals(listOf(Role.USER, Role.ASSISTANT), s.messages.map { it.role })
        assertEquals("Why was it low?", s.messages.first().text)
        assertEquals("Ask a follow-up…", s.placeholder)
    }

    @Test
    fun blankSendDoesNothing() = runTest(dispatcher) {
        val vm = vm()
        watch(vm)
        vm.send("   ")
        advanceUntilIdle()
        assertTrue(cloud.questions.isEmpty())
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun pickSendsTheSuggestionText() = runTest(dispatcher) {
        val vm = vm()
        watch(vm)
        vm.pick(Suggestion("Can I train hard today?", QuestionTag.TRAINING))
        advanceUntilIdle()
        assertEquals(listOf("Can I train hard today?"), cloud.questions)
    }

    @Test
    fun busyWhileAnAnswerIsPending() = runTest(dispatcher) {
        cloud.gate = CompletableDeferred()
        val vm = vm()
        watch(vm)
        vm.send("Q")
        advanceUntilIdle()
        assertTrue(vm.state.value.busy)
        cloud.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertEquals("Answer to Q", vm.state.value.messages.last().text)
    }
}
