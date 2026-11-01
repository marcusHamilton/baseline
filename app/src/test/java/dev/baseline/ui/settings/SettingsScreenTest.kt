package dev.baseline.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiMode
import dev.baseline.ai.engine.NanoStatus
import dev.baseline.garmin.KeyStatus
import dev.baseline.prefs.BanisterSet
import dev.baseline.prefs.SyncInterval
import dev.baseline.ui.theme.BaselineTheme
import dev.baseline.update.Release
import dev.baseline.update.UpdateState
import dev.baseline.update.UpdateUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
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
    private val offer = Release("v0.05", "Notes", "https://x", "https://x/a.apk", 54_815_418, null)
    private val keySet = AiKeyStatus.Set("openrouter.ai", "deepseek/deepseek-v4.1-flash", toolsSupported = true, invalid = false)

    private fun ai(mode: AiMode? = AiMode.OFF, nano: NanoStatus = NanoStatus.Unavailable, key: AiKeyStatus = AiKeyStatus.Missing) =
        buildAiSettingsState(mode, nano, true, key)

    private fun show(
        state: SettingsUiState = base,
        update: UpdateUi? = null,
        ai: AiSettingsState? = ai(),
        onOpen: (SettingsPage) -> Unit = {},
        onSyncNow: () -> Unit = {},
    ) = rule.setContent {
        BaselineTheme { SettingsHome(state, clock, clock.zone, update, ai, onOpen, onSyncNow) }
    }

    private fun row(page: SettingsPage) = rule.onNodeWithTag("settings-row-${page.route}")

    private fun assertRow(page: SettingsPage, title: String, summary: String) {
        row(page).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(72.dp)
        rule.onNode(hasText(title) and hasText(summary)).assertExists()
    }

    @Test
    fun keyCardMirrorsTheMockupStates() {
        assertEquals("Connected", keyCard(base.keyStatus).status)
        assertEquals("362 days left · Garmin emails you a week before", keyCard(base.keyStatus).detail)
        assertEquals("1 day left", keyCard(KeyStatus.ExpiringSoon(LocalDate.of(2026, 10, 25), 1)).detail)
        assertEquals("Expired", keyCard(KeyStatus.Expired(LocalDate.of(2026, 10, 20))).status)
        assertEquals(0f, keyCard(KeyStatus.Invalid).fill)
        assertEquals("Activities since Aug 2025 · Health since Sep 2025", coverageText(base.coverage))
        assertEquals("No activities yet · No health data yet", coverageText(HistoryCoverage(null, null)))
    }

    @Test
    fun garminSummaryPerKeyState() {
        assertEquals("Connected · until Oct 21, 2027", garminSummary(base.keyStatus))
        assertEquals("Expires soon · until Oct 25, 2026", garminSummary(KeyStatus.ExpiringSoon(LocalDate.of(2026, 10, 25), 1)))
        assertEquals("Expired Oct 20, 2026", garminSummary(KeyStatus.Expired(LocalDate.of(2026, 10, 20))))
        assertEquals("Key rejected", garminSummary(KeyStatus.Invalid))
        assertEquals("No key", garminSummary(KeyStatus.Missing))
        assertEquals("No key", garminSummary(null))
    }

    @Test
    fun aiSummaryPerState() {
        val ready = NanoStatus.Ready("nano-v4", 4096)
        assertEquals("…", aiSummary(ai(mode = null)))
        assertEquals("Off", aiSummary(ai(AiMode.OFF, ready, keySet)))
        assertEquals("On-device only · Nano ready", aiSummary(ai(AiMode.ON_DEVICE, ready)))
        assertEquals("Cloud only · deepseek-v4.1-flash", aiSummary(ai(AiMode.CLOUD, NanoStatus.Unavailable, keySet)))
        assertEquals("Hybrid · Nano ready · deepseek-v4.1-flash", aiSummary(ai(AiMode.HYBRID, ready, keySet)))
        assertEquals("Hybrid · Key rejected", aiSummary(ai(AiMode.HYBRID, ready, keySet.copy(invalid = true))))
        assertEquals("Off", aiSummary(ai(AiMode.OFF, ready, keySet.copy(invalid = true))))
    }

    @Test
    fun rowsShowTheirSummaries() {
        show(base, UpdateUi("0.04", true), ai(AiMode.HYBRID, NanoStatus.Ready("nano-v4", 4096), keySet))
        assertRow(SettingsPage.GARMIN, "Garmin connection", "Connected · until Oct 21, 2027")
        assertRow(SettingsPage.SYNC, "Sync", "Every hour · last Today, 11:00 AM")
        assertRow(SettingsPage.SCORES, "Scores & units", "Male-fitted strain · Metric")
        assertRow(SettingsPage.AI, "AI assistant", "Hybrid · Nano ready · deepseek-v4.1-flash")
        assertRow(SettingsPage.UPDATES, "Updates", "0.04 · Up to date")
        assertRow(SettingsPage.PRIVACY, "Privacy & data", "What leaves this phone")
        rule.onNodeWithText("Data from Garmin Connect Personal API. Not affiliated with Garmin.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun summariesFollowTheState() {
        show(
            base.copy(
                keyStatus = KeyStatus.Expired(LocalDate.of(2026, 10, 20)),
                lastSynced = null,
                interval = SyncInterval.MANUAL,
                banister = BanisterSet.FEMALE_FITTED,
                imperial = true,
            ),
            UpdateUi("0.04", false),
            ai(AiMode.OFF),
        )
        assertRow(SettingsPage.GARMIN, "Garmin connection", "Expired Oct 20, 2026")
        assertRow(SettingsPage.SYNC, "Sync", "Manual only · not synced yet")
        assertRow(SettingsPage.SCORES, "Scores & units", "Female-fitted strain · Imperial")
        assertRow(SettingsPage.AI, "AI assistant", "Off")
        assertRow(SettingsPage.UPDATES, "Updates", "0.04 · update checks off")
    }

    @Test
    fun invalidKeyAndRejectedAiKey() {
        show(base.copy(keyStatus = KeyStatus.Invalid), ai = ai(AiMode.CLOUD, key = keySet.copy(invalid = true)))
        assertRow(SettingsPage.GARMIN, "Garmin connection", "Key rejected")
        assertRow(SettingsPage.AI, "AI assistant", "Cloud only · Key rejected")
    }

    @Test
    fun failedItemsReplaceTheIntervalOnTheSyncRow() {
        show(base.copy(failedCount = 1))
        assertRow(SettingsPage.SYNC, "Sync", "1 item couldn't sync · last Today, 11:00 AM")
    }

    @Test
    fun severalFailedItemsArePlural() {
        show(base.copy(failedCount = 3))
        assertRow(SettingsPage.SYNC, "Sync", "3 items couldn't sync · last Today, 11:00 AM")
    }

    @Test
    fun anUpdateOfferIsTheUpdatesSummary() {
        show(update = UpdateUi("0.04", true, state = UpdateState.Available(offer)))
        assertRow(SettingsPage.UPDATES, "Updates", "Baseline 0.05 is available.")
    }

    @Test
    fun aFailedUpdateSaysWhy() {
        show(update = UpdateUi("0.04", true, state = UpdateState.Failed(offer, "Download didn't match the release checksum.")))
        assertRow(SettingsPage.UPDATES, "Updates", "Download didn't match the release checksum.")
    }

    @Test
    fun noUpdatesRowWithoutUpdateState() {
        show(update = null)
        row(SettingsPage.UPDATES).assertDoesNotExist()
    }

    @Test
    fun tappingARowOpensItsPage() {
        val opened = mutableListOf<SettingsPage>()
        show(update = UpdateUi("0.04", true), onOpen = { opened += it })
        SettingsPage.entries.forEach { row(it).performScrollTo().performClick() }
        assertEquals(SettingsPage.entries.toList(), opened)
    }

    @Test
    fun syncNowOnTheRowDoesNotOpenThePage() {
        var syncs = 0
        val opened = mutableListOf<SettingsPage>()
        show(onOpen = { opened += it }, onSyncNow = { syncs++ })
        rule.onNodeWithText("Sync now").performScrollTo().performClick()
        assertEquals(1, syncs)
        assertEquals(emptyList<SettingsPage>(), opened)
    }

    @Test
    fun bannerShowsWhenKeyExpired() {
        show(base.copy(keyStatus = KeyStatus.Expired(LocalDate.of(2026, 10, 20))))
        rule.onNodeWithText("Your Garmin key expired, so new data can't sync. Paste a new key in Settings.").assertIsDisplayed()
    }

    @Test
    fun screenAndGroupTitlesAreHeadings() {
        show(update = UpdateUi("0.04", true))
        listOf("SETTINGS", "DATA", "ASSISTANT", "APP").forEach {
            rule.onNode(isHeading() and hasText(it)).performScrollTo().assertExists()
        }
    }
}
