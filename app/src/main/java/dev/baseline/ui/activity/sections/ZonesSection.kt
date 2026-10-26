package dev.baseline.ui.activity.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.activity.Section
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextBody
import kotlin.math.roundToInt

internal val ZONES = listOf(
    "Z1 Warm" to Color(0xFF64748B), "Z2 Easy" to Color(0xFF60A5FA), "Z3 Aerobic" to Color(0xFF34D399),
    "Z4 Threshold" to Color(0xFFFBBF24), "Z5 Max" to Color(0xFFF87171),
)

/** (name, "m:ss", bar fraction of the total time) per zone; "—" when Garmin sent no figure for the zone. */
internal fun zoneRows(seconds: List<Double?>): List<Triple<String, String, Float>> {
    val total = seconds.sumOf { it ?: 0.0 }
    return ZONES.mapIndexed { i, (name, _) ->
        val s = seconds.getOrNull(i)
        Triple(name, if (s == null) "—" else Formatters(false).duration(s) ?: "0:00", if (s == null || total <= 0) 0f else (s / total).toFloat())
    }
}

@Composable
fun ZonesSection(zones: Section.Zones) {
    SectionCard("HEART RATE ZONES", "section-zones") {
        zoneRows(zones.secondsPerZone).forEachIndexed { i, (name, time, share) ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$name $time, ${(share * 100).roundToInt()} percent" },
                Arrangement.spacedBy(10.dp), Alignment.CenterVertically,
            ) {
                Text(name, Modifier.width(88.dp), fontSize = 13.sp, color = TextBody)
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Surface2).clearAndSetSemantics {}) {
                    Box(Modifier.fillMaxWidth(share).height(10.dp).clip(RoundedCornerShape(5.dp)).background(ZONES[i].second))
                }
                Text(time, Modifier.width(48.dp), fontSize = 13.sp, textAlign = TextAlign.End, fontFamily = FontFamily.Default)
            }
        }
    }
}
