package dev.baseline.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.baseline.AppContainer
import dev.baseline.data.db.AppDatabase
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminResult
import dev.baseline.garmin.KeyTest
import dev.baseline.sync.SyncEngine
import dev.baseline.sync.SyncScheduler
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.testutil.inMemoryDatabase
import dev.baseline.ui.nav.BaselineNavHost
import dev.baseline.ui.theme.BaselineTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private class DeepLinkTestContainer(context: Context, private val store: DataStore<Preferences>) : AppContainer(context) {
    override val scheduler = SyncScheduler(WorkManager.getInstance(context), { true })
    override val apiKeyVault by lazy { ApiKeyVault(store, { it }, { it }, clock) }
    override val db: AppDatabase by lazy {
        inMemoryDatabase(context)
    }
    override val testKey: KeyTest = { _, onValid ->
        onValid()
        GarminResult.Empty
    }
    override val syncEngine by lazy { SyncEngine({ _: GarminEndpoint, _: String -> GarminResult.Empty }, db, clock) {} }
}

@RunWith(RobolectricTestRunner::class)
class WidgetDeepLinkTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scopes = ScopeCleanup()
    private lateinit var file: File
    private lateinit var container: DeepLinkTestContainer

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        file = File.createTempFile("widgetdeeplink", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        container = DeepLinkTestContainer(ApplicationProvider.getApplicationContext(), store)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        rule.activityRule.scenario.close()
        scopes.close()
        container.close()
        container.db.close()
        dev.baseline.sync.closeWorkManagerDb(ApplicationProvider.getApplicationContext())
        file.delete()
    }

    private fun awaitTag(tag: String) = rule.waitUntil(5_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitText(text: String) = rule.waitUntil(5_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun intentsForDifferentTargetsAreNotFilterEqualAndRoundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val targets =
            listOf(
                WidgetTarget.Today,
                WidgetTarget.SleepToday,
                WidgetTarget.Activities,
                WidgetTarget.Activity(1L),
                WidgetTarget.Activity(2L),
                WidgetTarget.Settings,
            )
        for (i in targets.indices) {
            val intentI = WidgetRoutes.intent(ctx, targets[i])
            assertEquals(targets[i], WidgetRoutes.parse(intentI))
            for (j in i + 1 until targets.size) {
                val intentJ = WidgetRoutes.intent(ctx, targets[j])
                assertFalse("Intents must not be filterEquals: ${targets[i]} vs ${targets[j]}", intentI.filterEquals(intentJ))
                assertNotEquals(
                    WidgetRoutes.pendingIntent(ctx, targets[i]),
                    WidgetRoutes.pendingIntent(ctx, targets[j]),
                )
            }
        }
        assertNull(WidgetRoutes.parse(null))
        assertNull(WidgetRoutes.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(WidgetRoutes.parse(Intent(Intent.ACTION_VIEW, Uri.parse("baseline://widget/activity/not-a-id"))))
    }

    @Test
    fun deepLinksNavigateAndPreserveBackStack() {
        runBlocking { container.apiKeyVault.save("k", 365) }
        val deepLink = MutableStateFlow<WidgetTarget?>(WidgetTarget.SleepToday)
        rule.setContent {
            BaselineTheme {
                BaselineNavHost(container, deepLink = deepLink, onDeepLinkConsumed = { deepLink.value = null })
            }
        }

        // Cold start into SleepToday -> Back returns to Today.
        awaitTag("screen-sleep")
        assertNull(deepLink.value)
        rule.onNodeWithContentDescription("Back").performClick()
        awaitTag("screen-today")

        // Step Today to yesterday, then trigger WidgetTarget.Today -> resets to TODAY.
        val today = LocalDate.now(container.clock)
        val yesterdayWeekday = today.minusDays(1).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US).uppercase()
        rule.onNodeWithContentDescription("Previous day").performClick()
        awaitText(yesterdayWeekday)
        deepLink.value = WidgetTarget.Today
        awaitText("TODAY")

        // Activity(42L) -> opens Activity detail with Activities tab selected; Back returns to Activities.
        deepLink.value = WidgetTarget.Activity(42L)
        awaitTag("screen-activity")
        rule.onNode(hasText("Activities") and isSelected()).assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        awaitTag("screen-activities")

        // Settings -> opens Settings; Activities -> opens Activities.
        deepLink.value = WidgetTarget.Settings
        awaitTag("screen-settings")
        deepLink.value = WidgetTarget.Activities
        awaitTag("screen-activities")
    }

    @Test
    fun missingKeyStaysOnOnboarding() {
        val deepLink = MutableStateFlow<WidgetTarget?>(WidgetTarget.SleepToday)
        rule.setContent {
            BaselineTheme {
                BaselineNavHost(container, deepLink = deepLink, onDeepLinkConsumed = { deepLink.value = null })
            }
        }
        awaitText("Save and test")
        rule.onNodeWithText("Personal API key").assertExists()
        rule.onNodeWithText("Activities").assertDoesNotExist()
        assertNull(deepLink.value)
    }

    @Test
    fun consumedDeepLinkDoesNotReplayOnRecomposition() {
        runBlocking { container.apiKeyVault.save("k", 365) }
        val deepLink = MutableStateFlow<WidgetTarget?>(WidgetTarget.Settings)
        rule.setContent {
            BaselineTheme {
                BaselineNavHost(container, deepLink = deepLink, onDeepLinkConsumed = { deepLink.value = null })
            }
        }
        awaitTag("screen-settings")
        assertNull(deepLink.value)

        rule.onNodeWithText("Trends").performClick()
        awaitTag("screen-trends")
        rule.onNodeWithTag("screen-trends").assertIsDisplayed()
    }
}
