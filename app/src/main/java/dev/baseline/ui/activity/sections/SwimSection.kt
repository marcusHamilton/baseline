package dev.baseline.ui.activity.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.activity.Section
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted

@Composable
fun SwimSection(swim: Section.Swim) {
    SectionCard("LENGTHS", "section-swim", caption = swim.summary) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, Arrangement.spacedBy(8.dp)) {
            Text("#", Modifier.width(32.dp), fontSize = 11.sp, color = TextMuted)
            Text("STROKE", Modifier.weight(1f), fontSize = 11.sp, color = TextMuted)
            Text("STROKES", Modifier.width(56.dp), fontSize = 11.sp, color = TextMuted, textAlign = TextAlign.End)
            Text("TIME", Modifier.width(48.dp), fontSize = 11.sp, color = TextMuted, textAlign = TextAlign.End)
            Text("PACE", Modifier.width(48.dp), fontSize = 11.sp, color = TextMuted, textAlign = TextAlign.End)
        }
        swim.lengths.forEach { l ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 30.dp).semantics(mergeDescendants = true) {
                    contentDescription = "Length ${l.n}, ${l.stroke}, ${l.strokes} strokes, ${l.time}, pace ${l.pace}"
                },
                Arrangement.spacedBy(8.dp),
                Alignment.CenterVertically,
            ) {
                Text("${l.n}", Modifier.width(32.dp), fontSize = 13.sp, color = TextMuted)
                Text(l.stroke, Modifier.weight(1f), fontSize = 13.sp, color = TextBody, maxLines = 1)
                Text(l.strokes, Modifier.width(56.dp), fontSize = 13.sp, textAlign = TextAlign.End)
                Text(l.time, Modifier.width(48.dp), fontSize = 13.sp, textAlign = TextAlign.End)
                Text(l.pace, Modifier.width(48.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            }
        }
    }
}
