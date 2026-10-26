package dev.baseline.ui.sleep

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class SleepScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val date = LocalDate.of(2026, 10, 23)
    private val t0 = Instant.parse("2026-10-23T06:00:00Z")
    private val line = (0..30).map { t0.plusSeconds(it * 300L) to 50 + it }
    private val stages = listOf("Deep", "REM", "Light", "Awake").map { StageUi(it, null, null) }
    private val empty =
        SleepUiState(
            date,
            false,
            null,
            Duration.ZERO,
            Duration.ZERO,
            null,
            null,
            null,
            emptyList(),
            emptyList(),
            true,
            stages,
            null,
            SleepTiles("—", "—", "—", "—"),
        )

    @Test
    fun loadingThenChartsWithDescriptionsAndDashTiles() {
        rule.setContent { BaselineTheme { SleepContent(empty, true) {} } }
        rule.onNodeWithText("Loading overnight data…").assertExists()
        rule.onNodeWithContentDescription("Sleep unavailable").assertExists()
        rule.onNodeWithContentDescription("Overnight HRV, from", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Body Battery charged, no value").performScrollTo().assertExists()
    }

    @Test
    fun readyShowsChartsHeroAndNeedCard() {
        val ready =
            empty.copy(
                sleepPct = 86,
                asleep = Duration.ofMinutes(462),
                need = Duration.ofMinutes(538),
                garminScore = 84,
                bed = LocalTime.of(23, 2),
                wake = LocalTime.of(6, 54),
                hrvSeries = line,
                hrSeries = line,
                overnightLoading = false,
                needCard = SleepNeedUi(Duration.ofMinutes(460), Duration.ofMinutes(78), listOf("HRV ▲"), LocalTime.of(23, 40)),
            )
        rule.setContent { BaselineTheme { SleepContent(ready, false) {} } }
        rule.onNodeWithContentDescription("Sleep performance 86 percent").assertExists()
        rule.onNodeWithContentDescription("Overnight HRV, from 50 to 80 ms").assertExists()
        rule.onNodeWithContentDescription("Overnight heart rate, from 50 to 80 bpm").assertExists()
        rule.onNodeWithText("7h 42m").assertExists()
        rule.onNodeWithText("asleep of 8h 58m needed").assertExists()
        rule.onNodeWithText("11:02 PM → 6:54 AM · Garmin score 84").assertExists()
        rule.onNodeWithText("+ 1h 18m").performScrollTo().assertExists()
        rule.onNodeWithText("8h 58m · bed 11:40 PM").performScrollTo().assertExists()
        assertEquals("Oct 22 – 23", nightLabel(date))
    }
}
