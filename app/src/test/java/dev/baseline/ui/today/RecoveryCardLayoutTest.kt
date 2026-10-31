package dev.baseline.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.baseline.scores.FormState
import dev.baseline.scores.TrainingLoadDay
import dev.baseline.scores.WeekBudget
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the default mode measures text as one pixel per character, so nothing could wrap
class RecoveryCardLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val date = LocalDate.of(2026, 10, 28)

    @Test fun weekLineWrapsAtLargeFont() {
        val load = TrainingLoadUi(TrainingLoadDay(date, 64.0, 76.0, -18, FormState.PRODUCTIVE, null), WeekBudget(date.minusDays(2), 62, false, 2, 4))
        val state = TodayUiState(
            date, true, 86, null, "calibrating — need 7 nights", 11.4, 14.0..18.0, emptyList(), emptyList(),
            BodyBatteryUi(80, emptyList(), 62, null, false), Instant.EPOCH, date, trainingLoad = load,
        )
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                BaselineTheme { Box(Modifier.width(320.dp)) { TodayContent(state, null, {}, {}, {}, {}, {}, {}) } }
            }
        }
        val text = "This week: 62% of normal · ~2 hard days left"
        val node = rule.onNodeWithText(text, useUnmergedTree = true).performScrollTo().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        assertTrue("wraps", layouts.single().lineCount >= 2)
        assertFalse("not cut off", layouts.single().hasVisualOverflow)
    }
}
