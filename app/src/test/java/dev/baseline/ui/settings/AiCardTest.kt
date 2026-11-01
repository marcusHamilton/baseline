package dev.baseline.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiMode
import dev.baseline.ai.engine.NanoStatus
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class AiCardTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ready = NanoStatus.Ready("nano-v4", 4096)
    private val keySet = AiKeyStatus.Set("openrouter.ai", "deepseek/deepseek-v4.1-flash", toolsSupported = true, invalid = false)

    private fun state(mode: AiMode? = AiMode.ON_DEVICE, nano: NanoStatus = ready, key: AiKeyStatus = AiKeyStatus.Missing, known: Boolean = true) =
        buildAiSettingsState(mode, nano, known, key)

    private class Calls {
        val modes = mutableListOf<AiMode>()
        var download = 0
        var addKey = 0
        var changeModel = 0
        var replaceKey = 0
        var remove = 0
        var clear = 0
    }

    private fun show(state: AiSettingsState, calls: Calls = Calls()) = rule.setContent {
        BaselineTheme {
            Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                AiCard(
                    state,
                    onMode = { calls.modes += it },
                    onDownload = { calls.download++ },
                    onAddKey = { calls.addKey++ },
                    onChangeModel = { calls.changeModel++ },
                    onReplaceKey = { calls.replaceKey++ },
                    onRemove = { calls.remove++ },
                    onClearInsights = { calls.clear++ },
                )
            }
        }
    }

    @Test
    fun modesGreyedWithReasons() {
        show(state(mode = AiMode.OFF, nano = NanoStatus.Unavailable, key = AiKeyStatus.Missing))
        val onDevice = rule.onNode(hasTestTag("ai-mode-ON_DEVICE"))
        onDevice.assertIsNotEnabled()
        rule.onNode(hasTestTag("ai-mode-ON_DEVICE") and hasText("Not supported on this phone")).assertExists()
        rule.onNode(hasTestTag("ai-mode-CLOUD")).assertIsNotEnabled()
        rule.onNode(hasTestTag("ai-mode-CLOUD") and hasText("Add an API key")).assertExists()
        rule.onNode(hasTestTag("ai-mode-HYBRID")).assertIsNotEnabled()
        rule.onNode(hasTestTag("ai-mode-HYBRID") and hasText("Not supported on this phone · Add an API key")).assertExists()
        rule.onNode(hasTestTag("ai-mode-OFF")).assertIsSelected()
    }

    @Test
    fun theFourModesAreRadioRowsInOrderAndOnlyTheCurrentOneIsSelected() {
        show(state(mode = AiMode.HYBRID, key = keySet))
        listOf("Off", "On-device only", "Cloud only", "Hybrid").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNode(hasTestTag("ai-mode-HYBRID")).assertIsSelected()
        rule.onNode(hasTestTag("ai-mode-OFF")).assertIsNotSelected()
        rule.onNode(hasTestTag("ai-mode-CLOUD")).assertIsNotSelected()
    }

    @Test
    fun anUndecidedModeSelectsNothing() {
        show(state(mode = null))
        AiMode.entries.forEach { rule.onNode(hasTestTag("ai-mode-$it")).assertIsNotSelected() }
    }

    @Test
    fun tappingAnEnabledModeReportsItAndADisabledOneDoesNot() {
        val calls = Calls()
        show(state(mode = AiMode.ON_DEVICE, key = AiKeyStatus.Missing), calls)
        rule.onNode(hasTestTag("ai-mode-OFF")).performClick()
        rule.onNode(hasTestTag("ai-mode-CLOUD")).performClick()
        assertEquals(listOf(AiMode.OFF), calls.modes)
    }

    @Test
    fun nanoReadyRow() {
        show(state())
        rule.onNodeWithText("Gemini Nano (nano-v4) · Ready · 4,096-token window").assertIsDisplayed()
    }

    @Test
    fun nanoDownloadableShowsDownloadAndItsHint() {
        val calls = Calls()
        show(state(nano = NanoStatus.Downloadable), calls)
        rule.onNodeWithText("Uses Wi-Fi; may take a few minutes").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Download").performClick()
        assertEquals(1, calls.download)
    }

    @Test
    fun nanoDownloadingShowsAProgressBarDeterminateOrNot() {
        show(state(nano = NanoStatus.Downloading(0.25f)))
        rule.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(0.25f, 0f..1f))).assertExists()
    }

    @Test
    fun nanoDownloadingWithUnknownProgressIsIndeterminate() {
        show(state(nano = NanoStatus.Downloading(null)))
        rule.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertExists()
    }

    @Test
    fun nanoUnavailableRow() {
        show(state(mode = AiMode.OFF, nano = NanoStatus.Unavailable))
        // The mode row's reason and the on-device row both say it.
        assertEquals(2, rule.onAllNodesWithText("Not supported on this phone", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun nanoRestingSaysTheDailyLimitIsUsed() {
        show(state(nano = NanoStatus.Resting(Instant.parse("2026-11-02T00:00:00Z"))))
        rule.onNodeWithText("On-device AI has reached today's limit.").assertIsDisplayed()
    }

    @Test
    fun nothingAboutNanoShowsBeforeItsStatusIsKnown() {
        show(state(mode = null, nano = NanoStatus.Unavailable, known = false))
        assertEquals(1, rule.onAllNodesWithText("Not supported on this phone", useUnmergedTree = true).fetchSemanticsNodes().size) // only the mode row
    }

    @Test
    fun addKeyWhenNoneIsSet() {
        val calls = Calls()
        show(state(), calls)
        rule.onNodeWithText("Add API key").performScrollTo().performClick()
        assertEquals(1, calls.addKey)
        rule.onAllNodesWithText("Remove").assertCountEquals(0)
    }

    @Test
    fun keyRowsShowHostModelAndTools() {
        val calls = Calls()
        show(state(key = keySet, mode = AiMode.HYBRID), calls)
        rule.onNodeWithText("openrouter.ai").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("deepseek-v4.1-flash").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("✓ Tools").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Change model").performScrollTo().performClick()
        rule.onNodeWithText("Replace key").performScrollTo().performClick()
        assertEquals(1, calls.changeModel)
        assertEquals(1, calls.replaceKey)
    }

    @Test
    fun aModelWithoutToolsIsFlagged() {
        show(state(key = keySet.copy(toolsSupported = false)))
        rule.onNodeWithText("⚠ No tools: answers use page data only").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun removeAsksForAConfirmingSecondTap() {
        val calls = Calls()
        show(state(key = keySet), calls)
        rule.onNodeWithText("Remove").performScrollTo().performClick()
        assertEquals(0, calls.remove)
        rule.onNodeWithText("Remove key?").performScrollTo().performClick()
        assertEquals(1, calls.remove)
    }

    @Test
    fun hintOnlyOnDeviceReady() {
        val hint = "Add an API key for better answers to training and long-term questions."
        show(state(mode = AiMode.ON_DEVICE, nano = ready))
        rule.onNodeWithText(hint).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun noHintInOtherModesOrWhenNanoIsNotReady() {
        val hint = "Add an API key for better answers to training and long-term questions."
        show(state(mode = AiMode.OFF, nano = ready))
        assertEquals(0, rule.onAllNodesWithText(hint).fetchSemanticsNodes().size)
        assertEquals(false, state(mode = AiMode.ON_DEVICE, nano = NanoStatus.Downloadable).showHint)
        assertEquals(false, state(mode = AiMode.ON_DEVICE, nano = NanoStatus.Resting(Instant.EPOCH)).showHint)
        assertEquals(true, state(mode = AiMode.ON_DEVICE, nano = ready).showHint)
    }

    @Test
    fun privacyLineWithHost() {
        show(state(key = keySet))
        rule.onNodeWithText(
            "Cloud requests send this page's numbers, and any history the model looks up, to openrouter.ai. Never GPS, routes or names. Not medical advice.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun noPrivacyLineWithoutAKey() {
        show(state())
        assertEquals(0, rule.onAllNodesWithText("Cloud requests", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun invalidKeyMessage() {
        show(state(key = keySet.copy(invalid = true)))
        rule.onNodeWithText("Key rejected. Replace it to keep using the cloud.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun clearInsightsCallsBackAndSaysCleared() {
        val calls = Calls()
        show(state(), calls)
        rule.onNodeWithText("Clear AI insights").performScrollTo()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Clear AI insights").performClick()
        assertEquals(1, calls.clear)
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Cleared").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(5_000)
        rule.onNodeWithText("Clear AI insights").assertIsDisplayed()
    }

    @Test
    fun everyClickableIsAtLeast48dpTall() {
        show(state(mode = AiMode.HYBRID, nano = NanoStatus.Downloadable, key = keySet.copy(invalid = true)))
        val clickable = rule.onAllNodes(hasClickAction())
        val count = clickable.fetchSemanticsNodes().size
        assert(count >= 8) { "expected the mode rows and buttons, found $count" }
        for (i in 0 until count) clickable[i].performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun everyClickableIsAtLeast48dpTallWithoutAKey() {
        show(state(mode = AiMode.OFF, nano = NanoStatus.Unavailable))
        val clickable = rule.onAllNodes(hasClickAction())
        for (i in 0 until clickable.fetchSemanticsNodes().size) clickable[i].performScrollTo().assertHeightIsAtLeast(48.dp)
    }
}
