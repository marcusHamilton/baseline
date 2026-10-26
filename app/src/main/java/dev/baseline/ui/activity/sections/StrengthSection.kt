package dev.baseline.ui.activity.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.activity.Section
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.TextMuted

private val ChipBg = Color(0xFF22272E)
private val ChipText = Color(0xFFE2E6EA)

/** Exercises grouped by category: name and "4 sets · 2,400 kg" on top, one chip per set below. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StrengthSection(strength: Section.Strength) {
    Column(Modifier.fillMaxWidth().testTag("section-strength"), Arrangement.spacedBy(8.dp)) {
        Text("EXERCISES", Modifier.padding(bottom = 4.dp), fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 1.28.sp, color = TextMuted)
        strength.exercises.forEach { ex ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 16.dp, vertical = 14.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "${ex.name}, ${ex.summary}, sets ${ex.sets.joinToString(", ")}" },
                Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Bottom) {
                    Text(ex.name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(ex.summary, fontSize = 13.sp, color = TextMuted)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ex.sets.forEach { Text(it, Modifier.clip(RoundedCornerShape(8.dp)).background(ChipBg).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 13.sp, color = ChipText) }
                }
            }
        }
    }
}
