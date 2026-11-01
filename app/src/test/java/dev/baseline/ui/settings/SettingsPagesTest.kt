package dev.baseline.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiMode
import dev.baseline.ai.engine.NanoStatus
import dev.baseline.garmin.KeyStatus
import dev.baseline.prefs.BanisterSet
import dev.baseline.prefs.SyncInterval
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The cards of the Settings sub-pages, rendered inside the page shell. */
@RunWith(RobolectricTestRunner::class)
class SettingsPagesTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC)
    private val base =
        SettingsUiState(
            KeyStatus.Ok(LocalDate.of(2027, 10, 21), 362),
            SyncInterval.HOURLY,
            BanisterSet.MALE_FITTED,
            Instant.parse("2026-10-24T11:00:00Z"),
            HistoryCoverage(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 8, 30)),
            false,
            false,
        )
    private val keySet = AiKeyStatus.Set("openrouter.ai", "deepseek/deepseek-v4.1-flash", toolsSupported = true, invalid = false)

    private fun page(page: SettingsPage, onBack: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) =
        rule.setContent { BaselineTheme { SettingsPageContent(page.title, onBack, content) } }

    private fun garmin(status: KeyStatus? = base.keyStatus, entry: KeyEntryState = KeyEntryState.Idle) =
        page(SettingsPage.GARMIN) { GarminPage(status, entry, { _, _ -> }, {}) }

    private fun sync(state: SettingsUiState = base, zone: ZoneId = clock.zone, onInterval: (SyncInterval) -> Unit = {}, onRetry: () -> Unit = {}) =
        page(SettingsPage.SYNC) { SyncCard(state, clock, zone, onInterval, {}, onRetry) }

    private fun scores(state: SettingsUiState = base, onBanister: (BanisterSet) -> Unit = {}, onRecalc: () -> Unit = {}) =
        page(SettingsPage.SCORES) { ScoresCard(state, onBanister, onRecalc) }

    @Test
    fun theShellHasABackButtonAndAHeadingTitle() {
        var backs = 0
        page(SettingsPage.SYNC, onBack = { backs++ }) { }
        rule.onNodeWithTag("screen-settings-page").assertExists()
        rule.onNode(isHeading() and hasText("SYNC")).assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun garminPageShowsStatusReplaceFormAndKeystoreLine() {
        garmin()
        rule.onNodeWithText("Connected").assertIsDisplayed()
        rule.onNodeWithText("Oct 21, 2027").assertIsDisplayed()
        rule.onNode(isHeading() and hasText("REPLACE KEY")).performScrollTo().assertExists()
        rule.onNodeWithText("Save and test connection").performScrollTo().assertIsNotEnabled() // nothing pasted yet
        rule.onNodeWithText(KEYSTORE_LINE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun garminPageFollowsTheKeyState() {
        garmin(KeyStatus.Expired(LocalDate.of(2026, 10, 20)))
        rule.onNodeWithText("Expired").assertIsDisplayed()
        rule.onNodeWithText("Sync paused. Cached data is still available.").assertIsDisplayed()
    }

    @Test
    fun syncPageShowsIntervalsLastSyncAndCoverage() {
        sync()
        rule.onNodeWithText("Every 3 hours").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Today, 11:00 AM").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Activities since Aug 2025 · Health since Sep 2025").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun lastSyncUsesTheClocksZoneNotTheHosts() {
        val old = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
        try {
            sync()
            rule.onNodeWithText("Today, 11:00 AM").performScrollTo().assertIsDisplayed()
        } finally {
            java.util.TimeZone.setDefault(old)
        }
    }

    @Test
    fun lastSyncReformatsWhenTheZoneChangesWhileOpen() {
        var zone by mutableStateOf<ZoneId>(ZoneOffset.UTC)
        rule.setContent { BaselineTheme { SettingsPageContent("SYNC", {}) { SyncCard(base, clock, zone, {}, {}, {}) } } }
        rule.onNodeWithText("Today, 11:00 AM").performScrollTo().assertIsDisplayed()
        zone = ZoneOffset.ofHours(2)
        rule.onNodeWithText("Today, 1:00 PM").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun retryFailedShowsOnlyWithFailuresAndFires() {
        var retries = 0
        var state by mutableStateOf(base)
        rule.setContent { BaselineTheme { SettingsPageContent("SYNC", {}) { SyncCard(state, clock, clock.zone, {}, {}, { retries++ }) } } }
        rule.onNodeWithText("Retry failed").assertDoesNotExist()
        state = base.copy(failedCount = 1)
        rule.onNodeWithText("1 item couldn't sync").performScrollTo().assertIsDisplayed()
        state = base.copy(failedCount = 3)
        rule.onNodeWithText("3 items couldn't sync").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Retry failed").performScrollTo().performClick()
        assertEquals(1, retries)
        state = base.copy(failedCount = 3, retrying = true)
        rule.onNodeWithText("Retry failed").assertIsNotEnabled()
        state = base
        rule.onNodeWithText("Retry failed").assertDoesNotExist()
    }

    @Test
    fun intervalChipsFireTheirCallback() {
        var interval: SyncInterval? = null
        sync(onInterval = { interval = it })
        rule.onNodeWithText("Manual only").performScrollTo().performClick()
        assertEquals(SyncInterval.MANUAL, interval)
    }

    @Test
    fun scoresPageControlsFireCallbacks() {
        var picked: BanisterSet? = null
        var recalcs = 0
        scores(onBanister = { picked = it }, onRecalc = { recalcs++ })
        rule.onNodeWithText("Metric").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Female-fitted").performScrollTo().performClick()
        rule.onNodeWithText("Recalculate history").performScrollTo().performClick()
        assertEquals(BanisterSet.FEMALE_FITTED, picked)
        assertEquals(1, recalcs)
    }

    @Test
    fun recalculatingDisablesTheCoefficientControls() {
        scores(base.copy(recalculating = true, imperial = true))
        rule.onNodeWithText("Recalculating…").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Imperial (from your Garmin profile)").performScrollTo().assertIsDisplayed()
    }

    private fun privacy(mode: AiMode?, key: AiKeyStatus = AiKeyStatus.Missing) =
        page(SettingsPage.PRIVACY) { PrivacyCard(buildAiSettingsState(mode, NanoStatus.Unavailable, true, key)) }

    private fun assertAiLine(text: String) {
        rule.onNodeWithText("AI").assertIsDisplayed()
        rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun privacyAlwaysStatesTheLocalGarminAndGithubFacts() {
        privacy(AiMode.OFF)
        rule.onNodeWithText("Synced data is stored only on this phone. Baseline has no server.").assertIsDisplayed()
        rule.onNodeWithText(KEYSTORE_LINE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(GITHUB_LINE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun privacyAiOff() {
        privacy(AiMode.OFF)
        assertAiLine("Off. Nothing is sent.")
    }

    @Test
    fun privacyAiOnDevice() {
        privacy(AiMode.ON_DEVICE)
        assertAiLine("On-device only. Answers are worked out on this phone and nothing is sent.")
    }

    @Test
    fun privacyAiCloudNamesTheHost() {
        privacy(AiMode.CLOUD, keySet)
        assertAiLine(cloudPrivacyLine("openrouter.ai"))
    }

    @Test
    fun privacyAiHybridNamesTheHost() {
        privacy(AiMode.HYBRID, keySet)
        assertAiLine(cloudPrivacyLine("openrouter.ai"))
    }

    @Test
    fun privacyAiCloudWithoutAKey() {
        privacy(AiMode.HYBRID)
        assertAiLine("Nothing is sent until you add an API key.")
    }

    @Test
    fun privacyAiUnknownMode() {
        privacy(null, keySet)
        assertAiLine("Nothing is sent until you add an API key.")
    }
}
