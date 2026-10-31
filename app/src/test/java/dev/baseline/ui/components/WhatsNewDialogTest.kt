package dev.baseline.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.baseline.ui.theme.BaselineTheme
import dev.baseline.update.parseNotes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WhatsNewDialogTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val notes = parseNotes("Updates in one tap.\n\n## New\n- Update from Today\n- Daily checks\n## Fixed\n- Sleep times follow the time zone")

    @Test
    fun showsTheVersionSummaryAndEverySection() {
        rule.setContent { BaselineTheme { WhatsNewDialog("0.06", notes, {}) } }
        rule.onNodeWithText("Baseline 0.06").assertIsDisplayed()
        rule.onNodeWithText("Updates in one tap.").assertIsDisplayed()
        rule.onNodeWithText("NEW").assertIsDisplayed()
        rule.onNodeWithText("Update from Today").assertIsDisplayed()
        rule.onNodeWithText("Daily checks").assertIsDisplayed()
        rule.onNodeWithText("FIXED").assertIsDisplayed()
        rule.onNodeWithText("Sleep times follow the time zone").assertIsDisplayed()
    }

    @Test
    fun titlesAreHeadingsAndGotItIsATallEnoughTarget() {
        var dismissed = 0
        rule.setContent { BaselineTheme { WhatsNewDialog("0.06", notes, { dismissed++ }) } }
        for (title in listOf("Baseline 0.06", "NEW", "FIXED")) {
            rule.onNodeWithText(title).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
        rule.onNodeWithText("Got it").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun aLongNoteScrollsInsteadOfPushingGotItOffScreen() {
        val long = parseNotes(
            "Sum.\n\n## New\n" + (1..5).joinToString("\n") { "- Item $it ".padEnd(80, 'x') } + "\n## Improved\n" +
                (1..5).joinToString("\n") { "- Better $it ".padEnd(80, 'y') },
        )
        rule.setContent { BaselineTheme { WhatsNewDialog("0.06", long, {}) } }
        rule.onNodeWithText("Got it").assertIsDisplayed()
    }
}
