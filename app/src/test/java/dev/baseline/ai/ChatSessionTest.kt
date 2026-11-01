package dev.baseline.ai

import dev.baseline.ai.context.AskScope
import dev.baseline.ai.context.PackSize
import dev.baseline.ai.context.Prompts
import dev.baseline.ai.engine.AiEngine
import dev.baseline.ai.engine.AiEvent
import dev.baseline.ai.engine.AiFailure
import dev.baseline.ai.engine.AiRequest
import dev.baseline.ai.engine.ChatTurn
import dev.baseline.ai.engine.EngineKind
import dev.baseline.ai.engine.EngineLabel
import dev.baseline.ai.engine.NanoAvailability
import dev.baseline.ai.engine.NanoBackend
import dev.baseline.ai.engine.NanoBackendException
import dev.baseline.ai.engine.NanoEngine
import dev.baseline.ai.engine.NanoError
import dev.baseline.ai.engine.RequestKind
import dev.baseline.ai.engine.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset

private class ScriptedNano : NanoBackend {
    var limit = 4096
    var tokens = 100
    val scripts = ArrayDeque<suspend kotlinx.coroutines.flow.FlowCollector<String>.() -> Unit>()
    val requests = mutableListOf<List<ChatTurn>>()
    val systems = mutableListOf<String>()

    fun answer(vararg chunks: String) = scripts.addLast { chunks.forEach { emit(it) } }

    fun fail(error: NanoError) = scripts.addLast { throw NanoBackendException(error) }

    override suspend fun availability() = NanoAvailability.AVAILABLE

    override fun download(): Flow<Float?> = flow { }

    override suspend fun baseModelName() = "nano-v4"

    override suspend fun tokenLimit() = limit

    override suspend fun systemPromptSupported() = true

    override suspend fun countTokens(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean) = tokens

    override fun stream(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int, temperature: Float): Flow<String> = flow {
        requests += turns
        systems += system
        scripts.removeFirst().invoke(this)
    }

    override suspend fun warmup() = Unit
}

private class ScriptedCloud : AiEngine {
    val scripts = ArrayDeque<suspend kotlinx.coroutines.flow.FlowCollector<AiEvent>.() -> Unit>()
    val requests = mutableListOf<AiRequest>()
    val label = EngineLabel(EngineKind.CLOUD, "deepseek/deepseek-v4.1-flash")

    fun answer(vararg chunks: String) = scripts.addLast {
        chunks.forEach { emit(AiEvent.Text(it)) }
        emit(AiEvent.Done(label))
    }

    fun fail(failure: AiFailure) = scripts.addLast { throw failure }

    override fun run(req: AiRequest): Flow<AiEvent> = flow {
        requests += req
        scripts.removeFirst().invoke(this)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTest {
    private val backend = ScriptedNano()
    private val nano = NanoEngine(backend, Clock.systemUTC(), MutableStateFlow(ZoneOffset.UTC))
    private val cloud = ScriptedCloud()
    private val delays = mutableListOf<Long>()
    private var mode = AiMode.HYBRID
    private var keySet = true
    private var online = true
    private val packCalls = mutableListOf<PackSize>()

    private suspend fun session(): ChatSession {
        nano.refresh()
        return ChatSession(
            scope = AskScope.General,
            pack = { _, size ->
                packCalls += size
                "PACK-$size"
            },
            nano = nano,
            cloud = cloud,
            mode = { mode },
            keySet = { keySet },
            online = { online },
            delay = { delays += it },
        )
    }

    private val ChatSession.assistant get() = messages.value.filter { it.role == Role.ASSISTANT }

    @Test
    fun nanoAnswerIsLabelledWithMoreDetail() = runTest {
        backend.answer("Hel", "lo")
        val s = session()
        s.send("How did I sleep?", QuestionTag.PAGE)
        val m = s.assistant.single()
        assertEquals("Hello", m.text)
        assertEquals(EngineKind.NANO, m.label?.kind)
        assertTrue(m.canMoreDetail)
        assertFalse(m.streaming)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), s.messages.value.map { it.role })
        assertEquals("How did I sleep?", s.messages.value.first().text)
    }

    @Test
    fun moreDetailIsOffWithoutACloudOrOutsideHybrid() = runTest {
        online = false
        backend.answer("a")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertFalse(s.assistant.single().canMoreDetail)

        mode = AiMode.ON_DEVICE
        backend.answer("b")
        s.send("q2", QuestionTag.PAGE)
        assertFalse(s.assistant.last().canMoreDetail)
    }

    @Test
    fun followUpGoesToCloudAndStays() = runTest {
        backend.answer("first")
        cloud.answer("second")
        cloud.answer("third")
        val s = session()
        s.send("q1", QuestionTag.PAGE)
        s.send("q2", QuestionTag.PAGE)
        s.send("q3", QuestionTag.PAGE)
        assertEquals(1, backend.requests.size)
        assertEquals(2, cloud.requests.size)
        assertEquals(listOf("first", "second", "third"), s.assistant.map { it.text })
        assertEquals(listOf(EngineKind.NANO, EngineKind.CLOUD, EngineKind.CLOUD), s.assistant.map { it.label?.kind })
        assertEquals(
            listOf(ChatTurn(Role.USER, "q1"), ChatTurn(Role.ASSISTANT, "first"), ChatTurn(Role.USER, "q2")),
            cloud.requests[0].turns,
        )
        assertFalse(s.assistant.last().canMoreDetail)
    }

    @Test
    fun trainingQuestionGoesStraightToTheCloud() = runTest {
        cloud.answer("ok")
        val s = session()
        s.send("Can I train hard today?", QuestionTag.TRAINING)
        assertEquals(0, backend.requests.size)
        assertEquals("Cloud · deepseek-v4.1-flash", s.assistant.single().label?.text)
    }

    @Test
    fun needsHistoryIsHiddenAndEscalates() = runTest {
        backend.answer("NEEDS_", "HISTORY")
        cloud.answer("From your history")
        val s = session()
        val seen = mutableListOf<List<ChatMessage>>()
        val watcher = backgroundScope.launch { s.messages.collect { seen += it } }
        s.send("What changed this month?", QuestionTag.PAGE)
        advanceUntilIdle()
        watcher.cancel()
        assertTrue(seen.flatten().none { it.text.contains("NEEDS") })
        assertEquals(listOf(ChatTurn(Role.USER, "What changed this month?")), cloud.requests.single().turns)
        assertEquals("From your history", s.assistant.single().text)
        assertEquals(EngineKind.CLOUD, s.assistant.single().label?.kind)

        cloud.answer("again")
        s.send("and now?", QuestionTag.PAGE)
        assertEquals(2, cloud.requests.size)
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun needsHistoryPrefixReleasedWhenNotMatching() = runTest {
        backend.answer("NEE", "d more sleep")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertEquals("NEEd more sleep", s.assistant.single().text)
        assertEquals(0, cloud.requests.size)
    }

    @Test
    fun needsHistoryWithSurroundingWhitespaceStillEscalates() = runTest {
        backend.answer(" NEEDS_HISTORY\n")
        cloud.answer("ok")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertEquals("ok", s.assistant.single().text)
    }

    @Test
    fun needsHistoryOfflineShowsTheNote() = runTest {
        online = false
        backend.answer("NEEDS_HISTORY")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        val m = s.assistant.single()
        assertTrue(m.note)
        assertEquals("You're offline; this needs your history", m.text)
        assertNull(m.label)
    }

    @Test
    fun needsHistoryOnDeviceShowsTheNote() = runTest {
        mode = AiMode.ON_DEVICE
        keySet = false
        backend.answer("NEEDS_HISTORY")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        val m = s.assistant.single()
        assertTrue(m.note)
        assertEquals("On-device AI can only see this page. Add an API key in Settings for questions about your history", m.text)
    }

    @Test
    fun moreDetailReasksOnCloud() = runTest {
        backend.answer("short")
        cloud.answer("long")
        val s = session()
        s.send("How did I sleep?", QuestionTag.PAGE)
        s.moreDetail(1)
        assertEquals(
            listOf(ChatTurn(Role.USER, "How did I sleep?"), ChatTurn(Role.ASSISTANT, "short"), ChatTurn(Role.USER, "How did I sleep?")),
            cloud.requests.single().turns,
        )
        val last = s.assistant.last()
        assertEquals("long", last.text)
        assertFalse(last.canMoreDetail)
        assertFalse(s.assistant.first().canMoreDetail)

        cloud.answer("next")
        s.send("and?", QuestionTag.PAGE)
        assertEquals(0, backend.requests.size - 1)
        assertEquals(
            listOf(
                ChatTurn(Role.USER, "How did I sleep?"),
                ChatTurn(Role.ASSISTANT, "long"),
                ChatTurn(Role.USER, "and?"),
            ),
            cloud.requests[1].turns,
        )
    }

    @Test
    fun nanoBusyRetriesOnceThenCloud() = runTest {
        backend.fail(NanoError.BUSY)
        backend.fail(NanoError.BUSY)
        cloud.answer("cloud")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertEquals(listOf(1000L), delays)
        assertEquals(2, backend.requests.size)
        assertEquals("cloud", s.assistant.single().text)
    }

    @Test
    fun nanoBusyRetrySucceeds() = runTest {
        backend.fail(NanoError.BUSY)
        backend.answer("fine")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertEquals(listOf(1000L), delays)
        assertEquals("fine", s.assistant.single().text)
        assertEquals(0, cloud.requests.size)
    }

    @Test
    fun nanoQuotaMovesToTheCloud() = runTest {
        backend.fail(NanoError.QUOTA)
        cloud.answer("cloud")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertEquals("cloud", s.assistant.single().text)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun otherNanoFailureMovesToTheCloud() = runTest {
        backend.fail(NanoError.OTHER)
        cloud.answer("cloud")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertEquals("cloud", s.assistant.single().text)
    }

    @Test
    fun nanoBackgroundEndsSilently() = runTest {
        backend.fail(NanoError.BACKGROUND)
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertTrue(s.assistant.isEmpty())
        assertEquals(0, cloud.requests.size)
        assertFalse(s.busy.value)
    }

    @Test
    fun onDeviceOnlyShowsNanoFailures() = runTest {
        mode = AiMode.ON_DEVICE
        keySet = false
        backend.fail(NanoError.QUOTA)
        val s = session()
        s.send("q", QuestionTag.PAGE)
        val m = s.assistant.single()
        assertEquals("On-device AI has reached today's limit.", m.error)
        assertEquals("", m.text)
    }

    @Test
    fun onDeviceOnlyRetriesBusyOnceThenShowsTheError() = runTest {
        mode = AiMode.ON_DEVICE
        keySet = false
        backend.fail(NanoError.BUSY)
        backend.fail(NanoError.BUSY)
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertEquals(listOf(1000L), delays)
        assertEquals("On-device AI is busy. Try again in a moment.", s.assistant.single().error)
    }

    @Test
    fun onDeviceOverflowShowsTheNote() = runTest {
        mode = AiMode.ON_DEVICE
        keySet = false
        backend.tokens = 4000
        val s = session()
        s.send("q", QuestionTag.PAGE)
        val m = s.assistant.single()
        assertTrue(m.note)
        assertEquals("This needs more room than on-device AI allows. Try a shorter question, or add an API key in Settings.", m.text)
        assertEquals(0, backend.requests.size)
    }

    @Test
    fun cloudUnreachableFallsBackToNanoWithNote() = runTest {
        backend.answer("one")
        cloud.fail(AiFailure.Unreachable("openrouter.ai"))
        backend.answer("two")
        val s = session()
        s.send("q1", QuestionTag.PAGE)
        s.send("q2", QuestionTag.PAGE)
        val m = s.assistant.last()
        assertTrue(m.text, m.text.endsWith("(answered on-device; couldn't reach openrouter.ai)"))
        assertTrue(m.text.startsWith("two"))
        assertEquals(EngineKind.NANO, m.label?.kind)
        assertNull(m.error)
    }

    @Test
    fun cloudNoCreditFallsBackToNanoWithoutNote() = runTest {
        backend.answer("one")
        cloud.fail(AiFailure.NoCredit("openrouter.ai"))
        backend.answer("on-device answer")
        val s = session()
        s.send("q1", QuestionTag.PAGE)
        s.send("q2", QuestionTag.PAGE)
        assertEquals("on-device answer", s.assistant.last().text)
        assertEquals(EngineKind.NANO, s.assistant.last().label?.kind)
    }

    @Test
    fun cloudFailureForTrainingQuestionShowsTheError() = runTest {
        cloud.fail(AiFailure.NoCredit("openrouter.ai"))
        val s = session()
        s.send("Can I train?", QuestionTag.TRAINING)
        assertEquals("No credit left at openrouter.ai.", s.assistant.single().error)
        assertEquals(0, backend.requests.size)
    }

    @Test
    fun cloudUnreachableWhenNanoCannotHoldItShowsTheError() = runTest {
        backend.tokens = 4000
        cloud.fail(AiFailure.Unreachable("openrouter.ai"))
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertEquals("Couldn't reach openrouter.ai.", s.assistant.single().error)
    }

    @Test
    fun needsHistoryEscalationDoesNotFallBackToNano() = runTest {
        backend.answer("NEEDS_HISTORY")
        cloud.fail(AiFailure.Unreachable("openrouter.ai"))
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertEquals("Couldn't reach openrouter.ai.", s.assistant.single().error)
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun toolStatusBecomesLookedUp() = runTest {
        val label = "Looking up HRV, 1 Sep – 31 Oct"
        val s = session()
        var during: ChatMessage? = null
        cloud.scripts.addLast {
            emit(AiEvent.ToolStatus(label))
            during = s.messages.value.last()
            emit(AiEvent.Text("HRV rose."))
            emit(AiEvent.Done(cloud.label))
        }
        s.send("What helps my HRV?", QuestionTag.PATTERN)
        assertEquals(label, during?.status)
        assertTrue(during?.streaming == true)
        val m = s.assistant.single()
        assertNull(m.status)
        assertEquals(listOf(label), m.lookedUp)
        assertEquals("HRV rose.", m.text)
    }

    @Test
    fun textAfterAToolIsSeparatedByABlankLine() = runTest {
        val s = session()
        cloud.scripts.addLast {
            emit(AiEvent.Text("Let me check…"))
            emit(AiEvent.ToolStatus("Looking up sleep"))
            emit(AiEvent.Text("Sleep was short."))
            emit(AiEvent.ToolStatus("Looking up HRV"))
            emit(AiEvent.ToolStatus("Looking up RHR"))
            emit(AiEvent.Text(" Fine."))
            emit(AiEvent.Done(cloud.label))
        }
        s.send("q", QuestionTag.PATTERN)
        val m = s.assistant.single()
        assertEquals("Let me check…\n\nSleep was short.\n\n Fine.", m.text)
        assertEquals(listOf("Looking up sleep", "Looking up HRV", "Looking up RHR"), m.lookedUp)
    }

    @Test
    fun noBlankLineWhenNothingCameBeforeTheTool() = runTest {
        val s = session()
        cloud.scripts.addLast {
            emit(AiEvent.ToolStatus("Looking up sleep"))
            emit(AiEvent.Text("Short."))
            emit(AiEvent.Done(cloud.label))
        }
        s.send("q", QuestionTag.PATTERN)
        assertEquals("Short.", s.assistant.single().text)
    }

    @Test
    fun errorThenRetry() = runTest {
        cloud.fail(AiFailure.KeyRejected)
        cloud.answer("worked")
        val s = session()
        s.send("Can I train?", QuestionTag.TRAINING)
        val failed = s.assistant.single()
        assertEquals("Your API key was rejected. Check it in Settings.", failed.error)
        assertEquals("", failed.text)
        assertFalse(failed.streaming)

        s.retry()
        assertEquals(2, cloud.requests.size)
        assertEquals(cloud.requests[0].turns, cloud.requests[1].turns)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), s.messages.value.map { it.role })
        assertEquals("worked", s.assistant.single().text)
        assertNull(s.assistant.single().error)
    }

    @Test
    fun retryAfterNanoFailedToTheCloudStaysOnTheCloud() = runTest {
        backend.fail(NanoError.OTHER)
        cloud.fail(AiFailure.Offline)
        cloud.answer("cloud")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertEquals("You're offline.", s.assistant.single().error)
        s.retry()
        assertEquals("cloud", s.assistant.single().text)
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun retryAfterMoreDetailFailureReasksOnCloud() = runTest {
        backend.answer("short")
        cloud.fail(AiFailure.Busy("openrouter.ai"))
        cloud.answer("long")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        s.moreDetail(1)
        assertEquals("openrouter.ai is busy. Try again shortly.", s.assistant.last().error)
        s.retry()
        assertEquals("long", s.assistant.last().text)
        assertEquals(cloud.requests[0].turns, cloud.requests[1].turns)
        assertEquals(listOf("short", "long"), s.assistant.map { it.text })
    }

    @Test
    fun moreDetailOfflineShowsOfflineError() = runTest {
        backend.answer("short")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        online = false
        s.moreDetail(1)
        assertEquals("You're offline.", s.assistant.last().error)
    }

    @Test
    fun cloudGetsFullPackNanoGetsCompact() = runTest {
        backend.answer("one")
        cloud.answer("two")
        cloud.answer("three")
        val s = session()
        s.send("q1", QuestionTag.PAGE)
        s.send("q2", QuestionTag.PAGE)
        s.send("q3", QuestionTag.PAGE)
        assertEquals(true, backend.systems.single().contains("PACK-COMPACT"))
        assertEquals(false, backend.systems.single().contains("PACK-FULL"))
        assertEquals("PACK-FULL", cloud.requests[0].pack)
        assertEquals(Prompts.chat(EngineKind.CLOUD), cloud.requests[0].system)
        assertEquals(RequestKind.CHAT, cloud.requests[0].kind)
        // each pack is built once
        assertEquals(1, packCalls.count { it == PackSize.COMPACT })
        assertEquals(1, packCalls.count { it == PackSize.FULL })
    }

    @Test
    fun nanoRequestUsesTheNanoPrompt() = runTest {
        backend.answer("ok")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertTrue(backend.systems.single().startsWith(Prompts.chat(EngineKind.NANO)))
    }

    @Test
    fun partialNanoTextIsDroppedWhenItFailsToTheCloud() = runTest {
        backend.scripts.addLast {
            emit("partial answer")
            throw NanoBackendException(NanoError.OTHER)
        }
        cloud.answer("clean")
        val s = session()
        s.send("q", QuestionTag.PAGE)
        assertEquals("clean", s.assistant.single().text)
    }

    @Test
    fun aSecondSendWhileBusyIsIgnored() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        backend.scripts.addLast {
            emit("a")
            gate.await()
        }
        val s = session()
        val first = launch { s.send("q1", QuestionTag.PAGE) }
        runCurrent()
        assertTrue(s.busy.value)
        s.send("q2", QuestionTag.PAGE)
        assertEquals(listOf("q1"), s.messages.value.filter { it.role == Role.USER }.map { it.text })
        gate.complete(Unit)
        first.join()
        assertFalse(s.busy.value)
        assertEquals("a", s.assistant.single().text)
    }

    @Test
    fun cancellationPropagatesAndLeavesNoInProgressMessage() = runTest {
        backend.scripts.addLast {
            emit("a")
            awaitCancellation()
        }
        val s = session()
        val job = launch { s.send("q", QuestionTag.PAGE) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(s.assistant.isEmpty())
        assertFalse(s.busy.value)
        assertTrue(s.messages.value.none { it.error != null })
    }

    @Test
    fun cancellationExceptionFromEngineIsNotAnError() = runTest {
        backend.scripts.addLast { throw CancellationException("gone") }
        val s = session()
        var cancelled = false
        try {
            s.send("q", QuestionTag.PAGE)
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(s.messages.value.none { it.error != null })
    }
}
