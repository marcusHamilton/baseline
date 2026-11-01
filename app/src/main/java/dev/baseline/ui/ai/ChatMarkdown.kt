package dev.baseline.ui.ai

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

private val Bullet = ParagraphStyle(textIndent = TextIndent(0.sp, 16.sp))
private val Gap = SpanStyle(fontSize = 8.sp)

/**
 * The few markdown forms models write in chat: `**bold**`, `*` or `-` bullets and `#` headings, which show as bold lines. Anything else
 * stays literal. A `**` left open (a streaming or cut-off answer) bolds the rest of its line and its marker is dropped, so a half-written
 * answer never shows stray asterisks. Every line is its own paragraph so bullets wrap with a hanging indent.
 */
fun markdownLite(text: String, bold: SpanStyle): AnnotatedString = buildAnnotatedString {
    text.trimEnd().lines().forEach { raw ->
        val line = raw.trimEnd()
        val bullet = BULLET.matchEntire(line)?.groupValues?.get(1)
        val heading = HEADING.matchEntire(line)?.groupValues?.get(1)
        when {
            line.isBlank() -> withStyle(ParagraphStyle()) { withStyle(Gap) { append(" ") } }

            bullet != null -> withStyle(Bullet) {
                append("•  ")
                appendInline(bullet, bold, false)
            }

            else -> withStyle(ParagraphStyle()) { appendInline(heading ?: line, bold, heading != null) }
        }
    }
}

private val BULLET = Regex("""\s*[*-]\s+(.*)""")
private val HEADING = Regex("""\s*#{1,6}\s+(.*)""")

private fun AnnotatedString.Builder.appendInline(line: String, bold: SpanStyle, allBold: Boolean) {
    var on = allBold
    line.split("**").forEachIndexed { i, part ->
        if (i > 0) on = !on
        if (part.isEmpty()) return@forEachIndexed
        if (on) withStyle(bold) { append(part) } else append(part)
    }
}
