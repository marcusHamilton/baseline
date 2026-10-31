package dev.baseline.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.baseline.scores.FormState
import dev.baseline.scores.TrainingLoadDay
import dev.baseline.scores.WeekBudget
import dev.baseline.ui.theme.BaselineTheme
import dev.baseline.ui.today.TrainingLoadUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the default mode measures text as one pixel per character, so nothing could wrap
class TrainingLoadCardTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val w = LocalDate.of(2026, 10, 26)
    private val ui = TrainingLoadUi(TrainingLoadDay(w.plusDays(2), 64.0, 76.0, -18, FormState.PRODUCTIVE, null), WeekBudget(w, 62, false, 2, 4), false)

    @Test fun budgetLineWrapsAtLargeFont() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                BaselineTheme { TrainingLoadCard(ui, {}, Modifier.width(320.dp)) }
            }
        }
        val text = "62% of a normal week · room for ~2 hard days"
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        assertTrue("wraps", layouts.single().lineCount >= 2)
        assertFalse("not cut off", layouts.single().hasVisualOverflow)
    }
}
