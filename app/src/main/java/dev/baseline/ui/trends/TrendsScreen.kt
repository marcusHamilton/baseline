package dev.baseline.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.scores.RecoveryZone
import dev.baseline.ui.components.zoneColor
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.ChipActive
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.SleepPurple
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.theme.TextPrimary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ranges = listOf(TrendRange.D7 to "7 days", TrendRange.D30 to "30 days", TrendRange.D90 to "90 days")
private val SleepCap = Color(0xFF2E2A45)
private const val SLEEP_SCALE_MIN_H = 10 * 3600f
private const val MISSING = "—"
private val AXIS_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US)

@Composable
fun TrendsScreen(vm: TrendsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    TrendsContent(state, vm::setRange)
}

@Composable
fun TrendsContent(state: TrendsUiState, onRange: (TrendRange) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen-trends").padding(bottom = 24.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp), Arrangement.spacedBy(14.dp)) {
            Text(
                "TRENDS",
                Modifier.semantics {
                    heading()
                },
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                letterSpacing = 1.12.sp,
            )
            RangeChips(state.range, onRange)
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), Arrangement.spacedBy(12.dp)) {
            val n = state.range.days
            val a = state.averages
            val start = state.recovery.firstOrNull()?.first
            TrendCard("RECOVERY", a.recovery?.let { "avg $it%" } ?: "avg $MISSING") {
                val have = state.recovery.count { it.second != null }
                BarChart(
                    state.recovery.map { (_, v) -> v?.let { Bar(it / 100f, zoneColor(RecoveryZone.of(it))) } },
                    SleepCap,
                    "Daily recovery, $have of $n days with data, bars coloured by zone",
                )
                TimeAxis(start)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(RecoveryGreen, "67–100")
                    Legend(RecoveryAmber, "34–66")
                    Legend(RecoveryRed, "0–33")
                }
            }
            TrendCard("HRV · OVERNIGHT", state.hrvHeader()) {
                val band = remember(state.hrvBand) { state.hrvBand.toMap() }
                TrendLine(
                    state.hrv.map { it.second },
                    RecoveryGreen,
                    "Overnight HRV over the last $n days, ${state.hrv.count { it.second != null }} days with data",
                    band = state.hrv.map { band[it.first] },
                    height = 110.dp,
                )
                TimeAxis(start)
                if (band.isNotEmpty()) Text("Shaded band = your normal range (Garmin HRV baseline)", fontSize = 11.sp, color = TextMuted)
            }
            TrendCard("RESTING HEART RATE", state.rhrHeader()) {
                TrendLine(
                    state.rhr.map {
                        it.second
                    },
                    RecoveryRed,
                    "Resting heart rate over the last $n days, ${state.rhr.count { it.second != null }} days with data",
                    midline = a.rhr,
                    height = 90.dp,
                )
                TimeAxis(start)
            }
            TrendCard("SLEEP vs NEED", a.sleep?.let { "avg ${Formatters.hoursMinutes(it)}" } ?: "avg $MISSING") {
                val scale = maxOf(SLEEP_SCALE_MIN_H, state.sleepVsNeed.maxOf { maxOf(it.second?.seconds ?: 0L, it.third?.seconds ?: 0L).toFloat() })
                BarChart(
                    state.sleepVsNeed.map { (_, s, need) ->
                        s?.let { Bar(it.seconds / scale, SleepPurple, ((need?.seconds ?: 0L) - it.seconds).coerceAtLeast(0L) / scale) }
                    },
                    SleepCap,
                    "Nightly sleep as bars, the shortfall against need as a lighter cap, ${state.sleepVsNeed.count {
                        it.second != null
                    }} of $n nights with data",
                )
                TimeAxis(start)
            }
        }
    }
}

@Composable
private fun RangeChips(selected: TrendRange, onRange: (TrendRange) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(4.dp), Arrangement.spacedBy(4.dp)) {
        Ranges.forEach { (range, label) ->
            val on = range == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(if (on) ChipActive else Color.Transparent)
                    .clickable(role = Role.Tab) { onRange(range) }.semantics { this.selected = on },
                Alignment.Center,
            ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) TextPrimary else TextMuted) }
        }
    }
}

@Composable
private fun TrendCard(title: String, average: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Bottom) {
            Text(
                title,
                Modifier.semantics {
                    heading()
                },
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 1.28.sp,
                color = TextMuted,
            )
            Text(average, fontSize = 13.sp, color = TextBody)
        }
        content()
    }
}

/** First day of the range on the left, "Today" on the right. The chart's description already gives the span, so TalkBack skips this. */
@Composable
private fun TimeAxis(start: LocalDate?) {
    if (start == null) return
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, Arrangement.SpaceBetween) {
        Text(AXIS_DATE.format(start), fontSize = 11.sp, color = TextMuted)
        Text("Today", fontSize = 11.sp, color = TextMuted)
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, fontSize = 11.sp, color = TextMuted)
    }
}
