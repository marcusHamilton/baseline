package dev.baseline.ui.settings

import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.baseline.ai.AiKeyEntry
import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiKeyVault
import dev.baseline.ai.AiMode
import dev.baseline.ai.AiPrefs
import dev.baseline.ai.context.Pack
import dev.baseline.ai.context.PackFacts
import dev.baseline.ai.engine.AiEngine
import dev.baseline.ai.engine.AiEvent
import dev.baseline.ai.engine.AiRequest
import dev.baseline.ai.engine.ChatTurn
import dev.baseline.ai.engine.NanoAvailability
import dev.baseline.ai.engine.NanoBackend
import dev.baseline.ai.engine.NanoEngine
import dev.baseline.ai.engine.NanoStatus
import dev.baseline.ai.insight.InsightService
import dev.baseline.ai.parseBaseUrl
import dev.baseline.data.db.AiInsightEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private class FakeNano : NanoBackend {
    var availability = NanoAvailability.AVAILABLE
    var downloaded = 0

    override suspend fun availability() = availability

    override fun download(): Flow<Float?> = flow {
        downloaded++
        emit(0.5f)
        availability = NanoAvailability.AVAILABLE
    }

    override suspend fun baseModelName() = "nano-v4"

    override suspend fun tokenLimit() = 4096

    override suspend fun systemPromptSupported() = true

    override suspend fun countTokens(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean) = 100

    override fun stream(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int, temperature: Float): Flow<String> = emptyFlow()

    override suspend fun warmup() = Unit
}

private object NoCloud : AiEngine {
    override fun run(req: AiRequest): Flow<AiEvent> = emptyFlow()
}

@RunWith(AndroidJUnit4::class)
class AiSettingsViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var vault: AiKeyVault
    private lateinit var prefs: AiPrefs
    private val backend = FakeNano()
    private val nano = NanoEngine(backend, Clock.systemUTC(), MutableStateFlow(ZoneOffset.UTC))

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        file = File.createTempFile("aisettingsvm", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { file }
        vault = AiKeyVault(store, { it }, { it })
        prefs = AiPrefs(store)
    }

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    private fun viewModel(): AiSettingsViewModel {
        val entry = AiKeyEntry(OkHttpClient(), vault, prefs, { nano.status.value !is NanoStatus.Unavailable }, allowLoopbackHttp = false)
        val insights = InsightService(
            pack = { _, _ -> Pack("", PackFacts(null, null, null, null), false) },
            nano = nano,
            cloud = NoCloud,
            dao = db.aiInsightDao(),
            mode = prefs.mode,
            keyStatus = vault.status,
            online = MutableStateFlow(true),
            clock = Clock.fixed(Instant.parse("2026-11-01T08:00:00Z"), ZoneOffset.UTC),
        )
        return AiSettingsViewModel(prefs, vault, nano, entry, insights)
    }

    // Main-thread work (viewModelScope) only runs while the Robolectric looper is idled, so the tests poll instead of blocking.
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    // state is WhileSubscribed, so something has to collect it.
    private fun AiSettingsViewModel.collecting(): Job = CoroutineScope(Dispatchers.Default).launch { state.collect { } }

    @Test
    fun stateCombinesModeNanoAndKey() {
        runBlocking {
            nano.refresh()
            prefs.setMode(AiMode.ON_DEVICE)
        }
        val vm = viewModel()
        val collector = vm.collecting()
        waitFor("the combined state") { vm.state.value.mode == AiMode.ON_DEVICE && vm.state.value.nanoKnown }
        val s = vm.state.value
        assertEquals(NanoStatus.Ready("nano-v4", 4096), s.nano)
        assertEquals(AiKeyStatus.Missing, s.key)
        assertEquals(true, s.showHint)
        assertEquals(false, s.options.first { it.mode == AiMode.CLOUD }.enabled)
        collector.cancel()
    }

    @Test
    fun aStoredKeyEnablesCloudAndHybrid() {
        runBlocking {
            nano.refresh()
            vault.save(parseBaseUrl("https://openrouter.ai/api/v1", false)!!, "k", "deepseek/deepseek-v4.1-flash", true)
        }
        val vm = viewModel()
        val collector = vm.collecting()
        waitFor("the key") { vm.state.value.key is AiKeyStatus.Set }
        assertEquals(true, vm.state.value.options.first { it.mode == AiMode.CLOUD }.enabled)
        assertEquals(true, vm.state.value.options.first { it.mode == AiMode.HYBRID }.enabled)
        collector.cancel()
    }

    @Test
    fun setModeWritesThePreference() {
        viewModel().setMode(AiMode.OFF)
        waitFor("the mode") { runBlocking { prefs.mode.first() } == AiMode.OFF }
    }

    @Test
    fun removeKeyClearsTheVaultAndAppliesTheTransition() {
        runBlocking {
            nano.refresh()
            vault.save(parseBaseUrl("https://openrouter.ai/api/v1", false)!!, "k", "m", true)
            prefs.setMode(AiMode.HYBRID)
        }
        viewModel().removeKey()
        waitFor("the key to go") { runBlocking { vault.status.first() } == AiKeyStatus.Missing && runBlocking { prefs.mode.first() } == AiMode.ON_DEVICE }
        assertNull(runBlocking { vault.config() })
    }

    @Test
    fun clearInsightsEmptiesTheCache() {
        runBlocking { db.aiInsightDao().put(AiInsightEntity("today", "2026-11-01", "fp", "text", "nano", null, Instant.parse("2026-11-01T07:00:00Z"))) }
        viewModel().clearInsights()
        waitFor("the cache to empty") { runBlocking { db.aiInsightDao().get("today", "2026-11-01") } == null }
    }

    @Test
    fun downloadRunsTheEngineDownloadToCompletion() {
        backend.availability = NanoAvailability.DOWNLOADABLE
        runBlocking { nano.refresh() }
        viewModel().downloadNano()
        waitFor("Nano to be ready") { nano.status.value is NanoStatus.Ready }
        assertEquals(1, backend.downloaded)
    }
}
