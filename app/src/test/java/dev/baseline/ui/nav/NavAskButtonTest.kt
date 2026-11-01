package dev.baseline.ui.nav

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.baseline.AppContainer
import dev.baseline.ai.AiMode
import dev.baseline.ai.engine.ChatTurn
import dev.baseline.ai.engine.NanoAvailability
import dev.baseline.ai.engine.NanoBackend
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminResult
import dev.baseline.garmin.KeyTest
import dev.baseline.sync.SyncEngine
import dev.baseline.sync.SyncScheduler
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.testutil.inMemoryDatabase
import dev.baseline.ui.theme.BaselineTheme
import dev.baseline.widget.WidgetTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Nano that is always there and answers "Hello"; nothing here reaches ML Kit or the network. */
private class FakeNano : NanoBackend {
    override suspend fun availability() = NanoAvailability.AVAILABLE

    override fun download(): Flow<Float?> = flow { }

    override suspend fun baseModelName() = "nano-v4"

    override suspend fun tokenLimit() = 4096

    override suspend fun systemPromptSupported() = true

    override suspend fun countTokens(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean) = 100

    override fun stream(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int, temperature: Float): Flow<String> = flow {
        emit("Hello")
        emit(" there")
    }

    override suspend fun warmup() = Unit
}

private val TODAY: LocalDate = LocalDate.of(2026, 11, 1)

/** Identity-cipher vault, in-memory db, the test DataStore for the AI prefs too, a fixed clock and a fake Nano. */
private class AskTestContainer(context: Context, private val store: DataStore<Preferences>) :
    AppContainer(context, Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC)) {
    override val dataStore get() = store
    override val scheduler = SyncScheduler(WorkManager.getInstance(context), { true })
    override val apiKeyVault by lazy { ApiKeyVault(store, { it }, { it }, clock) }
    override val db: AppDatabase by lazy { inMemoryDatabase(context) }
    override val testKey: KeyTest = { _, _ -> GarminResult.Empty }
    override val syncEngine by lazy { SyncEngine({ _: GarminEndpoint, _: String -> GarminResult.Empty }, db, clock) {} }
    override val nanoBackend: NanoBackend = FakeNano()
}

@RunWith(RobolectricTestRunner::class)
class NavAskButtonTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scopes = ScopeCleanup()
    private lateinit var file: File
    private lateinit var container: AskTestContainer
    private val deepLink = MutableStateFlow<WidgetTarget?>(null)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        file = File.createTempFile("navask", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        container = AskTestContainer(ApplicationProvider.getApplicationContext(), store)
        runBlocking {
            container.apiKeyVault.save("k", 365)
            container.nanoEngine.refresh()
            container.aiPrefs.setMode(AiMode.HYBRID)
        }
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

    private fun show() {
        rule.setContent { BaselineTheme { BaselineNavHost(container, deepLink) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Today").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitTag(tag: String) = rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitText(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun askButton() = rule.onNodeWithContentDescription("Ask about this page")

    private fun openSheet() {
        // The mode is read from the DataStore; the button appears once it has been collected.
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Ask about this page").fetchSemanticsNodes().isNotEmpty() }
        askButton().performClick()
    }

    private fun closeSheet() {
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun buttonHiddenWhenOff() {
        runBlocking { container.aiPrefs.setMode(AiMode.OFF) }
        show()
        listOf("Today", "Activities", "Trends", "Settings").forEach { rule.onNodeWithText(it).assertExists() }
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Ask about this page").assertCountEqualsZero()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() {
        assertTrue(fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun buttonOpensSheetScopedToPage() {
        show()
        deepLink.value = WidgetTarget.SleepToday
        awaitTag("screen-sleep")
        openSheet()
        awaitText("Ask about last night")
        closeSheet()
    }

    @Test
    fun buttonDoesNotChangeSelectedTab() {
        show()
        deepLink.value = WidgetTarget.Activity(5)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Activity not found").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Activities") and isSelected()).assertExists()
        openSheet()
        awaitText("Ask about this activity")
        rule.onNode(hasText("Activities") and isSelected()).assertExists()
        closeSheet()
        rule.onNode(hasText("Activities") and isSelected()).assertExists()
        rule.onNodeWithText("Activity not found").assertExists()
    }

    @Test
    fun generalScopeOnSettings() {
        show()
        rule.onNodeWithText("Settings").performClick()
        awaitTag("screen-settings")
        openSheet()
        awaitText("Ask about your health")
        closeSheet()
    }

    @Test
    fun trendsScopeFollowsThePage() {
        show()
        rule.onNodeWithText("Trends").performClick()
        awaitTag("screen-trends")
        openSheet()
        awaitText("Ask about your trends")
        closeSheet()
    }

    @Test
    fun todayScopeIsReported() {
        show()
        awaitTag("screen-today")
        openSheet()
        awaitText("Ask about today")
        closeSheet()
    }

    @Test
    fun insightLinkOpensSameScope() {
        runBlocking {
            container.db.dayDao().upsert(DayEntity(TODAY))
            container.db.scoreDao().upsert(ScoreEntity(TODAY, 70, false, null, 100.0, 10.0, null, null, 1))
        }
        show()
        awaitText("Ask about today ›")
        rule.onNodeWithText("Ask about today ›").performScrollTo().performClick()
        awaitText("Ask about today")
        rule.onNodeWithContentDescription("Close").assertExists()
        closeSheet()
    }

    @Test
    fun closingDiscardsChat() {
        show()
        openSheet()
        awaitTag("ask-suggestion")
        rule.onNodeWithTag("ask-input").performTextInput("How did I do?")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Hello there", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("ask-suggestion").assertCountEqualsZero()

        closeSheet()
        openSheet()
        awaitTag("ask-suggestion")
        rule.onAllNodesWithText("How did I do?").assertCountEqualsZero()
        rule.onAllNodesWithText("Hello there", substring = true).assertCountEqualsZero()
    }

    @Test
    fun buttonIsAnnouncedAsAButtonNotATab() {
        show()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Ask about this page").fetchSemanticsNodes().isNotEmpty() }
        val config = askButton().fetchSemanticsNode().config
        assertEquals(androidx.compose.ui.semantics.Role.Button, config.getOrNull(SemanticsProperties.Role))
    }

    @Test
    fun touchTarget48dp() {
        show()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Ask about this page").fetchSemanticsNodes().isNotEmpty() }
        askButton().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
}
