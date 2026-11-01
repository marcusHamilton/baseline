package dev.baseline.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import dev.baseline.AppContainer
import dev.baseline.ai.engine.ChatTurn
import dev.baseline.ai.engine.NanoAvailability
import dev.baseline.ai.engine.NanoBackend
import dev.baseline.ai.engine.NanoBackendException
import dev.baseline.ai.engine.NanoError
import dev.baseline.testutil.ScopeCleanup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private class StubNano(private val availability: NanoAvailability?) : NanoBackend {
    /** A null availability makes the check itself fail. */
    override suspend fun availability() = availability ?: throw NanoBackendException(NanoError.OTHER)

    override fun download(): Flow<Float?> = emptyFlow()

    override suspend fun baseModelName() = "nano-v4"

    override suspend fun tokenLimit() = 4096

    override suspend fun systemPromptSupported() = true

    override suspend fun countTokens(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean) = 0

    override fun stream(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int, temperature: Float): Flow<String> = emptyFlow()

    override suspend fun warmup() = Unit
}

private class StartupContainer(context: Context, private val store: DataStore<Preferences>, override val nanoBackend: NanoBackend) : AppContainer(context) {
    override val dataStore get() = store
}

@RunWith(RobolectricTestRunner::class)
class AiStartupTest {
    private val scopes = ScopeCleanup()
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private val containers = mutableListOf<AppContainer>()

    @Before
    fun setUp() {
        file = File.createTempFile("aistartup", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        containers.forEach { it.close() }
        scopes.close()
        file.delete()
    }

    /** One app start; a null [availability] is a failed check. Starts share the store, like successive launches. */
    private fun start(availability: NanoAvailability?, before: AiMode? = null): AiMode? = runBlocking {
        val c = StartupContainer(ApplicationProvider.getApplicationContext(), store, StubNano(availability)).also { containers += it }
        before?.let { c.aiPrefs.setMode(it) }
        c.startAi().join()
        c.aiPrefs.mode.first()
    }

    @Test
    fun undecidedModeBecomesOnDeviceWhenNanoIsAvailable() = assertEquals(AiMode.ON_DEVICE, start(NanoAvailability.AVAILABLE))

    @Test
    fun undecidedModeBecomesOnDeviceWhenNanoIsDownloadable() = assertEquals(AiMode.ON_DEVICE, start(NanoAvailability.DOWNLOADABLE))

    @Test
    fun undecidedModeBecomesOffWhenNanoIsUnavailable() = assertEquals(AiMode.OFF, start(NanoAvailability.UNAVAILABLE))

    @Test
    fun hybridBecomesCloudWhenNanoIsUnavailable() = assertEquals(AiMode.CLOUD, start(NanoAvailability.UNAVAILABLE, before = AiMode.HYBRID))

    @Test
    fun onDeviceBecomesOffWhenNanoIsUnavailable() = assertEquals(AiMode.OFF, start(NanoAvailability.UNAVAILABLE, before = AiMode.ON_DEVICE))

    @Test
    fun aDecidedModeIsKeptWhenNanoIsAvailable() = assertEquals(AiMode.CLOUD, start(NanoAvailability.AVAILABLE, before = AiMode.CLOUD))

    @Test
    fun aFailedCheckKeepsHybrid() = assertEquals(AiMode.HYBRID, start(null, before = AiMode.HYBRID))

    @Test
    fun aFailedCheckKeepsOnDevice() = assertEquals(AiMode.ON_DEVICE, start(null, before = AiMode.ON_DEVICE))

    @Test
    fun aFailedCheckLeavesAnUndecidedModeUndecidedUntilTheNextStart() {
        assertEquals(null, start(null))
        assertEquals(AiMode.ON_DEVICE, start(NanoAvailability.AVAILABLE))
    }
}
