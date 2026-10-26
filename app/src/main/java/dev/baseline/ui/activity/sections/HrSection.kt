package dev.baseline.ui.activity.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.activity.Section
import dev.baseline.ui.charts.LineChart
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.TextMuted
import java.time.Instant
import kotlin.math.roundToInt

/** [yRange] for a chart that fits its data (Vico's auto range includes zero, which would squash a 50-170 line). */
internal fun fitRange(points: List<Pair<Instant, Number>>): ClosedFloatingPointRange<Double> {
    val lo = points.minOf { it.second.toDouble() }
    val hi = points.maxOf { it.second.toDouble() }
    val pad = maxOf(1.0, (hi - lo) * 0.1)
    return (lo - pad)..(hi + pad)
}

@Composable
fun HrSection(hr: Section.Hr) {
    val caption = "avg ${hr.avg ?: "—"} · max ${hr.max ?: "—"}"
    val lo = hr.series.minOf { it.second.toDouble() }.roundToInt()
    val hi = hr.series.maxOf { it.second.toDouble() }.roundToInt()
    SectionCard("HEART RATE", "section-hr", caption = caption) {
        LineChart(hr.series, RecoveryRed, "Heart rate over the activity, from $lo to $hi bpm, $caption", Modifier.height(110.dp), fitRange(hr.series))
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, Arrangement.SpaceBetween) {
            hr.ticks.forEach { Text(it, fontSize = 11.sp, color = TextMuted) }
        }
    }
}
