package dev.baseline.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.baseline.garmin.KeyStatus
import dev.baseline.prefs.BanisterSet
import dev.baseline.prefs.SyncInterval
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC)
    private val base = SettingsUiState(
        KeyStatus.Ok(LocalDate.of(2027, 10, 21), 362), SyncInterval.HOURLY, BanisterSet.MALE_FITTED,
        Instant.parse("2026-10-24T11:00:00Z"), HistoryCoverage(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 8, 30)), false, false,
    )

    private fun show(
        state: SettingsUiState, entry: KeyEntryState = KeyEntryState.Idle,
        onBanister: (BanisterSet) -> Unit = {}, onRecalc: () -> Unit = {}, onInterval: (SyncInterval) -> Unit = {},
    ) = rule.setContent {
        BaselineTheme { SettingsContent(state, entry, clock, clock.zone, { _, _ -> }, {}, onInterval, {}, onBanister, onRecalc) }
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
    fun showsKeySyncAndScoresCards() {
        show(base)
        rule.onNodeWithText("Connected").assertIsDisplayed()
        rule.onNodeWithText("Oct 21, 2027").assertIsDisplayed()
        rule.onNodeWithText("Save and test connection").performScrollTo().assertIsNotEnabled() // nothing pasted yet
        rule.onNodeWithText("Every 3 hours").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Today, 11:00 AM").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Activities since Aug 2025 · Health since Sep 2025").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Metric").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Recalculate history").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun lastSyncUsesTheClocksZoneNotTheHosts() {
        val old = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
        try {
            rule.setContent {
                BaselineTheme { SettingsContent(base, KeyEntryState.Idle, clock, clock.zone, { _, _ -> }, {}, {}, {}, {}, {}) }
            }
            rule.onNodeWithText("Today, 11:00 AM").performScrollTo().assertIsDisplayed()
        } finally { java.util.TimeZone.setDefault(old) }
    }

    @Test
    fun lastSyncReformatsWhenTheZoneChangesWhileOpen() {
        var zone by mutableStateOf<ZoneId>(ZoneOffset.UTC)
        rule.setContent {
            BaselineTheme { SettingsContent(base, KeyEntryState.Idle, clock, zone, { _, _ -> }, {}, {}, {}, {}, {}) }
        }
        rule.onNodeWithText("Today, 11:00 AM").performScrollTo().assertIsDisplayed()
        zone = ZoneOffset.ofHours(2)
        rule.onNodeWithText("Today, 1:00 PM").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun retryFailedShowsOnlyWithFailuresAndFires() {
        var retries = 0
        var state by mutableStateOf(base)
        rule.setContent {
            BaselineTheme { SettingsContent(state, KeyEntryState.Idle, clock, clock.zone, { _, _ -> }, {}, {}, {}, {}, {}, { retries++ }) }
        }
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
    fun bannerShowsWhenKeyExpiredAndControlsFireCallbacks() {
        var picked: BanisterSet? = null
        var recalcs = 0
        var interval: SyncInterval? = null
        show(
            base.copy(keyStatus = KeyStatus.Expired(LocalDate.of(2026, 10, 20))),
            onBanister = { picked = it }, onRecalc = { recalcs++ }, onInterval = { interval = it },
        )
        rule.onNodeWithText("Your Garmin key expired, so new data can't sync. Paste a new key in Settings.").assertIsDisplayed()
        rule.onNodeWithText("Female-fitted").performScrollTo().performClick()
        rule.onNodeWithText("Recalculate history").performScrollTo().performClick()
        rule.onNodeWithText("Manual only").performScrollTo().performClick()
        assertEquals(BanisterSet.FEMALE_FITTED, picked)
        assertEquals(1, recalcs)
        assertEquals(SyncInterval.MANUAL, interval)
    }

    @Test
    fun recalculatingDisablesTheCoefficientControls() {
        show(base.copy(recalculating = true, imperial = true))
        rule.onNodeWithText("Recalculating…").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Imperial (from your Garmin profile)").performScrollTo().assertIsDisplayed()
    }
}
