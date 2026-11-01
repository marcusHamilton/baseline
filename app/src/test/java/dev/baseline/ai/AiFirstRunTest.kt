package dev.baseline.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.baseline.ai.engine.ChatTurn
import dev.baseline.ai.engine.NanoAvailability
import dev.baseline.ai.engine.NanoBackend
import dev.baseline.ai.engine.NanoBackendException
import dev.baseline.ai.engine.NanoEngine
import dev.baseline.ai.engine.NanoError
import dev.baseline.testutil.ScopeCleanup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.ZoneOffset

private class DownloadableNano : NanoBackend {
    var availability = NanoAvailability.DOWNLOADABLE
    var downloads = 0
    var failDownload = false
    val attempted = kotlinx.coroutines.CompletableDeferred<Unit>()

    override suspend fun availability() = availability

    override fun download(): Flow<Float?> = flow {
        downloads++
        if (failDownload) {
            attempted.complete(Unit)
            throw NanoBackendException(NanoError.OTHER)
        }
        availability = NanoAvailability.AVAILABLE
        emit(1f)
    }

    override suspend fun baseModelName() = "nano-v4"

    override suspend fun tokenLimit() = 4096

    override suspend fun systemPromptSupported() = true

    override suspend fun countTokens(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean) = 0

    override fun stream(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int, temperature: Float): Flow<String> = emptyFlow()

    override suspend fun warmup() = Unit
}

class AiFirstRunTest {
    private val scopes = ScopeCleanup()
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private val backend = DownloadableNano()

    @Before
    fun setUp() {
        file = File.createTempFile("aifirstrun", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        scopes.close()
        file.delete()
    }

    private fun firstRun(mode: AiMode?): Triple<AiFirstRun, AiPrefs, NanoEngine> = runBlocking {
        val prefs = AiPrefs(store).also { p -> mode?.let { p.setMode(it) } }
        val nano = NanoEngine(backend, Clock.systemUTC(), MutableStateFlow(ZoneOffset.UTC)).also { it.refresh() }
        Triple(AiFirstRun(nano, prefs, scopes.storeScope), prefs, nano)
    }

    @Test
    fun showsForOnDeviceWhenNanoNeedsTheDownload() = runBlocking {
        assertTrue(firstRun(AiMode.ON_DEVICE).first.show.first())
    }

    @Test
    fun doesNotShowBeforeTheModeIsDecided() = runBlocking {
        assertEquals(false, firstRun(null).first.show.first())
    }

    @Test
    fun doesNotShowForCloudOnly() = runBlocking {
        assertEquals(false, firstRun(AiMode.CLOUD).first.show.first())
    }

    @Test
    fun doesNotShowWhenNanoIsUnavailable() = runBlocking {
        backend.availability = NanoAvailability.UNAVAILABLE
        assertEquals(false, firstRun(AiMode.ON_DEVICE).first.show.first())
    }

    @Test
    fun downloadKeepsOnDeviceStartsTheDownloadAndRetiresThePrompt() = runBlocking {
        val (run, prefs) = firstRun(AiMode.ON_DEVICE)
        run.download()
        prefs.promptDone.first { it }
        assertEquals(AiMode.ON_DEVICE, prefs.mode.first())
        assertEquals(1, backend.downloads)
    }

    @Test
    fun aFailedDownloadLeavesThePromptPendingSoItAsksAgain() = runBlocking {
        backend.failDownload = true
        val (run, prefs) = firstRun(AiMode.ON_DEVICE)
        run.download()
        backend.attempted.await()
        assertTrue(run.show.first { it })
        assertEquals(false, prefs.promptDone.first())
        assertEquals(AiMode.ON_DEVICE, prefs.mode.first())
    }

    @Test
    fun turnOffSetsOffAndRetiresThePromptWithoutDownloading() = runBlocking {
        val (run, prefs) = firstRun(AiMode.ON_DEVICE)
        run.turnOff()
        run.show.first { !it }
        assertEquals(AiMode.OFF, prefs.mode.first())
        assertTrue(prefs.promptDone.first())
        assertEquals(0, backend.downloads)
    }
}
