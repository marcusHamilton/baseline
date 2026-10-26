package dev.baseline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.BetterGreen
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.today.StatRowUi

/** "HRV (overnight) 98 ms, 9% above baseline 90 ms, better": what TalkBack reads for a row. */
fun statRowDescription(row: StatRowUi): String {
    val value = if (row.value == "—") "no value" else listOf(row.value, row.unit).filter { it.isNotEmpty() }.joinToString(" ")
    val move = row.delta?.let { d ->
        val text = d.removePrefix("▲ ").removePrefix("▼ ")
        when {
            d == "same" -> "same as baseline" + (row.baseline?.let { " $it" } ?: "")
            d == "so far" -> "so far today"
            else -> {
                val amount = if ('%' in text || row.unit.isEmpty()) text else "$text ${row.unit}"
                "$amount ${if (d.startsWith("▲")) "above" else "below"} baseline" + (row.baseline?.let { " $it" } ?: "")
            }
        }
    } ?: row.baseline?.let { "baseline $it" }
    return listOfNotNull("${row.label} $value", move, row.verdict).joinToString(", ")
}

/**
 * One "vs. 30-day baseline" row. The arrow gives the direction; the word under it ("better", "worse" or "normal") and its
 * colour give the verdict, so colour is never the only cue.
 */
@Composable
fun StatRow(row: StatRowUi, modifier: Modifier = Modifier) {
    val description = statRowDescription(row)
    BoxWithConstraints(modifier) {
        // Narrow (edit mode on a phone: handle and eye take 96 dp): tighter padding, and the delta goes under the label so the label keeps its width.
        val narrow = maxWidth < NARROW_ROW
        val stacked = narrow && row.delta != null
        val deltaColor = when (row.better) { true -> BetterGreen; false -> RecoveryRed; null -> TextMuted }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Card)
                .padding(horizontal = if (narrow) 8.dp else 16.dp, vertical = 12.dp).clearAndSetSemantics { contentDescription = description },
            Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
        ) {
            // Tagged for tests: a layout-only Column has no semantics node, and tests assert the width the row gives the label.
            Column(Modifier.weight(1f).testTag("stat-label"), Arrangement.spacedBy(2.dp)) {
                Text(row.label, fontSize = 13.sp, color = TextMuted)
                Text("Baseline ${row.baseline ?: "—"}", fontSize = 12.sp, color = BASELINE_TEXT)
                if (stacked) {
                    Text(listOfNotNull(row.delta, row.verdict).joinToString(" "), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = deltaColor)
                }
            }
            if (row.delta != null && !stacked) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(row.delta, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = deltaColor)
                    row.verdict?.let { Text(it, fontSize = 11.sp, color = deltaColor) }
                }
            }
            Text(row.value, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
            Text(row.unit, Modifier.widthIn(min = if (narrow) 0.dp else 30.dp), fontSize = 12.sp, color = TextMuted)
        }
    }
}

private val NARROW_ROW = 260.dp
private val BASELINE_TEXT = Color(0xFF8A939E)
