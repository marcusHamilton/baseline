package dev.baseline.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class AppCardTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val clock = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC)
    private val settings =
        SettingsUiState(
            KeyStatus.Ok(LocalDate.of(2027, 10, 21), 357),
            SyncInterval.HOURLY,
            BanisterSet.MALE_FITTED,
            null,
            HistoryCoverage(null, null),
            false,
            false,
        )
    private val offer = Release("v0.05", "Adds in-app updates.\nSecond line", "https://x", "https://x/a.apk", 54_815_418, null)

    private fun show(update: UpdateUi, onPrimary: () -> Unit = {}, onCheck: () -> Unit = {}, onAuto: (Boolean) -> Unit = {}) = rule.setContent {
        BaselineTheme {
            SettingsContent(
                settings, KeyEntryState.Idle, clock, clock.zone, { _, _ -> }, {}, {}, {}, {}, {},
                update = update, onUpdatePrimary = onPrimary, onCheckUpdates = onCheck, onAutoCheck = onAuto,
            )
        }
    }

    @Test
    fun upToDateShowsWhenItLastChecked() {
        var checks = 0
        show(UpdateUi("0.04", true, checkedAt = Instant.parse("2026-10-29T10:00:00Z")), onCheck = { checks++ })
        rule.onNodeWithText("0.04").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Up to date · checked 2 h ago").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Check now").performScrollTo().performClick()
        assertEquals(1, checks)
    }

    @Test
    fun beforeTheFirstCheckItDoesNotClaimUpToDate() {
        show(UpdateUi("0.04", true))
        rule.onNodeWithText("Not checked yet").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anOfferShowsItsNotesAndTheOneButton() {
        var taps = 0
        show(UpdateUi("0.04", true, state = UpdateState.Available(offer)), onPrimary = { taps++ })
        rule.onNodeWithTag("update-status").performScrollTo().assertTextEquals("Baseline 0.05 is available.")
        rule.onNodeWithText("Adds in-app updates.\nSecond line").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Update (55 MB)").performScrollTo().performClick()
        assertEquals(1, taps)
    }

    @Test
    fun aFailureSaysWhyAndOffersRetry() {
        show(UpdateUi("0.04", true, checkError = "Couldn't reach GitHub", state = UpdateState.Failed(offer, "Download didn't match the release checksum.")))
        rule.onNodeWithTag("update-status").performScrollTo().assertTextEquals("Download didn't match the release checksum.")
        rule.onNodeWithText("Retry").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Couldn't check for updates: Couldn't reach GitHub").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun whileInstallingThereIsNoButton() {
        show(UpdateUi("0.04", true, state = UpdateState.Installing(offer)))
        rule.onNodeWithTag("update-status").performScrollTo().assertTextEquals("Installing Baseline 0.05…")
        listOf("Update (55 MB)", "Install", "Retry", "Cancel").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun theDailyCheckSwitchReportsItsNewValue() {
        var auto: Boolean? = null
        show(UpdateUi("0.04", true, autoCheck = true), onAuto = { auto = it })
        rule.onNode(isToggleable()).performScrollTo().assertIsOn().performClick()
        assertEquals(false, auto)
    }

    @Test
    fun debugBuildsSayTheyDoNotCheck() {
        show(UpdateUi("0.04-debug", enabled = false))
        rule.onNodeWithText("Debug and test builds don't check for updates.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Check now").assertDoesNotExist()
        rule.onNode(isToggleable()).assertDoesNotExist()
    }
}
