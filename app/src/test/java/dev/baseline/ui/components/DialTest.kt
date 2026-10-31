package dev.baseline.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

// Compose's non-linear font scaling keeps these numbers inside the ring up to about 2.0; past that (OEM or adb settings) it needs the shrink.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the default mode measures text as one pixel per character, so nothing could overflow
class DialTest {
    private companion object {
        const val HUGE_FONT_SCALE = 3f
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** The number laid out at its full [valueSize] with room to spare, for comparing with what the dial shows. */
    private var unshrunk: TextLayoutResult? = null

    private fun showDial(fontScale: Float, value: String, suffix: String, size: Int, stroke: Int, valueSize: Int) {
        RuntimeEnvironment.setFontScale(fontScale)
        rule.setContent {
            BaselineTheme {
                val measurer = rememberTextMeasurer()
                unshrunk = measurer.measure(
                    buildAnnotatedString {
                        append(value)
                        withStyle(SpanStyle(fontSize = (valueSize / 2).sp)) { append(suffix) }
                    },
                    LocalTextStyle.current.merge(TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = valueSize.sp)),
                    maxLines = 1,
                )
                Dial(0.72f, value, suffix, Color.Green, size.dp, stroke.dp, valueSize, "Dial")
            }
        }
    }

    private fun valueLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!(results)
        return results.single()
    }

    @Test
    fun valueKeepsItsSizeAtTheDefaultFontScale() {
        showDial(1f, "100", "%", 132, 7, 44)
        val layout = valueLayout("100%")
        assertFalse(layout.hasVisualOverflow)
        assertEquals(unshrunk!!.size.width, layout.size.width) // identical to a plain 44 sp number
        assertEquals(unshrunk!!.size.height, layout.size.height)
    }

    private fun assertFitsAtHugeFontScale(value: String, suffix: String, size: Int, stroke: Int, valueSize: Int) {
        showDial(HUGE_FONT_SCALE, value, suffix, size, stroke, valueSize)
        val layout = valueLayout(value + suffix)
        assertFalse("$value$suffix must not overflow the $size dp ring", layout.hasVisualOverflow)
        val inner = (size - 2 * stroke) * rule.density.density
        assertTrue("${layout.size.width} px of $value$suffix fits in $inner", layout.size.width <= inner)
    }

    @Test
    fun threeDigitSleepStaysInsideTheRingAtAHugeFontScale() = assertFitsAtHugeFontScale("100", "%", 96, 8, 30)

    @Test
    fun threeDigitRecoveryStaysInsideTheRingAtAHugeFontScale() = assertFitsAtHugeFontScale("100", "%", 132, 7, 44)

    @Test
    fun strainStaysInsideTheRingAtAHugeFontScale() = assertFitsAtHugeFontScale("18.4", "", 96, 8, 30)

    @Test
    fun shrunkDialOnANarrowScreenStaysInsideTheRingAtAHugeFontScale() = assertFitsAtHugeFontScale("100", "%", 82, 7, 26)
}
