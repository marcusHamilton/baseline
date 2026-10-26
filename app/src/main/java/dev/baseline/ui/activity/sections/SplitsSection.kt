package dev.baseline.ui.activity.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.activity.Section
import dev.baseline.ui.activity.Tile
import dev.baseline.ui.theme.StrainBlue
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted

private val LabelW = 40.dp
private val PaceW = 48.dp
private val HrW = 40.dp
private val ElevW = 44.dp

@Composable
private fun Right(text: String, width: androidx.compose.ui.unit.Dp, size: Int, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified, weight: FontWeight? = null) =
    Text(text, modifier.width(width), fontSize = size.sp, color = color, fontWeight = weight, textAlign = TextAlign.End, maxLines = 1)

@Composable
fun SplitsSection(splits: Section.Splits) {
    SectionCard("SPLITS", "section-splits") {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, Arrangement.spacedBy(8.dp)) {
            Text(splits.unit.uppercase(), Modifier.width(LabelW), fontSize = 11.sp, color = TextMuted)
            Spacer(Modifier.weight(1f))
            Right("PACE", PaceW, 11, color = TextMuted)
            Right("HR", HrW, 11, color = TextMuted)
            Right("ELEV", ElevW, 11, color = TextMuted)
        }
        splits.rows.forEach { r ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 30.dp).semantics(mergeDescendants = true) {
                    contentDescription = "${splits.unit} ${r.label}, pace ${r.pace} per ${splits.unit}, heart rate ${r.hr}, elevation ${r.elev}"
                },
                Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
            ) {
                Text(r.label, Modifier.width(LabelW), fontSize = 13.sp, color = TextMuted)
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Surface2)) {
                    Box(Modifier.fillMaxWidth(r.bar).height(8.dp).clip(RoundedCornerShape(4.dp)).background(StrainBlue))
                }
                Right(r.pace, PaceW, 13, weight = FontWeight.SemiBold)
                Right(r.hr, HrW, 13)
                Right(r.elev, ElevW, 13, color = TextBody)
            }
        }
    }
}

/** Cadence, average power and stride length: three small tiles under the splits. */
@Composable
fun RunningSection(r: Section.Running) {
    Row(Modifier.fillMaxWidth().testTag("section-running"), Arrangement.spacedBy(8.dp)) {
        listOf(Tile("cadence spm", r.cadence), Tile("avg power W", r.power), Tile("stride length m", r.stride)).forEach {
            StatTile(it, Modifier.weight(1f), 22)
        }
    }
}
