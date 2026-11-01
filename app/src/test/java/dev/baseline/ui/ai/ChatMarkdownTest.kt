package dev.baseline.ui.ai

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownTest {
    private val bold = SpanStyle(fontWeight = FontWeight.SemiBold)

    private fun boldParts(text: String): List<String> = markdownLite(text, bold).let { s -> s.spanStyles.map { s.text.substring(it.start, it.end) } }

    @Test
    fun boldMarkersBecomeSpans() {
        val s = markdownLite("**HRV:** 97 ms", bold)
        assertEquals("HRV: 97 ms", s.text)
        assertEquals(listOf("HRV:"), boldParts("**HRV:** 97 ms"))
    }

    @Test
    fun bulletsGetADotAndNoAsterisk() {
        val s = markdownLite("Here:\n*   **Sleep:** 8h\n- plain", bold)
        // Each line is its own paragraph, so the line breaks come from the paragraph styles and the text has none.
        assertEquals("Here:•  Sleep: 8h•  plain", s.text)
        assertEquals(3, s.paragraphStyles.size)
    }

    @Test
    fun anOpenBoldIsDroppedAndBoldsTheRestOfTheLine() {
        val s = markdownLite("*  **Main drivers", bold)
        assertEquals("•  Main drivers", s.text)
        assertEquals(listOf("Main drivers"), boldParts("*  **Main drivers"))
    }

    @Test
    fun headingsLoseTheHashAndAreBold() {
        assertEquals("Summary", markdownLite("## Summary", bold).text)
        assertEquals(listOf("Summary"), boldParts("## Summary"))
    }

    @Test
    fun plainTextAndNumbersPassThrough() {
        assertEquals("1,875 vs 6,436 (-71%)", markdownLite("1,875 vs 6,436 (-71%)", bold).text)
    }
}
