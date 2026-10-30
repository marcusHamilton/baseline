package dev.baseline.ui.trends

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TrendsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val today = LocalDate.of(2026, 10, 24)

    @Test fun d7StateRendersCardsAverages_andRangeChipsAreBigEnough() {
        val days =
            (0L..6).map {
                DayEntity(
                    today.minusDays(it),
                    restingHr = 48,
                    hrvLastNight = 58.0,
                    hrvWeeklyAvg = 55.0,
                    hrvBalancedLow = 50.0,
                    hrvBalancedUpper = 65.0,
                    sleepSeconds = 26_400,
                    sleepNeedMinutes = 480,
                )
            }
        val scores = (0L..5).map { ScoreEntity(today.minusDays(it), 70, false, null, 0.0, 0.0, null, null, 1) } // one day missing
        val picked = mutableListOf<TrendRange>()
        rule.setContent { BaselineTheme { TrendsContent(buildTrendsState(TrendRange.D7, today, days, scores), { picked += it }) } }
        rule.onNodeWithText("avg 70%").assertExists()
        rule.onNodeWithText("58 ms · baseline 55").assertExists()
        rule.onNodeWithText("48 bpm · avg 48").assertExists()
        rule.onNodeWithText("avg 7h 20m").assertExists()
        rule.onNodeWithContentDescription("Daily recovery, 6 of 7 days", substring = true).assertExists()
        listOf("7 days", "30 days", "90 days").forEach { rule.onNodeWithText(it).assertHasClickAction().assertHeightIsAtLeast(48.dp) }
        rule.onNodeWithText("90 days").performClick()
        assertEquals(listOf(TrendRange.D90), picked)
    }

    @Test fun everyCardLabelsTheRangeStartAndToday() {
        val days = (0L..6).map { DayEntity(today.minusDays(it), restingHr = 48, hrvLastNight = 58.0, sleepSeconds = 26_400, sleepNeedMinutes = 480) }
        rule.setContent { BaselineTheme { TrendsContent(buildTrendsState(TrendRange.D7, today, days, emptyList()), {}) } }
        // The axis is hidden from TalkBack (the chart descriptions give the span), so look in the unmerged tree.
        rule.onAllNodesWithText("Oct 18", useUnmergedTree = true).assertCountEquals(4)
        rule.onAllNodesWithText("Today", useUnmergedTree = true).assertCountEquals(4)
        rule.onAllNodesWithText("Oct 18").assertCountEquals(0)
    }

    @Test fun emptyStateShowsDashesNotNaN() {
        rule.setContent { BaselineTheme { TrendsContent(buildTrendsState(TrendRange.D30, today, emptyList(), emptyList()), {}) } }
        rule.onAllNodesWithText("avg —").assertCountEquals(2)
        rule.onAllNodesWithText("— · avg —").assertCountEquals(2)
    }

    @Test fun screenAndCardTitlesAreHeadings() {
        rule.setContent { BaselineTheme { TrendsContent(buildTrendsState(TrendRange.D7, today, emptyList(), emptyList()), {}) } }
        listOf("TRENDS", "RECOVERY", "HRV · OVERNIGHT", "RESTING HEART RATE", "SLEEP vs NEED").forEach {
            rule.onNode(isHeading() and hasText(it)).assertExists()
        }
    }
}
