package dev.baseline.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.baseline.ai.engine.EngineKind
import dev.baseline.ai.engine.EngineLabel
import dev.baseline.ai.insight.InsightState
import dev.baseline.scores.Contributor
import dev.baseline.scores.FormState
import dev.baseline.scores.RecoveryResult
import dev.baseline.scores.RecoveryZone
import dev.baseline.scores.TrainingLoadDay
import dev.baseline.scores.WeekBudget
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

/** The AI insight inside Today's Recovery card (ai-assistant spec §3.8): it replaces the rule-based sentence once Ready. */
@RunWith(RobolectricTestRunner::class)
class RecoveryInsightTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val date = LocalDate.of(2026, 10, 24)
    private val ruleSentence = "HRV is 6% above your 30-day baseline and resting HR is 2 bpm below."
    private val aiText = "Your recovery is in the green at 72%, so a moderate day of training fits well."
    private val nano = EngineLabel(EngineKind.NANO, null)
    private val cloud = EngineLabel(EngineKind.CLOUD, "deepseek/deepseek-v4.1-flash")
    private val green = RecoveryResult(72, false, RecoveryZone.GREEN, listOf(Contributor("hrv", 1.0, 6.2), Contributor("rhr", 1.0, -2.0)))
    private val load = TrainingLoadUi(
        TrainingLoadDay(date, 64.0, 76.0, -18, FormState.PRODUCTIVE, null),
        WeekBudget(date.minusDays(2), 62, false, 2, 4),
    )
    private val state = TodayUiState(
        date, true, 86, green, null, 11.4, 14.0..18.0, emptyList(), emptyList(),
        BodyBatteryUi(80, emptyList(), 62, null, false), Instant.EPOCH, date, trainingLoad = load,
    )

    private var asks = 0
    private var retries = 0
    private var opened = 0

    private fun show(insight: TodayInsightUi?, today: TodayUiState = state) {
        rule.setContent {
            BaselineTheme {
                Surface { TodayContent(today, null, {}, {}, {}, {}, {}, {}, onOpenTrends = { opened++ }, insight = insight) }
            }
        }
    }

    private fun ui(s: InsightState, subject: String = "today") = TodayInsightUi(s, subject, { asks++ }, { retries++ })

    @Test
    fun readyReplacesTheRuleSentenceAndAddsATailRow() {
        show(ui(InsightState.Ready(aiText, nano)))
        rule.onNodeWithText(aiText, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(ruleSentence, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("On-device", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Ask about today ›", useUnmergedTree = true).assertExists().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        // The callout-free footer lines stay exactly as they were.
        rule.onNodeWithText("Strain target today: 14 – 18", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("This week: 62% of normal · ~2 hard days left", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Productive week", useUnmergedTree = true).assertExists()
    }

    @Test
    fun cloudLabelAndAPastDaySubject() {
        show(ui(InsightState.Ready(aiText, cloud), subject = "Tue 20 Oct"))
        rule.onNodeWithText("Cloud · deepseek-v4.1-flash", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Ask about Tue 20 Oct ›", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theAskLinkCallsOnAskAndNotTheCardClick() {
        show(ui(InsightState.Ready(aiText, nano)))
        rule.onNodeWithText("Ask about today ›", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(1, asks)
        assertEquals("the link is not the card's click", 0, opened)
    }

    @Test
    fun theRestOfTheCardStillOpensTrends() {
        show(ui(InsightState.Ready(aiText, nano)))
        rule.onNodeWithText("Productive week", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(1, opened)
        assertEquals(0, asks)
    }

    @Test
    fun readyWithoutATrainingLoadStillHasTheLinkButNoCardClick() {
        show(ui(InsightState.Ready(aiText, nano)), state.copy(trainingLoad = null))
        rule.onNodeWithText(aiText, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Ask about today ›", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(1, asks)
        rule.onNodeWithText(aiText, useUnmergedTree = true).performClick()
        assertEquals("no training load, so no card click", 0, opened)
    }

    @Test
    fun talkBackReadsTheInsightAndSaysWhoMadeIt() {
        show(ui(InsightState.Ready(aiText, nano)))
        val config = rule.onNodeWithTag("recovery-card").fetchSemanticsNode().config
        assertTrue(config[SemanticsProperties.Text].joinToString { it.text }.contains(aiText))
        assertTrue(config[SemanticsProperties.Text].none { it.text == ruleSentence })
        assertEquals(listOf("Made on-device."), config[SemanticsProperties.ContentDescription])
        val action = config[SemanticsActions.CustomActions].single()
        assertEquals("Ask about today", action.label)
        action.action()
        assertEquals(1, asks)
    }

    @Test
    fun talkBackSaysCloud() {
        show(ui(InsightState.Ready(aiText, cloud)))
        val config = rule.onNodeWithTag("recovery-card").fetchSemanticsNode().config
        assertEquals(listOf("Made in the cloud."), config[SemanticsProperties.ContentDescription])
    }

    private fun assertCardUnchanged(insight: TodayInsightUi?) {
        show(insight)
        rule.onNodeWithText(ruleSentence, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("recovery-tail", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Your recov", useUnmergedTree = true).assertDoesNotExist()
        assertNull(rule.onNodeWithTag("recovery-card").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions))
        assertTrue(rule.onNodeWithTag("recovery-card").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription) == null)
    }

    @Test
    fun generatingShowsNothingInTheCard() = assertCardUnchanged(ui(InsightState.Generating("Your recov")))

    @Test
    fun hiddenLeavesTheCardAsItWas() = assertCardUnchanged(ui(InsightState.Hidden))

    @Test
    fun noInsightLeavesTheCardAsItWas() = assertCardUnchanged(null)

    @Test
    fun failedKeepsTheRuleSentenceAndOffersRetry() {
        show(ui(InsightState.Failed("Couldn't reach the cloud. Check your connection.")))
        rule.onNodeWithText(ruleSentence, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Couldn't reach the cloud. Check your connection.", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Ask about today ›", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Retry", useUnmergedTree = true).assertHasClickAction().assertHeightIsAtLeast(48.dp).performScrollTo().performClick()
        assertEquals(1, retries)
        assertEquals("Retry is not the card's click", 0, opened)
        assertEquals(0, asks)
    }

    @Test
    fun theTailRowSitsAtTheBottomOfTheCard() {
        show(ui(InsightState.Ready(aiText, nano)))
        val card = rule.onNodeWithTag("recovery-card").getUnclippedBoundsInRoot()
        val tail = rule.onNodeWithTag("recovery-tail", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val week = rule.onNodeWithText("This week: 62% of normal · ~2 hard days left", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("tail ${tail.top} below the footer lines ${week.bottom}", tail.top >= week.bottom)
        assertTrue("tail inside the card", tail.bottom <= card.bottom)
    }
}
