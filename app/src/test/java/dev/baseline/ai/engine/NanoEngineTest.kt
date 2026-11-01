package dev.baseline.ai.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private class FakeNanoBackend : NanoBackend {
    var availability = NanoAvailability.AVAILABLE
    var modelName = "nano-v4"
    var limit = 4096
    var systemSupported = true
    var tokens = 100
    var chunks = listOf("Hel", "lo")
    var streamError: NanoError? = null
    var hang = false
    var downloadSteps = listOf<Float?>(null, 0.5f, 1f)
    var warmups = 0

    var lastSystem: String? = null
    var lastTurns: List<ChatTurn> = emptyList()
    var lastAsInstruction: Boolean? = null
    var lastMaxOutput: Int? = null
    var lastTemperature: Float? = null

    override suspend fun availability() = availability

    override fun download(): Flow<Float?> = flow {
        downloadSteps.forEach { emit(it) }
        availability = NanoAvailability.AVAILABLE
    }

    override suspend fun baseModelName() = modelName

    override suspend fun tokenLimit() = limit

    override suspend fun systemPromptSupported() = systemSupported

    override suspend fun countTokens(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean) = tokens

    override fun stream(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int, temperature: Float): Flow<String> = flow {
        lastSystem = system
        lastTurns = turns
        lastAsInstruction = systemAsInstruction
        lastMaxOutput = maxOutput
        lastTemperature = temperature
        streamError?.let { throw NanoBackendException(it) }
        if (hang) awaitCancellation()
        chunks.forEach { emit(it) }
    }

    override suspend fun warmup() {
        warmups++
    }
}

/** A clock a test can move. */
private class MovableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

class NanoEngineTest {
    private val backend = FakeNanoBackend()
    private val clock = MovableClock(Instant.parse("2026-11-01T12:00:00Z"))
    private val zone = MutableStateFlow<ZoneId>(ZoneOffset.UTC)
    private val engine = NanoEngine(backend, clock, zone)

    private fun chat(vararg turns: ChatTurn) = AiRequest(RequestKind.CHAT, "SYS", "PACK", turns.toList().ifEmpty { listOf(ChatTurn(Role.USER, "Hi")) })

    private fun failureOf(req: AiRequest = chat()): AiFailure = runBlocking {
        try {
            engine.run(req).toList()
            fail("expected an AiFailure")
            error("unreachable")
        } catch (e: AiFailure) {
            e
        }
    }

    @Test
    fun mapsAvailabilityToStatus() = runBlocking {
        assertEquals(NanoStatus.Unavailable, engine.status.value)
        assertFalse(engine.known.value)

        engine.refresh()
        assertEquals(NanoStatus.Ready("nano-v4", 4096), engine.status.value)
        assertTrue(engine.known.value)

        backend.availability = NanoAvailability.DOWNLOADABLE
        engine.refresh()
        assertEquals(NanoStatus.Downloadable, engine.status.value)

        backend.availability = NanoAvailability.DOWNLOADING
        engine.refresh()
        assertEquals(NanoStatus.Downloading(null), engine.status.value)

        backend.availability = NanoAvailability.UNAVAILABLE
        engine.refresh()
        assertEquals(NanoStatus.Unavailable, engine.status.value)
    }

    @Test
    fun knownBecomesTrueEvenWhenUnavailable() = runBlocking {
        backend.availability = NanoAvailability.UNAVAILABLE
        engine.refresh()
        assertTrue(engine.known.value)
    }

    @Test
    fun fitsUsesTokenLimitAndOutputReserve() = runBlocking {
        engine.refresh()
        backend.tokens = 3840
        assertTrue(engine.fits(chat()))
        assertTrue(engine.fits(chat().copy(kind = RequestKind.INSIGHT)))
        backend.tokens = 3841
        assertFalse(engine.fits(chat()))
        // Insights reserve only 160 tokens for the answer.
        assertTrue(engine.fits(chat().copy(kind = RequestKind.INSIGHT)))
        backend.tokens = 3937
        assertFalse(engine.fits(chat().copy(kind = RequestKind.INSIGHT)))
    }

    @Test
    fun fitsIsFalseWhenNanoIsNotReady() = runBlocking {
        backend.availability = NanoAvailability.DOWNLOADABLE
        engine.refresh()
        backend.tokens = 1
        assertFalse(engine.fits(chat()))
    }

    @Test
    fun systemTextIsSystemThenPack() {
        runBlocking {
            engine.refresh()
            engine.run(chat()).toList()
        }
        assertEquals("SYS\n\nPACK", backend.lastSystem)
        assertEquals(true, backend.lastAsInstruction)
        assertEquals(listOf(ChatTurn(Role.USER, "Hi")), backend.lastTurns)
    }

    @Test
    fun systemPromptFallsBackToPrependedWhenUnsupported() {
        backend.systemSupported = false
        runBlocking {
            engine.refresh()
            engine.run(chat(ChatTurn(Role.USER, "Hi"), ChatTurn(Role.ASSISTANT, "Hello"), ChatTurn(Role.USER, "More"))).toList()
        }
        assertEquals(false, backend.lastAsInstruction)
        assertEquals(
            listOf(ChatTurn(Role.USER, "SYS\n\nPACK\n\nHi"), ChatTurn(Role.ASSISTANT, "Hello"), ChatTurn(Role.USER, "More")),
            backend.lastTurns,
        )
    }

    @Test
    fun usesTheLimitsFromTheSpec() {
        runBlocking {
            engine.refresh()
            engine.run(chat()).toList()
            assertEquals(256, backend.lastMaxOutput)
            assertEquals(0.2f, backend.lastTemperature)
            engine.run(chat().copy(kind = RequestKind.INSIGHT)).toList()
            assertEquals(160, backend.lastMaxOutput)
        }
    }

    @Test
    fun streamsAndLabels() = runBlocking {
        engine.refresh()
        val events = engine.run(chat()).toList()
        assertEquals(
            listOf(AiEvent.Text("Hel"), AiEvent.Text("lo"), AiEvent.Done(EngineLabel(EngineKind.NANO, "nano-v4"))),
            events,
        )
    }

    @Test
    fun runRefreshesFirstWhenStatusIsNotKnownYet() = runBlocking {
        val events = engine.run(chat()).toList()
        assertEquals(AiEvent.Done(EngineLabel(EngineKind.NANO, "nano-v4")), events.last())
    }

    @Test
    fun quotaRestsUntilLocalMidnight() = runBlocking {
        zone.value = ZoneId.of("Asia/Tokyo")
        clock.now = Instant.parse("2026-11-01T22:00:00Z")
        engine.refresh()
        backend.streamError = NanoError.QUOTA

        assertEquals(AiFailure.NanoQuota, failureOf())
        val until = Instant.parse("2026-11-02T15:00:00Z")
        assertEquals(NanoStatus.Resting(until), engine.status.value)

        clock.now = Instant.parse("2026-11-02T14:59:00Z")
        engine.refresh()
        assertEquals(NanoStatus.Resting(until), engine.status.value)
        // Resting blocks a new request without asking the backend.
        backend.lastSystem = null
        assertEquals(AiFailure.NanoQuota, failureOf())
        assertEquals(null, backend.lastSystem)

        clock.now = until
        engine.refresh()
        assertEquals(NanoStatus.Ready("nano-v4", 4096), engine.status.value)
    }

    @Test
    fun runAfterRestingEndsRefreshesAndGenerates() = runBlocking {
        engine.refresh()
        backend.streamError = NanoError.QUOTA
        failureOf()
        backend.streamError = null
        clock.now = Instant.parse("2026-11-02T00:00:00Z")
        val events = engine.run(chat()).toList()
        assertEquals(AiEvent.Done(EngineLabel(EngineKind.NANO, "nano-v4")), events.last())
        assertEquals(NanoStatus.Ready("nano-v4", 4096), engine.status.value)
    }

    @Test
    fun errorsMapToFailures() {
        runBlocking { engine.refresh() }
        backend.streamError = NanoError.BUSY
        assertEquals(AiFailure.NanoBusy, failureOf())
        backend.streamError = NanoError.BACKGROUND
        assertEquals(AiFailure.NanoBackground, failureOf())
        backend.streamError = NanoError.NOT_AVAILABLE
        assertEquals(AiFailure.NanoUnavailable, failureOf())
        backend.streamError = NanoError.OTHER
        assertEquals(AiFailure.Malformed, failureOf())
    }

    @Test
    fun runIsUnavailableWhenModelIsNotReady() {
        for (a in listOf(NanoAvailability.UNAVAILABLE, NanoAvailability.DOWNLOADABLE, NanoAvailability.DOWNLOADING)) {
            val e = NanoEngine(FakeNanoBackend().also { it.availability = a }, clock, zone)
            val failure =
                runBlocking {
                    e.refresh()
                    try {
                        e.run(chat()).toList()
                        null
                    } catch (f: AiFailure) {
                        f
                    }
                }
            assertEquals(a.name, AiFailure.NanoUnavailable, failure)
        }
    }

    @Test
    fun timeoutIsBusy() {
        backend.hang = true
        val quick = NanoEngine(backend, clock, zone, timeoutMillis = 50)
        val failure =
            runBlocking {
                quick.refresh()
                try {
                    quick.run(chat()).toList()
                    null
                } catch (f: AiFailure) {
                    f
                }
            }
        assertEquals(AiFailure.NanoBusy, failure)
    }

    @Test
    fun cancellationIsNotAFailure() {
        backend.hang = true
        val thrown =
            runBlocking {
                engine.refresh()
                var caught: Throwable? = null
                val job = launch { caught = runCatching { engine.run(chat()).toList() }.exceptionOrNull() }
                delay(20)
                job.cancelAndJoin()
                caught
            }
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun downloadDrivesStatusToReady() = runBlocking {
        backend.availability = NanoAvailability.DOWNLOADABLE
        engine.refresh()
        val seen = mutableListOf<NanoStatus>()
        val progress = mutableListOf<Float?>()
        engine.download().collect {
            progress += it
            seen += engine.status.value
        }
        assertEquals(listOf<Float?>(null, 0.5f, 1f), progress)
        assertEquals(listOf(NanoStatus.Downloading(null), NanoStatus.Downloading(0.5f), NanoStatus.Downloading(1f)), seen)
        assertEquals(NanoStatus.Ready("nano-v4", 4096), engine.status.value)
    }

    @Test
    fun warmupOnlyWhenReady() = runBlocking {
        engine.warmup()
        assertEquals(0, backend.warmups)
        engine.refresh()
        engine.warmup()
        assertEquals(1, backend.warmups)
    }
}
