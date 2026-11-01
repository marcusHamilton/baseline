package dev.baseline.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.baseline.scores.FormState
import dev.baseline.scores.TrainingLoad
import dev.baseline.scores.TrainingLoadDay
import dev.baseline.scores.WeekBudget
import dev.baseline.ui.theme.BaselineTheme
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.StrainBlue
import dev.baseline.ui.today.TrainingLoadUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class WeekLoadBarTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val w = LocalDate.of(2026, 10, 26)

    private fun ui(pct: Int = 67, over: Boolean = false, hard: Int? = 3, daysLeft: Int = 4, state: FormState = FormState.PRODUCTIVE, budget: Boolean = true) =
        TrainingLoadUi(
            TrainingLoadDay(w.plusDays(2), 64.0, 76.0, -18, state, null),
            WeekBudget(w, pct, over, hard, daysLeft).takeIf { budget },
        )

    private fun show(ui: TrainingLoadUi?, label: String = "WEEK LOAD") {
        rule.setContent { BaselineTheme { Box(Modifier.width(320.dp)) { WeekLoadBar(ui, label, Modifier) } } }
    }

    private fun trackWidth() = rule.onNodeWithTag(WEEK_BAR_TRACK_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.right - it.left }

    private fun fillWidth() = rule.onNodeWithTag(WEEK_BAR_FILL_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.right - it.left }

    private fun fillColor(color: Color) =
        rule.onNodeWithTag(WEEK_BAR_FILL_TAG, useUnmergedTree = true).assert(SemanticsMatcher.expectValue(WeekBarFillColor, color))

    @Test fun scaleIsZeroToTwoHundredWithTheSafeLimitFromTheModel() {
        assertEquals(0f, weekBarFraction(0), 0f)
        assertEquals(0.335f, weekBarFraction(67), 0.0001f)
        assertEquals(0.5f, weekBarFraction(100), 0f)
        assertEquals(1f, weekBarFraction(200), 0f)
        assertEquals(1f, weekBarFraction(340), 0f)
        assertEquals(0f, weekBarFraction(-5), 0f)
        assertEquals("the green band ends at the model's safe limit", (TrainingLoad.SAFE_BAND * 100).toInt(), 130)
        assertEquals(0.65f, weekBarFraction((TrainingLoad.SAFE_BAND * 100).toInt()), 0.0001f)
    }

    @Test fun fillIsPctOfTheTwoHundredScaleInBlue() {
        show(ui(pct = 67))
        assertEquals(0.335f, fillWidth().value / trackWidth().value, 0.01f)
        fillColor(StrainBlue)
    }

    @Test fun fillIsCappedAtTheEndOfTheTrack() {
        show(ui(pct = 340, over = true, hard = 0))
        assertEquals(1f, fillWidth().value / trackWidth().value, 0.01f)
    }

    @Test fun pastTheSafeLimitTheFillIsAmber() {
        show(ui(pct = 141, over = true, hard = 0))
        fillColor(RecoveryAmber)
        assertEquals(0.705f, fillWidth().value / trackWidth().value, 0.01f)
    }

    @Test fun normalAndSafeLimitTicksSitAtOneHundredAndOneThirtyPercent() {
        show(ui())
        val track = rule.onNodeWithTag(WEEK_BAR_TRACK_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val normal = rule.onNodeWithTag(WEEK_BAR_NORMAL_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val safe = rule.onNodeWithTag(WEEK_BAR_SAFE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val width = track.right - track.left
        assertEquals(0.5f, (normal.left - track.left).value / width.value, 0.02f)
        assertEquals(0.65f, (safe.left - track.left).value / width.value, 0.02f)
        assertTrue("ticks are 1.5dp wide", (normal.right - normal.left).value in 1f..2f)
        rule.onNodeWithText("normal", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("safe limit", useUnmergedTree = true).assertExists()
    }

    @Test fun showsTheLabelAndThePercent() {
        show(ui(pct = 67), "WEEK LOAD")
        rule.onNodeWithText("WEEK LOAD", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("67% of normal", useUnmergedTree = true).assertExists()
    }

    @Test fun thisWeekLabelOnTrends() {
        show(ui(), "THIS WEEK")
        rule.onNodeWithText("THIS WEEK", useUnmergedTree = true).assertExists()
    }

    @Test fun theLineUnderTheTrackNamesTheStateAndTheHardDaysLeft() {
        assertEquals("Productive week · about 3 hard days left before you overreach.", weekBarLine(ui(hard = 3)))
        assertEquals("Productive week · about 1 hard day left before you overreach.", weekBarLine(ui(hard = 1)))
        assertEquals("Productive week · easy days from here.", weekBarLine(ui(hard = 0)))
        assertEquals("Productive week", weekBarLine(ui(pct = 141, over = true, hard = 0)))
        assertEquals("Productive week", weekBarLine(ui(hard = 2, daysLeft = 0)))
        assertEquals("Productive week", weekBarLine(ui(hard = null)))
        assertEquals("Overreaching week", weekBarLine(ui(over = true, state = FormState.OVERREACHING)))
    }

    @Test fun theLineIsShown() {
        show(ui(hard = 3))
        rule.onNodeWithText("Productive week · about 3 hard days left before you overreach.", useUnmergedTree = true).assertExists()
    }

    @Test fun withoutABudgetThereIsNoTrackAndTheStateIsShown() {
        show(ui(state = FormState.CALIBRATING, budget = false))
        rule.onNodeWithText("WEEK LOAD", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Calibrating", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(WEEK_BAR_TRACK_TAG, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("of normal", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun noRecentTrainingSaysSo() {
        show(ui(state = FormState.NO_RECENT_TRAINING, budget = false))
        rule.onNodeWithText("No recent training", useUnmergedTree = true).assertExists()
    }

    @Test fun beforeTheFirstScoreItIsADash() {
        show(null)
        rule.onNodeWithText("WEEK LOAD", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("—", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(WEEK_BAR_TRACK_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun talkBackIsOneNode() {
        assertEquals(
            "Week load 67% of normal. Productive range up to 130%. About 3 hard days left.",
            weekBarDescription(ui(hard = 3)),
        )
        assertEquals("Week load 67% of normal. Productive range up to 130%. About 1 hard day left.", weekBarDescription(ui(hard = 1)))
        assertEquals("Week load 67% of normal. Productive range up to 130%. Easy days from here.", weekBarDescription(ui(hard = 0)))
        assertEquals("Week load 141% of normal. Productive range up to 130%. Past it.", weekBarDescription(ui(pct = 141, over = true, hard = 0)))
        assertEquals("Week load 100% of normal. Productive range up to 130%.", weekBarDescription(ui(pct = 100, hard = 2, daysLeft = 0)))
        assertEquals("Week load: Calibrating.", weekBarDescription(ui(state = FormState.CALIBRATING, budget = false)))
        assertEquals("Week load unavailable.", weekBarDescription(null))
        show(ui(hard = 3))
        val node = rule.onNodeWithText("67% of normal", useUnmergedTree = true).fetchSemanticsNode()
        var n = node
        while (n.parent != null && n.config.getOrNull(SemanticsProperties.ContentDescription) == null) n = n.parent!!
        assertEquals(listOf(weekBarDescription(ui(hard = 3))), n.config[SemanticsProperties.ContentDescription])
    }
}
