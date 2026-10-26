package dev.baseline.ui.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var vault: ApiKeyVault

    private val clock = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        file = File.createTempFile("vault", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { file }
        vault = ApiKeyVault(store, { it }, { it }, clock)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        file.delete()
    }

    private suspend fun GarminResult.pass(onValid: suspend () -> Unit) {
        if (this is GarminResult.Ok || this == GarminResult.Empty) onValid()
    }

    private suspend fun OnboardingViewModel.settle() = withTimeout(5_000) { state.first { it is OnboardingState.Done || it is OnboardingState.Error } }

    @Test
    fun submitSavesOnlyAfterSuccessfulTest() = runBlocking {
        var result: GarminResult = GarminResult.KeyInvalid
        var keyDuringTest: String? = null
        var testedKey: String? = null
        val vm =
            OnboardingViewModel(vault) { candidate, onValid ->
                keyDuringTest = vault.key()
                testedKey = candidate
                result.also { it.pass(onValid) }
            }

        vm.submit("k", 365)
        assertEquals(
            OnboardingState.Error("Garmin didn't accept this key. Check you copied all of it."),
            vm.settle(),
        )
        assertNull(vault.key())

        result = GarminResult.Ok(ByteArray(0), null)
        vm.submit("  k  ", 365)
        assertEquals(OnboardingState.Done, vm.settle())
        assertEquals("k", vault.key()) // trimmed
        assertEquals("k", testedKey) // the trimmed candidate is what gets tested
        assertNull(keyDuringTest) // and it is not in the vault while the test runs
    }

    @Test
    fun errorMessagesAndClearOnEveryFailure() = runBlocking {
        val cases =
            listOf<suspend () -> GarminResult>(
                { GarminResult.RateLimited(429) },
                { throw IOException("boom") },
                { GarminResult.Failed(403, "Forbidden") },
                { GarminResult.BadRequest("x") },
                { throw IllegalStateException("keystore exploded") },
            )
        val expected =
            listOf(
                "Garmin is busy right now. Try again in a minute.",
                "No connection. Check your internet and try again.",
                "Couldn't reach Garmin (code 403).",
                "Couldn't reach Garmin (code 400).",
                "Couldn't reach Garmin (code unknown).",
            )
        cases.zip(expected).forEach { (case, msg) ->
            val vm = OnboardingViewModel(vault) { _, onValid -> case().also { it.pass(onValid) } }
            vm.submit("secret", 30)
            assertEquals(OnboardingState.Error(msg), vm.settle())
            assertNull(vault.key())
        }
    }

    @Test
    fun emptyCountsAsSuccessAndBlankIsIgnored() = runBlocking {
        var calls = 0
        val vm =
            OnboardingViewModel(vault) { _, onValid ->
                calls++
                GarminResult.Empty.also { it.pass(onValid) }
            }
        vm.submit("   ", 365)
        assertEquals(OnboardingState.Idle, vm.state.value)
        assertEquals(0, calls)
        assertNull(vault.key())
        vm.submit("k", 90)
        assertTrue(vm.settle() is OnboardingState.Done)
    }
}
