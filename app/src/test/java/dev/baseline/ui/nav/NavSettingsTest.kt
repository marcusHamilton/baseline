package dev.baseline.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.baseline.ai.AiMode
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.ui.settings.SettingsPage
import dev.baseline.ui.theme.BaselineTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Settings is a list; each row pushes a sub-page, and the Settings tab stays selected on it. */
@RunWith(RobolectricTestRunner::class)
class NavSettingsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scopes = ScopeCleanup()
    private lateinit var file: File
    private lateinit var container: AskTestContainer

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        file = File.createTempFile("navsettings", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        container = AskTestContainer(ApplicationProvider.getApplicationContext(), store)
        runBlocking {
            container.apiKeyVault.save("k", 365)
            container.nanoEngine.refresh()
            container.aiPrefs.setMode(AiMode.HYBRID)
        }
        rule.setContent { BaselineTheme { BaselineNavHost(container) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Today").fetchSemanticsNodes().isNotEmpty() }
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

    private fun awaitTag(tag: String) = rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun openGarmin() {
        rule.onNodeWithText("Settings").performClick()
        awaitTag("screen-settings")
        rule.onNodeWithTag("settings-row-${SettingsPage.GARMIN.route}").performScrollTo().performClick()
        awaitTag("screen-settings-page")
    }

    @Test
    fun aRowOpensItsPageAndBackReturnsToTheList() {
        openGarmin()
        rule.onNode(isHeading() and hasText("GARMIN CONNECTION")).assertExists()
        rule.onNode(hasText("Settings") and isSelected()).assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        awaitTag("screen-settings")
        rule.onAllNodesWithTag("screen-settings-page").assertCountIsZero()
    }

    @Test
    fun theSettingsTabFromAPageShowsTheList() {
        openGarmin()
        rule.onNodeWithText("Settings").performClick()
        awaitTag("screen-settings")
        rule.onAllNodesWithTag("screen-settings-page").assertCountIsZero()
    }

    @Test
    fun everyPageOpens() {
        rule.onNodeWithText("Settings").performClick()
        awaitTag("screen-settings")
        SettingsPage.entries.forEach { page ->
            rule.onNodeWithTag("settings-row-${page.route}").performScrollTo().performClick()
            awaitTag("screen-settings-page")
            rule.onNode(isHeading() and hasText(page.title)).assertExists()
            rule.onNodeWithContentDescription("Back").performClick()
            awaitTag("screen-settings")
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountIsZero() = org.junit.Assert.assertTrue(fetchSemanticsNodes().isEmpty())
}
