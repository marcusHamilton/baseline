package dev.baseline.ui.nav

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.baseline.AppContainer
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminResult
import dev.baseline.garmin.KeyTest
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.baseline.sync.SyncEngine
import dev.baseline.sync.SyncScheduler
import dev.baseline.ui.theme.BaselineTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Counts Today's refresh requests; the test WorkManager keeps the work enqueued, so nothing actually syncs. */
private class CountingScheduler(wm: WorkManager) : SyncScheduler(wm, { true }) {
    val syncSoons = AtomicInteger()
    override suspend fun syncSoon() { syncSoons.incrementAndGet(); super.syncSoon() }
}

/** Identity-cipher vault, in-memory db and an offline engine: no Keystore, no network. */
private class TestContainer(
    context: Context,
    private val store: DataStore<Preferences>,
    private val fetch: suspend (GarminEndpoint, String) -> GarminResult,
) : AppContainer(context) {
    override val scheduler = CountingScheduler(WorkManager.getInstance(context))
    override val apiKeyVault by lazy { ApiKeyVault(store, { it }, { it }, clock) }
    override val db: AppDatabase by lazy {
        Room.inMemoryDatabaseBuilder<AppDatabase>(context) { AppDatabase_Impl() }.allowMainThreadQueries().build()
    }
    override val testKey: KeyTest = { _, onValid ->
        fetch(GarminEndpoint.PROFILE, "").also { if (it is GarminResult.Ok || it == GarminResult.Empty) onValid() }
    }
    override val syncEngine by lazy { SyncEngine(fetch, db, clock) {} }
}

@RunWith(RobolectricTestRunner::class)
class NavShellTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scopes = ScopeCleanup()
    private lateinit var file: File
    private lateinit var container: TestContainer
    private val dataFetches = AtomicInteger()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        file = File.createTempFile("navshell", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        container = TestContainer(ApplicationProvider.getApplicationContext(), store) { e, _ ->
            if (e != GarminEndpoint.PROFILE) dataFetches.incrementAndGet()
            GarminResult.Empty
        }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        // Destroy the activity first: that clears the ViewModels the nav host created, whose collectors read the database.
        rule.activityRule.scenario.close()
        scopes.close()
        container.close()
        container.db.close()
        dev.baseline.sync.closeWorkManagerDb(ApplicationProvider.getApplicationContext())
        file.delete()
    }

    private fun show() = rule.setContent { BaselineTheme { BaselineNavHost(container) } }

    /** The vault's first emission decides the start destination; wait for it before touching tabs. */
    private fun showWithKey() {
        runBlocking { container.apiKeyVault.save("k", 365) }
        show()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Today").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun bottomBarHasFourTabs() {
        showWithKey()
        listOf("Today", "Activities", "Trends", "Settings").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        rule.onNodeWithText("Trends").performClick()
        rule.onNodeWithTag("screen-trends").assertIsDisplayed()
    }

    @Test
    fun tabsUseIconsNotLetterGlyphs() {
        showWithKey()
        // The old placeholder rendered the first letter of each label as the icon.
        listOf("T", "A", "S").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun missingKeyStartsOnboardingWithoutBottomBar() {
        show()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Save and test").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Personal API key").assertExists()
        rule.onNodeWithText("Activities").assertDoesNotExist()
    }

    @Test
    fun tabsBehaveTheSameAfterFirstRunOnboarding() {
        show()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Save and test").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("some-key")
        rule.onNodeWithText("Save and test").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("screen-today").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Trends").performClick()
        rule.onNodeWithTag("screen-trends").assertIsDisplayed()
        rule.onNodeWithText("Today").performClick()
        rule.onNodeWithTag("screen-today").assertIsDisplayed()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue("Back should leave the app, not land on Trends", rule.activity.isFinishing)
        assertEquals("Today asks the scheduler once (syncSoon) and never fetches itself", 1, container.scheduler.syncSoons.get())
        assertEquals(0, dataFetches.get())
    }

    private fun awaitTag(tag: String) = rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun tabTapOpensItsOwnPageNotTheSubPageLeftOpen() {
        showWithKey()
        rule.onNodeWithText("Activities").performClick()
        awaitTag("screen-activities")
        rule.onNodeWithContentDescription("Records").performClick()
        awaitTag("screen-records")
        // Records belongs to Activities, so that tab stays highlighted.
        rule.onNode(hasText("Activities") and isSelected()).assertExists()

        rule.onNodeWithText("Activities").performClick()
        awaitTag("screen-activities")
        rule.onNodeWithTag("screen-records").assertDoesNotExist()

        // Same after leaving for another tab and coming back.
        rule.onNodeWithContentDescription("Records").performClick()
        awaitTag("screen-records")
        rule.onNodeWithText("Trends").performClick()
        awaitTag("screen-trends")
        rule.onNodeWithText("Activities").performClick()
        awaitTag("screen-activities")
        rule.onNodeWithTag("screen-records").assertDoesNotExist()

        // Back from a tab still lands on Today, with its tab highlighted again.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        awaitTag("screen-today")
        rule.onNode(hasText("Today") and isSelected()).assertExists()
    }

    @Test
    fun todayTabReturnsToTheCurrentDay() {
        showWithKey()
        val today = LocalDate.now(container.clock)
        rule.onNodeWithContentDescription("Previous day").performClick()
        awaitText(weekday(today.minusDays(1)))
        rule.onNodeWithText("Trends").performClick()
        awaitTag("screen-trends")
        rule.onNodeWithText("Today").performClick()
        awaitText("TODAY")

        // Also when Today is already showing.
        rule.onNodeWithContentDescription("Previous day").performClick()
        awaitText(weekday(today.minusDays(1)))
        rule.onNodeWithText("Today").performClick()
        awaitText("TODAY")
    }

    private fun cellFor(date: LocalDate) = SemanticsMatcher("calendar cell for $date") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?.startsWith(date.format(DateTimeFormatter.ofPattern("MMMM d", Locale.US)) + ",") == true
    }

    private fun openDayFromCalendar(date: LocalDate, today: LocalDate) {
        rule.onNodeWithText("TODAY").performClick() // the date header opens the calendar
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("screen-calendar").fetchSemanticsNodes().isNotEmpty() }
        if (date.month != today.month) rule.onNodeWithContentDescription("Previous month").performClick()
        rule.onNode(cellFor(date)).performScrollTo().performClick()
        rule.onNodeWithText("Open day →").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("screen-today").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US).uppercase()

    private fun awaitText(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun openDayReturnsSelectedDateToToday() {
        showWithKey()
        val today = LocalDate.now(container.clock)
        openDayFromCalendar(today.minusDays(1), today)
        awaitText(weekday(today.minusDays(1)))
        rule.onNodeWithContentDescription("Next day").performClick() // same Today ViewModel: stepping forward works
        awaitText("TODAY")
        // The hand-off was cleared, so a second pick is delivered too.
        openDayFromCalendar(today.minusDays(2), today)
        awaitText(weekday(today.minusDays(2)))
    }
}
