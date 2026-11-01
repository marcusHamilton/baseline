package dev.baseline.ai.insight

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiMode
import dev.baseline.ai.context.AskScope
import dev.baseline.ai.context.Pack
import dev.baseline.ai.context.PackFacts
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
import dev.baseline.data.db.AiInsightEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private class ScriptedNano : NanoBackend {
    var tokens = 100
    val scripts = ArrayDeque<suspend kotlinx.coroutines.flow.FlowCollector<String>.() -> Unit>()
    val systems = mutableListOf<String>()
    var calls = 0

    fun answer(vararg chunks: String) = scripts.addLast { chunks.forEach { emit(it) } }

    fun fail(error: NanoError) = scripts.addLast { throw NanoBackendException(error) }

    override suspend fun availability() = NanoAvailability.AVAILABLE

    override fun download(): Flow<Float?> = flow { }

    override suspend fun baseModelName() = "nano-v4"

    override suspend fun tokenLimit() = 4096

    override suspend fun systemPromptSupported() = true

    override suspend fun countTokens(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean) = tokens

    override fun stream(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int, temperature: Float): Flow<String> = flow {
        calls++
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

private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

@RunWith(AndroidJUnit4::class)
class InsightServiceTest {
    private lateinit var db: AppDatabase
    private val backend = ScriptedNano()
    private val nano = NanoEngine(backend, Clock.systemUTC(), MutableStateFlow(ZoneOffset.UTC))
    private val cloud = ScriptedCloud()
    private val mode = MutableStateFlow<AiMode?>(AiMode.HYBRID)
    private val keyStatus = MutableStateFlow<AiKeyStatus>(AiKeyStatus.Set("openrouter.ai", "deepseek/deepseek-v4.1-flash", true, false))
    private val online = MutableStateFlow(true)
    private val clock = Clock.fixed(Instant.parse("2026-11-01T08:00:00Z"), ZoneOffset.UTC)
    private val delays = mutableListOf<Long>()
    private val packCalls = mutableListOf<PackSize>()
    private var hasData = true
    private var data = "v1"
    private val scope = AskScope.Today(LocalDate.of(2026, 11, 1))

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun compactText() = "COMPACT-$data"

    private fun fullText() = "FULL-$data"

    private suspend fun service(): InsightService {
        nano.refresh()
        return InsightService(
            pack = { _, size ->
                packCalls += size
                Pack(if (size == PackSize.COMPACT) compactText() else fullText(), PackFacts(null, null, null, null), hasData)
            },
            nano = nano,
            cloud = cloud,
            dao = db.aiInsightDao(),
            mode = mode,
            keyStatus = keyStatus,
            online = online,
            clock = clock,
            delay = { delays += it },
        )
    }

    private fun row(fingerprint: String, text: String = "Cached.", engine: String = "nano", model: String? = "nano-v4") =
        AiInsightEntity("today", "2026-11-01", fingerprint, text, engine, model, Instant.parse("2026-10-31T08:00:00Z"))

    @Test
    fun cacheHitSkipsEngine() = runTest {
        db.aiInsightDao().put(row(fingerprint(compactText())))
        val states = service().insight(scope).toList()
        assertEquals(listOf<InsightState>(InsightState.Ready("Cached.", EngineLabel(EngineKind.NANO, "nano-v4"))), states)
        assertEquals(0, backend.calls)
        assertEquals(0, cloud.requests.size)
    }

    @Test
    fun aRowFromTheOtherEnginesPackStillHits() = runTest {
        // Written by the cloud from the full pack; this run would use Nano, but the text is shown anyway.
        db.aiInsightDao().put(row(fingerprint(fullText()), engine = "cloud", model = "deepseek/deepseek-v4.1-flash"))
        val states = service().insight(scope).toList()
        assertEquals(
            listOf<InsightState>(InsightState.Ready("Cached.", EngineLabel(EngineKind.CLOUD, "deepseek/deepseek-v4.1-flash"))),
            states,
        )
        assertEquals(0, backend.calls)
    }

    @Test
    fun dataChangeRegenerates() = runTest {
        db.aiInsightDao().put(row(fingerprint("COMPACT-old")))
        backend.answer("Fresh ", "take. ")
        val states = service().insight(scope).toList()
        assertEquals(
            listOf(
                InsightState.Generating(""),
                InsightState.Generating("Fresh "),
                InsightState.Generating("Fresh take. "),
                InsightState.Ready("Fresh take.", EngineLabel(EngineKind.NANO, "nano-v4")),
            ),
            states,
        )
        val stored = db.aiInsightDao().get("today", "2026-11-01")!!
        assertEquals("Fresh take.", stored.text)
        assertEquals(fingerprint(compactText()), stored.fingerprint)
        assertEquals("nano", stored.engine)
        assertEquals("nano-v4", stored.model)
        assertEquals(Instant.parse("2026-11-01T08:00:00Z"), stored.createdAt)
    }

    @Test
    fun promptVersionIsPartOfFingerprint() {
        assertNotEquals(sha256("x"), fingerprint("x"))
        assertEquals(sha256("${Prompts.PROMPT_VERSION}\nx"), fingerprint("x"))
        assertEquals(sha256("1\nx"), fingerprint("x"))
    }

    @Test
    fun nanoGetsTheCompactPackAndTheCloudTheFullOne() = runTest {
        backend.answer("a")
        service().insight(scope).toList()
        assertTrue(backend.systems.single().contains(compactText()))

        mode.value = AiMode.CLOUD
        data = "v2"
        cloud.answer("b")
        service().insight(scope).toList()
        val req = cloud.requests.single()
        assertEquals(RequestKind.INSIGHT, req.kind)
        assertEquals(Prompts.insight(scope), req.system)
        assertEquals(fullText(), req.pack)
        assertEquals(listOf(ChatTurn(Role.USER, "Write the insight.")), req.turns)
    }

    @Test
    fun failureWritesNothing() = runTest {
        mode.value = AiMode.CLOUD
        cloud.fail(AiFailure.Unreachable("openrouter.ai"))
        val states = service().insight(scope).toList()
        assertEquals(listOf(InsightState.Generating(""), InsightState.Failed("Couldn't reach openrouter.ai")), states)
        assertNull(db.aiInsightDao().get("today", "2026-11-01"))
    }

    @Test
    fun cancelWritesNothing() = runBlocking {
        mode.value = AiMode.CLOUD
        val midStream = CompletableDeferred<Unit>()
        cloud.scripts.addLast {
            emit(AiEvent.Text("Half "))
            midStream.complete(Unit)
            awaitCancellation()
        }
        val seen = mutableListOf<InsightState>()
        val job = launch { service().insight(scope).collect { seen += it } }
        midStream.await()
        assertEquals(listOf(InsightState.Generating(""), InsightState.Generating("Half ")), seen)
        job.cancelAndJoin()
        assertNull(db.aiInsightDao().get("today", "2026-11-01"))
    }

    @Test
    fun hybridFallsBackToCloud() = runTest {
        backend.fail(NanoError.QUOTA)
        cloud.answer("Cloud text.")
        val states = service().insight(scope).toList()
        val ready = states.last() as InsightState.Ready
        assertEquals("Cloud text.", ready.text)
        assertEquals(EngineKind.CLOUD, ready.label.kind)
        val stored = db.aiInsightDao().get("today", "2026-11-01")!!
        assertEquals("cloud", stored.engine)
        assertEquals(fingerprint(fullText()), stored.fingerprint)
    }

    @Test
    fun nanoBusyRetriesOnceAfterASecondThenFallsBack() = runTest {
        backend.fail(NanoError.BUSY)
        backend.answer("Second try.")
        val states = service().insight(scope).toList()
        assertEquals("Second try.", (states.last() as InsightState.Ready).text)
        assertEquals(listOf(1_000L), delays)
        assertEquals(0, cloud.requests.size)

        backend.fail(NanoError.BUSY)
        backend.fail(NanoError.BUSY)
        cloud.answer("Cloud.")
        data = "v2"
        val again = service().insight(scope).toList()
        assertEquals(EngineKind.CLOUD, (again.last() as InsightState.Ready).label.kind)
    }

    @Test
    fun nanoFailureOnDeviceOnlyIsAMessage() = runTest {
        mode.value = AiMode.ON_DEVICE
        backend.fail(NanoError.QUOTA)
        val states = service().insight(scope).toList()
        assertEquals(listOf(InsightState.Generating(""), InsightState.Failed("On-device AI has reached today's limit.")), states)
        assertEquals(0, cloud.requests.size)
    }

    @Test
    fun nanoBackgroundStopsSilently() = runTest {
        backend.fail(NanoError.BACKGROUND)
        val states = service().insight(scope).toList()
        assertEquals(listOf<InsightState>(InsightState.Generating("")), states)
        assertEquals(0, cloud.requests.size)
        assertNull(db.aiInsightDao().get("today", "2026-11-01"))
    }

    @Test
    fun bothEnginesFailingShowsTheLastFailureAndWritesNothing() = runTest {
        backend.fail(NanoError.OTHER)
        cloud.fail(AiFailure.KeyRejected)
        val states = service().insight(scope).toList()
        assertEquals(InsightState.Failed("API key rejected"), states.last())
        assertNull(db.aiInsightDao().get("today", "2026-11-01"))
    }

    @Test
    fun hybridGoesStraightToTheCloudWhenTheCompactPackDoesNotFit() = runTest {
        backend.tokens = 5000
        cloud.answer("Cloud.")
        val states = service().insight(scope).toList()
        assertEquals(EngineKind.CLOUD, (states.last() as InsightState.Ready).label.kind)
        assertEquals(0, backend.calls)
    }

    @Test
    fun anEmptyAnswerIsNotCached() = runTest {
        mode.value = AiMode.CLOUD
        cloud.answer("  ", "\n")
        val states = service().insight(scope).toList()
        assertEquals(InsightState.Failed("Couldn't generate"), states.last())
        assertNull(db.aiInsightDao().get("today", "2026-11-01"))
    }

    @Test
    fun clearAllDeletesEveryRow() = runTest {
        db.aiInsightDao().put(row("a"))
        service().clearAll()
        assertNull(db.aiInsightDao().get("today", "2026-11-01"))
    }

    @Test
    fun hiddenCases() = runTest {
        // Off, and undecided.
        mode.value = AiMode.OFF
        assertEquals(listOf<InsightState>(InsightState.Hidden), service().insight(scope).toList())
        mode.value = null
        assertEquals(listOf<InsightState>(InsightState.Hidden), service().insight(scope).toList())

        // A scope with no insight card.
        mode.value = AiMode.HYBRID
        assertEquals(listOf<InsightState>(InsightState.Hidden), service().insight(AskScope.Trends(30)).toList())
        assertEquals(listOf<InsightState>(InsightState.Hidden), service().insight(AskScope.General).toList())

        // No data for the page.
        hasData = false
        assertEquals(listOf<InsightState>(InsightState.Hidden), service().insight(scope).toList())
        hasData = true

        // Cloud only, offline (a cached row doesn't change that).
        mode.value = AiMode.CLOUD
        online.value = false
        db.aiInsightDao().put(row(fingerprint(fullText())))
        assertEquals(listOf<InsightState>(InsightState.Hidden), service().insight(scope).toList())
        assertEquals(0, cloud.requests.size)
    }

    @Test
    fun hiddenWhenNothingCanRun() = runTest {
        // Cloud only with no key; Hybrid offline with Nano not fitting.
        mode.value = AiMode.CLOUD
        keyStatus.value = AiKeyStatus.Missing
        assertEquals(listOf<InsightState>(InsightState.Hidden), service().insight(scope).toList())

        mode.value = AiMode.HYBRID
        online.value = false
        backend.tokens = 5000
        assertEquals(listOf<InsightState>(InsightState.Hidden), service().insight(scope).toList())
    }
}
