package dev.baseline.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.ui.theme.BaselineTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {
    /**
     * Runs after the compose rule has torn the activity down. Disposing a focused text field writes snapshot state one last
     * time, which queues a dispatch on the process-wide AndroidUiDispatcher.Main; Robolectric then resets the looper and drops
     * that message, leaving the dispatcher marked as scheduled for good. Every later test in the JVM that relies on apply
     * notifications without idling (TodayScreenTest's manual-clock drag) then never recomposes. Draining the looper here lets
     * the queued dispatch run.
     */
    @get:Rule(order = 0)
    val drainMainLooper =
        object : ExternalResource() {
            override fun after() = ShadowLooper.idleMainLooper()
        }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scopes = ScopeCleanup()
    private lateinit var file: File
    private lateinit var vm: OnboardingViewModel

    @Before
    fun setUp() {
        file = File.createTempFile("onboarding", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        vm = scopes.track(OnboardingViewModel(ApiKeyVault(store, { it }, { it }, Clock.systemUTC())) { _, _ -> error("not submitted in these tests") })
        rule.setContent { BaselineTheme { OnboardingScreen(vm) } }
    }

    @After
    fun tearDown() {
        runBlocking { scopes.close() }
        file.delete()
    }

    @Test
    fun saveButtonEnablesOnlyOnceAKeyIsPasted() {
        rule.onNodeWithText("Save and test").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Personal API key").performTextInput("abc")
        rule.onNodeWithText("Save and test").assertIsEnabled()
    }

    @Test
    fun lifetimeChipsAreSelectableAndBigEnough() {
        rule.onNodeWithText("365 days").assertIsSelected()
        rule.onNodeWithText("90 days").assertIsNotSelected().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("90 days").assertIsSelected()
        rule.onNodeWithText("365 days").assertIsNotSelected()
    }

    @Test
    fun titleIsAHeading() {
        rule.onNode(isHeading() and hasText("Connect Garmin")).assertExists()
    }
}
