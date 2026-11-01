package dev.baseline.ui.ai

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.baseline.ai.ChatMessage
import dev.baseline.ai.QuestionTag
import dev.baseline.ai.context.Suggestion
import dev.baseline.ai.engine.EngineKind
import dev.baseline.ai.engine.EngineLabel
import dev.baseline.ai.engine.Role
import dev.baseline.ui.theme.BaselineTheme
import dev.baseline.ui.theme.Bg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the default mode fakes text measurement, which breaks the width and position checks
@Config(qualifiers = "w400dp-h900dp-xxhdpi")
class AskSheetTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class Calls {
        val sent = mutableListOf<String>()
        val picked = mutableListOf<Suggestion>()
        val moreDetail = mutableListOf<Int>()
        var retry = 0
        var close = 0
    }

    private val suggestions = listOf(
        Suggestion("Why is my recovery 71%?", QuestionTag.PAGE),
        Suggestion("What does today's strain target mean for me?", QuestionTag.PAGE),
        Suggestion("Can I train hard today?", QuestionTag.TRAINING),
        Suggestion("What's been affecting my HRV lately?", QuestionTag.PATTERN),
    )

    private val nano = EngineLabel(EngineKind.NANO, null)
    private val cloud = EngineLabel(EngineKind.CLOUD, "deepseek/deepseek-v4.1-flash")

    private fun state(messages: List<ChatMessage> = emptyList(), busy: Boolean = false, suggestions: List<Suggestion> = this.suggestions) = AskUiState(
        title = "Ask about last night",
        hint = "Ask anything about last night, or how it fits your training and longer trends.",
        placeholder = if (messages.isEmpty()) "Ask about last night…" else "Ask a follow-up…",
        suggestions = suggestions,
        messages = messages,
        busy = busy,
    )

    private fun show(state: AskUiState, calls: Calls = Calls()) = rule.setContent {
        BaselineTheme {
            Surface(Modifier.fillMaxSize(), color = Bg) {
                AskContent(
                    state,
                    onSend = { calls.sent += it },
                    onPick = { calls.picked += it },
                    onMoreDetail = { calls.moreDetail += it },
                    onRetry = { calls.retry++ },
                    onClose = { calls.close++ },
                )
            }
        }
    }

    private fun answered(text: String, label: EngineLabel, more: Boolean = false) = ChatMessage(Role.ASSISTANT, text, label = label, canMoreDetail = more)

    @Test
    fun emptyStateShowsTitleAndHint() {
        show(state())
        rule.onNodeWithText("Ask about last night").assertExists()
        rule.onNodeWithText("Ask anything about last night, or how it fits your training and longer trends.").assertExists()
        rule.onNodeWithText("Ask about last night…").assertExists()
    }

    @Test
    fun suggestionsStackedMaxFourAboveInput() {
        show(state())
        val chips = rule.onAllNodesWithTag("ask-suggestion")
        chips.assertCountEquals(4)
        val bounds = (0 until 4).map { chips[it].getBoundsInRoot() }
        bounds.forEach { assertEquals(bounds[0].left, it.left) }
        for (i in 1 until 4) assertTrue("chip $i sits below chip ${i - 1}", bounds[i].top >= bounds[i - 1].bottom)
        val field = rule.onNode(hasSetTextAction()).getBoundsInRoot()
        assertTrue("chips sit above the text field", bounds.last().bottom <= field.top)
        assertTrue("chips are not stretched across the sheet", (bounds[2].right - bounds[2].left) < (field.right - field.left))
    }

    @Test
    fun tappingASuggestionPicksIt() {
        val calls = Calls()
        show(state(), calls)
        rule.onNodeWithText("Can I train hard today?").performClick()
        assertEquals(listOf(suggestions[2]), calls.picked)
    }

    @Test
    fun suggestionsHideAfterFirstSend() {
        show(state(messages = listOf(ChatMessage(Role.USER, "How did I sleep?"), answered("Fine.", nano))))
        rule.onAllNodesWithTag("ask-suggestion").assertCountEquals(0)
        rule.onNodeWithText("How did I sleep?").assertExists()
    }

    @Test
    fun trailingLabelAndMoreDetail() {
        val calls = Calls()
        show(
            state(
                messages = listOf(
                    ChatMessage(Role.USER, "Q1"),
                    answered("Nano answer.", nano, more = true),
                    ChatMessage(Role.USER, "Q2"),
                    answered("Cloud answer.", cloud),
                ),
            ),
            calls,
        )
        rule.onNodeWithText("On-device").assertExists()
        rule.onNodeWithText("Cloud · deepseek-v4.1-flash").assertExists()
        rule.onAllNodesWithTag("ask-more-detail").assertCountEquals(1)
        rule.onNodeWithText("· More detail ☁").performClick()
        assertEquals(listOf(1), calls.moreDetail)
    }

    @Test
    fun noMoreDetailWhenNotOffered() {
        show(state(messages = listOf(ChatMessage(Role.USER, "Q"), answered("A.", nano, more = false))))
        rule.onNodeWithText("· More detail ☁").assertDoesNotExist()
    }

    @Test
    fun toolStatusThenLookedUp() {
        val running = ChatMessage(Role.ASSISTANT, "", status = "Looking up HRV, 1 Sep – 31 Oct", streaming = true)
        show(state(messages = listOf(ChatMessage(Role.USER, "Q"), running)))
        rule.onNodeWithText("Looking up HRV, 1 Sep – 31 Oct").assertExists()
        rule.onNodeWithText("Looked up", substring = true).assertDoesNotExist()
    }

    @Test
    fun lookedUpLineStaysAboveTheAnswer() {
        val done = ChatMessage(
            Role.ASSISTANT,
            "Your HRV is up.",
            label = cloud,
            lookedUp = listOf("Looking up HRV, 1 Sep – 31 Oct", "Looking up sleep, night of 30 Oct"),
        )
        show(state(messages = listOf(ChatMessage(Role.USER, "Q"), done)))
        rule.onNodeWithText("Looked up: HRV, 1 Sep – 31 Oct; sleep, night of 30 Oct").assertExists()
        rule.onNodeWithText("Looking up", substring = true).assertDoesNotExist()
        val line = rule.onNodeWithText("Looked up", substring = true).getBoundsInRoot()
        val answer = rule.onNodeWithText("Your HRV is up.").getBoundsInRoot()
        assertTrue(line.bottom <= answer.top)
    }

    @Test
    fun errorShowsTryAgain() {
        val calls = Calls()
        val failed = ChatMessage(Role.ASSISTANT, "", error = "Couldn't reach openrouter.ai.")
        show(state(messages = listOf(ChatMessage(Role.USER, "Q"), failed)), calls)
        rule.onNodeWithText("Couldn't reach openrouter.ai.").assertExists()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, calls.retry)
        rule.onAllNodesWithContentDescription("Copy answer").assertCountEquals(0)
    }

    @Test
    fun noteShowsAsMutedTextWithoutTrailingRow() {
        val note = ChatMessage(Role.ASSISTANT, "That needs the cloud. Add a key in Settings.", note = true)
        show(state(messages = listOf(ChatMessage(Role.USER, "Q"), note)))
        rule.onNodeWithText("That needs the cloud. Add a key in Settings.").assertExists()
        rule.onAllNodesWithContentDescription("Copy answer").assertCountEquals(0)
    }

    @Test
    fun placeholderChangesAfterFirstMessage() {
        show(state(messages = listOf(ChatMessage(Role.USER, "Q"), answered("A.", nano))))
        rule.onNodeWithText("Ask a follow-up…").assertExists()
        rule.onNodeWithText("Ask about last night…").assertDoesNotExist()
    }

    @Test
    fun sendingTrimsClearsTheFieldAndIgnoresBlank() {
        val calls = Calls()
        show(state(), calls)
        rule.onNode(hasSetTextAction()).performTextInput("   ")
        rule.onNodeWithContentDescription("Send").performClick()
        assertTrue(calls.sent.isEmpty())
        rule.onNode(hasSetTextAction()).performTextInput(" Why? ")
        rule.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("Why?"), calls.sent)
        rule.onNode(hasSetTextAction() and hasText("Why?")).assertDoesNotExist()
    }

    @Test
    fun inputIsDisabledWhileBusy() {
        show(state(messages = listOf(ChatMessage(Role.USER, "Q"), ChatMessage(Role.ASSISTANT, "", streaming = true)), busy = true))
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        rule.onNode(hasTestTag("ask-input")).assert(hasSetTextAction().not())
    }

    @Test
    fun suggestionsAreDisabledWhileBusy() {
        show(state(busy = true))
        rule.onAllNodesWithTag("ask-suggestion")[0].assertIsNotEnabled()
    }

    @Test
    fun sendIsEnabledWhenIdle() {
        show(state())
        rule.onNode(hasTestTag("ask-input") and hasSetTextAction()).assertExists()
        rule.onNode(hasSetTextAction()).performTextInput("Hi")
        rule.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test
    fun closeCallsOnClose() {
        val calls = Calls()
        show(state(), calls)
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, calls.close)
    }

    @Test
    fun copyPutsAnswerOnClipboard() {
        show(state(messages = listOf(ChatMessage(Role.USER, "Q"), answered("Your sleep was steady.", nano))))
        rule.onNodeWithContentDescription("Copy answer").performClick()
        rule.waitForIdle()
        val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Your sleep was steady.", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun touchTargets48dp() {
        val failed = ChatMessage(Role.ASSISTANT, "", error = "Something went wrong reading the answer.")
        show(
            state(
                messages = listOf(
                    ChatMessage(Role.USER, "Q1"),
                    answered("Nano answer.", nano, more = true),
                    ChatMessage(Role.USER, "Q2"),
                    failed,
                ),
            ),
        )
        val clickable = rule.onAllNodes(hasClickAction())
        val count = clickable.fetchSemanticsNodes().size
        assertTrue("expected clickables, found $count", count >= 4)
        for (i in 0 until count) {
            clickable[i].assertHeightIsAtLeast(48.dp)
            clickable[i].assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun suggestionChipsAre48dpTall() {
        show(state())
        val chips = rule.onAllNodesWithTag("ask-suggestion")
        for (i in 0 until 4) chips[i].assertHeightIsAtLeast(48.dp)
    }
}
