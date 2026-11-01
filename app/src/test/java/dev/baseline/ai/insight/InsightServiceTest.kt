package dev.baseline.ai.insight

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiMode
import dev.baseline.ai.context.AskScope
import dev.baseline.ai.context.BASIS_SETTLE_MILLIS
import dev.baseline.ai.context.ContextPacks
import dev.baseline.ai.context.Pack
import dev.baseline.ai.context.PackFacts
import dev.baseline.ai.context.PackSize
import dev.baseline.ai.context.Prompts
import dev.baseline.ai.context.settledBasis
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
import dev.baseline.data.Repository
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.AiInsightDao
import dev.baseline.data.db.AiInsightEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.LoadSource
import dev.baseline.data.db.ScoreEntity
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.prefs.UserPrefs
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
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
    private var volatileTail = ""
    private val scope = AskScope.Today(LocalDate.of(2026, 11, 1))
    private val scopes = ScopeCleanup()
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        runBlocking { scopes.close() }
        db.close()
        files.forEach { it.delete() }
    }

    private fun compactText() = "COMPACT-$data"

    private fun fullText() = "FULL-$data"

    // What the fake packs fingerprint: the text without its volatile lines, which differs by size so a test can tell which one was used.
    private fun compactBasis() = "CBASIS-$data"

    private fun fullBasis() = "FBASIS-$data"

    private suspend fun service(): InsightService {
        nano.refresh()
        return InsightService(
            pack = { _, size ->
                packCalls += size
                Pack(
                    if (size == PackSize.COMPACT) compactText() + volatileTail else fullText() + volatileTail,
                    PackFacts(null, null, null, null),
                    hasData,
                    if (size == PackSize.COMPACT) compactBasis() else fullBasis(),
                )
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
        db.aiInsightDao().put(row(fingerprint(compactBasis())))
        val states = service().insight(scope).toList()
        assertEquals(listOf<InsightState>(InsightState.Ready("Cached.", EngineLabel(EngineKind.NANO, "nano-v4"))), states)
        assertEquals(0, backend.calls)
        assertEquals(0, cloud.requests.size)
    }

    @Test
    fun aChangeOnlyInTheTextKeepsTheCachedInsight() = runTest {
        db.aiInsightDao().put(row(fingerprint(compactBasis())))
        volatileTail = "\nSteps 9,999, so far today"
        val states = service().insight(scope).toList()
        assertEquals(listOf<InsightState>(InsightState.Ready("Cached.", EngineLabel(EngineKind.NANO, "nano-v4"))), states)
        assertEquals(0, backend.calls)
    }

    @Test
    fun aRowWrittenByTheOtherEngineStillHits() = runTest {
        // Written by the cloud; this run would use Nano, but the text is shown anyway (both fingerprint the compact basis).
        db.aiInsightDao().put(row(fingerprint(compactBasis()), engine = "cloud", model = "deepseek/deepseek-v4.1-flash"))
        val states = service().insight(scope).toList()
        assertEquals(
            listOf<InsightState>(InsightState.Ready("Cached.", EngineLabel(EngineKind.CLOUD, "deepseek/deepseek-v4.1-flash"))),
            states,
        )
        assertEquals(0, backend.calls)
    }

    @Test
    fun theFullPacksBasisIsNotAFingerprint() = runTest {
        db.aiInsightDao().put(row(fingerprint(fullBasis())))
        backend.answer("Fresh.")
        val states = service().insight(scope).toList()
        assertEquals("Fresh.", (states.last() as InsightState.Ready).text)
        assertEquals(1, backend.calls)
    }

    @Test
    fun dataChangeRegenerates() = runTest {
        db.aiInsightDao().put(row(fingerprint("CBASIS-old")))
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
        assertEquals(fingerprint(compactBasis()), stored.fingerprint)
        assertEquals("nano", stored.engine)
        assertEquals("nano-v4", stored.model)
        assertEquals(Instant.parse("2026-11-01T08:00:00Z"), stored.createdAt)
    }

    @Test
    fun promptVersionIsPartOfFingerprint() {
        assertNotEquals(sha256("x"), fingerprint("x"))
        assertEquals(sha256("${Prompts.PROMPT_VERSION}\nx"), fingerprint("x"))
        assertEquals(sha256("2\nx"), fingerprint("x"))
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
        assertEquals(fingerprint(compactBasis()), stored.fingerprint)
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
        db.aiInsightDao().put(row(fingerprint(compactBasis())))
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

    // ---- Real packs over the in-memory database: what counts as a change, and the live refresh ----

    private val today = LocalDate.of(2026, 11, 1)

    private fun realPacks(): ContextPacks {
        val file = File.createTempFile("insight", ".preferences_pb").also { it.delete() }.also { files += it }
        val store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        val prefs = UserPrefs(store)
        val repo = Repository(db, prefs, ApiKeyVault(store, { it }, { it }, Clock.systemUTC()))
        return ContextPacks(db, repo, prefs, MutableStateFlow(today), MutableStateFlow(ZoneId.of("Europe/London")), settleMillis = 0)
    }

    private suspend fun realService(): InsightService {
        nano.refresh()
        return InsightService(realPacks(), nano, cloud, db.aiInsightDao(), mode, keyStatus, online, clock)
    }

    private suspend fun seedToday(steps: Int = 4200) {
        db.dayDao().upsert(
            DayEntity(
                date = today,
                restingHr = 52,
                steps = steps,
                stressAvg = 30,
                bodyBatteryWake = 80,
                bodyBatteryCharged = 60,
                sleepSeconds = 25200,
                hrvLastNight = 58.0,
            ),
        )
        db.scoreDao().upsert(ScoreEntity(today, 71, false, 1.0, 80.0, 8.4, 14.0, 18.0, 1))
    }

    /** The run and its strain in one transaction, so a collector sees one change, not two. */
    private suspend fun addRun(id: Long = 900, strain: Double = 9.1) {
        db.useWriterConnection { tx ->
            tx.immediateTransaction {
                db.activityDao().upsert(
                    ActivityEntity(
                        id, null, "running", SportFamily.RUN, LocalDateTime.of(today, LocalTime.of(7, 5)), today, today.atTime(7, 5).toInstant(ZoneOffset.UTC),
                        3135.0, 10200.0, 151.0, 172.0, 120.0, 900.0, 1500.0, 500.0, 115.0, 120.0, 86.0, null, null, null, null, null, null,
                    ),
                )
                db.activityScoreDao().upsert(ActivityScoreEntity(id, 90.0, LoadSource.HR, strain))
            }
        }
    }

    /** Collects one run of the card: everything up to its Ready, Failed or Hidden. */
    private suspend fun Flow<InsightState>.untilSettled(): List<InsightState> = transformWhile {
        emit(it)
        it is InsightState.Generating
    }.toList()

    @Test
    fun aStepsOnlyChangeBetweenVisitsIsACacheHit() = runBlocking {
        seedToday(steps = 4200)
        backend.answer("First.")
        val service = realService()
        assertEquals("First.", (service.insight(scope).untilSettled().last() as InsightState.Ready).text)

        db.dayDao().upsert(db.dayDao().get(today)!!.copy(steps = 9100, stressAvg = 41))
        db.scoreDao().upsert(db.scoreDao().get(today)!!.copy(strain = 12.0))

        val second = service.insight(scope).untilSettled()
        assertEquals(listOf<InsightState>(InsightState.Ready("First.", EngineLabel(EngineKind.NANO, "nano-v4"))), second)
        assertEquals(1, backend.calls)
        assertEquals(0, cloud.requests.size)
    }

    @Test
    fun aNewActivityRegeneratesOnTheNextVisit() = runBlocking {
        seedToday()
        backend.answer("First.")
        backend.answer("After the run.")
        val service = realService()
        service.insight(scope).untilSettled()

        addRun()

        val second = service.insight(scope).untilSettled()
        assertEquals(InsightState.Generating(""), second.first())
        assertEquals("After the run.", (second.last() as InsightState.Ready).text)
        assertEquals(2, backend.calls)
        assertTrue(backend.systems.last().contains("Run, Sun 1 Nov 07:05"))
    }

    @Test
    fun anActivitysStrainArrivingLaterRegenerates() = runBlocking {
        seedToday()
        addRun()
        backend.answer("First.")
        backend.answer("Scored.")
        val service = realService()
        service.insight(scope).untilSettled()

        db.activityScoreDao().upsert(ActivityScoreEntity(900, 90.0, LoadSource.HR, 12.3))

        assertEquals("Scored.", (service.insight(scope).untilSettled().last() as InsightState.Ready).text)
        assertEquals(2, backend.calls)
    }

    @Test
    fun aMeaningfulChangeWhileOnScreenRegeneratesInPlace() = runBlocking {
        seedToday()
        backend.answer("First.")
        backend.answer("After the run.")
        val service = realService()
        val states = Channel<InsightState>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { service.insight(scope).collect { states.send(it) } }
        suspend fun next() = withTimeout(30_000) { states.receive() }
        try {
            assertEquals(InsightState.Generating(""), next())
            assertEquals(InsightState.Generating("First."), next())
            assertEquals(InsightState.Ready("First.", EngineLabel(EngineKind.NANO, "nano-v4")), next())

            addRun()

            assertEquals(InsightState.Generating(""), next())
            assertEquals(InsightState.Generating("After the run."), next())
            assertEquals(InsightState.Ready("After the run.", EngineLabel(EngineKind.NANO, "nano-v4")), next())
            assertEquals(2, backend.calls)
            assertEquals("After the run.", db.aiInsightDao().get("today", "2026-11-01")!!.text)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun aVolatileOnlyChangeWhileOnScreenDoesNothing() = runBlocking {
        seedToday()
        backend.answer("First.")
        backend.answer("After the run.")
        val service = realService()
        val states = Channel<InsightState>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { service.insight(scope).collect { states.send(it) } }
        suspend fun next() = withTimeout(30_000) { states.receive() }
        try {
            repeat(3) { next() }

            // Steps, stress and strain so far move on every sync. Had any of them regenerated, its states would arrive
            // before the run's, and the run would find no scripted answer left.
            db.dayDao().upsert(db.dayDao().get(today)!!.copy(steps = 9100, stressAvg = 41))
            db.scoreDao().upsert(db.scoreDao().get(today)!!.copy(strain = 12.0))
            addRun()

            assertEquals(InsightState.Generating(""), next())
            assertEquals(InsightState.Generating("After the run."), next())
            assertEquals(InsightState.Ready("After the run.", EngineLabel(EngineKind.NANO, "nano-v4")), next())
            assertEquals(2, backend.calls)
            assertEquals(0, cloud.requests.size)
            assertTrue(states.tryReceive().isFailure)
        } finally {
            job.cancelAndJoin()
        }
    }

    // ---- Settling: virtual time over a fake stream of Room reads ----

    /** In memory, so the virtual-time tests below never wait on Room's threads. */
    private class MapDao : AiInsightDao {
        val rows = mutableMapOf<Pair<String, String>, AiInsightEntity>()

        override suspend fun get(scope: String, key: String) = rows[scope to key]

        override suspend fun put(row: AiInsightEntity) {
            rows[row.scope to row.scopeKey] = row
        }

        override suspend fun clear() = rows.clear()
    }

    private val mapDao = MapDao()

    /** A service whose basis stream is [settledBasis] over [reads], with the basis (and so the pack) read from [data]. */
    private suspend fun settlingService(reads: Flow<Unit>): InsightService {
        nano.refresh()
        return InsightService(
            pack = { _, size -> Pack("T-$data", PackFacts(null, null, null, null), true, if (size == PackSize.COMPACT) compactBasis() else fullBasis()) },
            nano = nano,
            cloud = cloud,
            dao = mapDao,
            mode = mode,
            keyStatus = keyStatus,
            online = online,
            clock = clock,
            basisChanges = { settledBasis(reads, BASIS_SETTLE_MILLIS) { compactBasis() } },
        )
    }

    @Test
    fun theFirstBasisArrivesWithoutWaiting() = runTest {
        backend.answer("First.")
        val reads = MutableSharedFlow<Unit>(replay = 1)
        reads.emit(Unit)
        val seen = mutableListOf<InsightState>()
        backgroundScope.launch { settlingService(reads).insight(scope).collect { seen += it } }
        runCurrent() // no advanceTimeBy
        assertEquals(InsightState.Ready("First.", EngineLabel(EngineKind.NANO, "nano-v4")), seen.last())
        assertEquals(1, backend.calls)
    }

    @Test
    fun aBurstOfWritesRegeneratesOnce() = runTest {
        backend.answer("First.")
        backend.answer("Second.")
        val reads = MutableSharedFlow<Unit>(replay = 1)
        reads.emit(Unit)
        val seen = mutableListOf<InsightState>()
        backgroundScope.launch { settlingService(reads).insight(scope).collect { seen += it } }
        runCurrent()
        assertEquals(1, backend.calls)

        // An activity, then its score a second later: the basis only differs once both are in.
        data = "v2"
        reads.emit(Unit)
        advanceTimeBy(1_000)
        reads.emit(Unit)
        advanceTimeBy(BASIS_SETTLE_MILLIS - 1)
        runCurrent()
        assertEquals(1, backend.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, backend.calls)
        assertEquals(InsightState.Ready("Second.", EngineLabel(EngineKind.NANO, "nano-v4")), seen.last())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, backend.calls)
    }

    @Test
    fun aChangeFollowedByQuietRegenerates() = runTest {
        backend.answer("First.")
        backend.answer("Second.")
        val reads = MutableSharedFlow<Unit>(replay = 1)
        reads.emit(Unit)
        val seen = mutableListOf<InsightState>()
        backgroundScope.launch { settlingService(reads).insight(scope).collect { seen += it } }
        runCurrent()

        data = "v2"
        reads.emit(Unit)
        runCurrent()
        assertEquals(1, backend.calls)
        advanceTimeBy(BASIS_SETTLE_MILLIS)
        runCurrent()
        assertEquals(2, backend.calls)
        assertEquals("Second.", mapDao.rows.getValue("today" to "2026-11-01").text)
    }

    @Test
    fun aReadThatLeavesTheBasisAloneRegeneratesNothing() = runTest {
        backend.answer("First.")
        val reads = MutableSharedFlow<Unit>(replay = 1)
        reads.emit(Unit)
        backgroundScope.launch { settlingService(reads).insight(scope).collect { } }
        runCurrent()

        reads.emit(Unit) // steps moved; the basis is the same
        advanceTimeBy(BASIS_SETTLE_MILLIS * 2)
        runCurrent()
        assertEquals(1, backend.calls)
    }
}
