package dev.baseline.ui.ai

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.baseline.ai.engine.EngineKind
import dev.baseline.ai.engine.EngineLabel
import dev.baseline.ai.insight.InsightState
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.ActivityStreamEntity
import dev.baseline.data.db.LoadSource
import dev.baseline.fit.FitReader
import dev.baseline.fit.fitFixture
import dev.baseline.ui.activities.act
import dev.baseline.ui.activity.ActivityContent
import dev.baseline.ui.activity.buildActivityState
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.sleep.SleepContent
import dev.baseline.ui.sleep.SleepTiles
import dev.baseline.ui.sleep.SleepUiState
import dev.baseline.ui.sleep.StageUi
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.LocalDate
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the default mode fakes text measurement, which breaks the wrapping and height checks
@Config(qualifiers = "w400dp-h900dp-xxhdpi")
class InsightCardTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val nano = EngineLabel(EngineKind.NANO, null)
    private val cloud = EngineLabel(EngineKind.CLOUD, "deepseek/deepseek-v4.1-flash")

    // About three lines at 12.5sp in a 340dp card; the test below asserts it.
    private val threeLines = "Your recovery is in the green at 71%, helped by an HRV above your usual range. " +
        "Last night's 7h 42m of sleep fits a moderate day."

    private var asks = 0
    private var retries = 0

    private val current = mutableStateOf<InsightState>(InsightState.Hidden)

    private fun show(state: InsightState, subject: String = "today", scale: Float? = null) {
        current.value = state
        if (scale != null) {
            val resolver = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        }
        rule.setContent {
            BaselineTheme {
                Surface {
                    Box(Modifier.width(340.dp)) {
                        InsightCard(current.value, subject, onAsk = { asks++ }, onRetry = { retries++ }, modifier = Modifier.testTag("card"))
                    }
                }
            }
        }
    }

    private fun cardHeight() = rule.onNodeWithTag("card").getBoundsInRoot().let { it.bottom - it.top }

    @Test
    fun readyShowsTextLabelAndLink() {
        show(InsightState.Ready(threeLines, nano))
        rule.onNodeWithText("INSIGHT", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(threeLines, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("On-device", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Ask about today ›", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("insight-footer", useUnmergedTree = true).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun cloudLabelShowsTheModel() {
        show(InsightState.Ready(threeLines, cloud), subject = "last night")
        rule.onNodeWithText("Cloud · deepseek-v4.1-flash", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Ask about last night ›", useUnmergedTree = true).assertExists()
    }

    @Test
    fun generatingKeepsHeight() {
        show(InsightState.Ready(threeLines, nano))
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(threeLines, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        assertEquals("fixture should take three lines", 3, layouts.single().lineCount)
        val ready = cardHeight()

        current.value = InsightState.Generating("")
        rule.waitForIdle()
        val loading = cardHeight()
        assertTrue("generating $loading vs ready $ready", abs((loading - ready).value) <= 2f)
        // Text streaming in over the bars must not change the height while it is within three lines.
        current.value = InsightState.Generating("Your recovery is in the green")
        rule.waitForIdle()
        val streaming = cardHeight()
        assertTrue("streaming $streaming vs ready $ready", abs((streaming - ready).value) <= 2f)
    }

    @Test
    fun generatingReadsAsLoading() {
        show(InsightState.Generating(""))
        rule.onNodeWithContentDescription("Insight loading").assertExists()
        rule.onNodeWithTag("insight-shimmer", useUnmergedTree = true).assertExists()
    }

    @Test
    fun reducedMotionDrawsStaticBars() {
        show(InsightState.Generating(""), scale = 0f)
        rule.onNodeWithTag("insight-bars-static", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("insight-shimmer", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun failedShowsRetry() {
        show(InsightState.Failed("Couldn't reach the cloud. Check your connection."))
        rule.onNodeWithText("Couldn't reach the cloud. Check your connection.", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Insight: Couldn't reach the cloud. Check your connection.").assertExists()
        rule.onNodeWithText("Retry").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, retries)
        assertEquals("a failed card is not an Ask target", 0, asks)
    }

    @Test
    fun hiddenTakesNoSpace() {
        rule.setContent {
            BaselineTheme {
                Box(Modifier.testTag("slot")) { InsightCard(InsightState.Hidden, "today", {}, {}) }
            }
        }
        rule.onNodeWithTag("slot").assertExists().getBoundsInRoot().let { assertEquals(0f, (it.bottom - it.top).value, 0f) }
    }

    @Test
    fun talkBackMergedDescription() {
        show(InsightState.Ready(threeLines, nano))
        rule.onNodeWithContentDescription("Insight: $threeLines. Made on-device.").assertExists()
        val node = rule.onNodeWithTag("card").fetchSemanticsNode()
        assertEquals(listOf("Insight: $threeLines. Made on-device."), node.config[SemanticsProperties.ContentDescription])
        val action = node.config[SemanticsActions.CustomActions].single()
        assertEquals("Ask about today", action.label)
        action.action()
        assertEquals(1, asks)
    }

    @Test
    fun talkBackSaysCloud() {
        show(InsightState.Ready("Short one.", cloud), subject = "this run")
        rule.onNodeWithContentDescription("Insight: Short one.. Made in the cloud.").assertExists()
        assertEquals("Ask about this run", rule.onNodeWithTag("card").fetchSemanticsNode().config[SemanticsActions.CustomActions].single().label)
    }

    @Test
    fun askLinkAndCardClickCallOnAsk() {
        show(InsightState.Ready(threeLines, nano))
        rule.onNodeWithText("Ask about today ›", useUnmergedTree = true).performClick()
        assertEquals(1, asks)
        rule.onNodeWithText(threeLines, useUnmergedTree = true).performClick()
        assertEquals(2, asks)
    }

    // ---- the slot on each screen ----

    private val date = LocalDate.of(2026, 10, 28)
    private val slot: @Composable () -> Unit = { Box(Modifier.fillMaxWidth().height(40.dp).testTag("slot")) }

    private fun top(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot().top
    private fun bottom(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot().bottom

    @Test
    fun sleepPutsTheSlotUnderTheHeader() {
        val stages = listOf("Deep", "REM", "Light", "Awake").map { StageUi(it, null, null) }
        val state = SleepUiState(
            date, false, 86, Duration.ofMinutes(462), Duration.ofMinutes(538), null, null, null, emptyList(), emptyList(), false, stages, null,
            SleepTiles("—", "—", "—", "—"),
        )
        rule.setContent { BaselineTheme { Surface { SleepContent(state, true, insight = slot) {} } } }
        val hero = rule.onNodeWithText("asleep of 8h 58m needed", useUnmergedTree = true).getBoundsInRoot()
        val overnight = rule.onNodeWithText("OVERNIGHT", substring = true, useUnmergedTree = true).getBoundsInRoot()
        val slotTop = top("slot")
        assertTrue("slot ($slotTop) below the sleep-performance header (${hero.bottom})", slotTop >= hero.bottom)
        assertTrue("slot ($slotTop) above the overnight card (${overnight.top})", slotTop < overnight.top)
    }

    @Test
    fun activityPutsTheSlotUnderTheSummaryTiles() {
        val stream = ActivityStreamEntity(1, FitReader.read(fitFixture("run")).streams.pack())
        val state = buildActivityState(
            act(1).copy(avgHr = 152.0, maxHr = 171.0, garminLoad = 112.0, zone1Sec = 192.0, zone2Sec = 880.0, zone3Sec = 1325.0),
            ActivityScoreEntity(1, 112.0, LoadSource.HR, 9.8),
            stream,
            emptyList(),
            emptyList(),
            null,
            Formatters(false),
        )
        rule.setContent { BaselineTheme { Surface { ActivityContent(state, {}, {}, insight = slot) } } }
        val summaryBottom = bottom("summary")
        val slotTop = top("slot")
        assertTrue("slot ($slotTop) below the summary tiles ($summaryBottom)", slotTop >= summaryBottom)
        assertTrue("slot ($slotTop) above the next section", slotTop < top("section-hr"))
    }
}
