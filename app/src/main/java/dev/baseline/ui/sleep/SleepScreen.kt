package dev.baseline.ui.sleep

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.R
import dev.baseline.ui.charts.LineChart
import dev.baseline.ui.components.CircleButton
import dev.baseline.ui.components.Dial
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.activity.NOTE_UNREADABLE
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.SleepPurple
import dev.baseline.ui.theme.SleepPurpleLight
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ChipBg = Color(0xFF22272E)
private val ChipText = Color(0xFFE2E6EA)
private val DeepColor = Color(0xFF5B48C2)
private val RemColor = Color(0xFF7DD3FC)
private val AwakeColor = Color(0xFFFBBF24)
private val ChartHeight = 88.dp
private val MonthDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)

@Composable
fun SleepScreen(vm: SleepViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    SleepContent(state, DateFormat.is24HourFormat(LocalContext.current), onBack)
}

/** "Oct 22 – 23": the night that ends on [date]. */
internal fun nightLabel(date: LocalDate): String {
    val start = date.minusDays(1)
    return start.format(MonthDay) + " – " + if (start.month == date.month) "${date.dayOfMonth}" else date.format(MonthDay)
}

@Composable
fun SleepContent(state: SleepUiState, is24h: Boolean, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().testTag("screen-sleep").verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 32.dp),
        Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(R.drawable.ic_chevron_left, "Back", true, onBack)
            Column {
                Text("SLEEP", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.88.sp)
                Text("Night of ${nightLabel(state.date)}", fontSize = 13.sp, color = TextMuted)
            }
        }
        if (state.unreadable) Text(NOTE_UNREADABLE, fontSize = 13.sp, color = RecoveryAmber)
        Hero(state, is24h)
        OvernightCard(state, is24h)
        Stages(state)
        state.needCard?.let { NeedCard(it, state.isToday, is24h) }
        Tiles(state.tiles)
    }
}

@Composable
private fun Hero(state: SleepUiState, is24h: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.spacedBy(20.dp), Alignment.CenterVertically) {
        Dial(
            state.sleepPct?.let { it / 100f }, state.sleepPct?.toString() ?: "—", if (state.sleepPct != null) "%" else "", SleepPurple,
            112.dp, 9.dp, 36, state.sleepPct?.let { "Sleep performance $it percent" } ?: "Sleep unavailable",
        )
        Column(Modifier.weight(1f), Arrangement.spacedBy(6.dp)) {
            Text(Formatters.hoursMinutes(state.asleep), fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 32.sp)
            Text(
                if (state.need.isZero) "asleep" else "asleep of ${Formatters.hoursMinutes(state.need)} needed",
                fontSize = 14.sp, color = TextBody,
            )
            val span = state.bed?.let { b -> state.wake?.let { w -> "${Formatters.time(b, is24h)} → ${Formatters.time(w, is24h)}" } }
            val line = listOfNotNull(span, state.garminScore?.let { "Garmin score $it" }).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, fontSize = 13.sp, color = TextMuted)
        }
    }
}

@Composable
private fun CardTitle(text: String) =
    Text(text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 1.28.sp, color = TextMuted)

@Composable
private fun OvernightCard(state: SleepUiState, is24h: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        CardTitle("OVERNIGHT")
        val hrv = state.hrvSeries.filter { it.second > 0 }
        val hr = state.hrSeries
        when {
            state.overnightLoading ->
                Text("Loading overnight data…", Modifier.fillMaxWidth().padding(vertical = 24.dp), fontSize = 14.sp, color = TextMuted)
            hrv.size < 2 && hr.size < 2 ->
                Text("No overnight data for this night", Modifier.fillMaxWidth().padding(vertical = 24.dp), fontSize = 14.sp, color = TextMuted)
            else -> {
                if (hrv.size >= 2) SeriesChart("HRV", "ms", hrv.map { it.first to it.second }, RecoveryGreen, "Overnight HRV")
                if (hr.size >= 2) SeriesChart("Heart rate", "bpm", hr, RecoveryRed, "Overnight heart rate")
                if (state.bed != null && state.wake != null) {
                    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, Arrangement.SpaceBetween) {
                        Text(Formatters.time(state.bed, is24h), fontSize = 11.sp, color = TextMuted)
                        Text(Formatters.time(state.wake, is24h), fontSize = 11.sp, color = TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesChart(label: String, unit: String, points: List<Pair<java.time.Instant, Int>>, color: Color, description: String) {
    val lo = points.minOf { it.second }; val hi = points.maxOf { it.second }
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(width = 12.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Text("$label $unit", fontSize = 12.sp, color = TextBody)
        }
        Text("$lo–$hi", fontSize = 12.sp, color = TextMuted)
    }
    // Fit the data (Vico's auto range includes zero, which would squash a 50-120 line into the top).
    val pad = maxOf(2.0, (hi - lo) * 0.1)
    LineChart(points, color, "$description, from $lo to $hi $unit", Modifier.height(ChartHeight), (lo - pad)..(hi + pad))
}

private fun stageColor(label: String) = when (label) {
    "Deep" -> DeepColor; "REM" -> RemColor; "Awake" -> AwakeColor; else -> SleepPurple
}

@Composable
private fun Stages(state: SleepUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(bottom = 4.dp)) { CardTitle("STAGES") }
        state.stages.forEach { st ->
            val time = st.seconds?.let { Formatters.hoursMinutes(it.toDouble()) } ?: "—"
            val color = stageColor(st.label)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${st.label} $time" + (st.pct?.let { ", $it percent" } ?: "")
                    },
                Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                        Text(st.label, fontSize = 14.sp)
                    }
                    Row {
                        Text(time, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(" · ${st.pct?.let { "$it%" } ?: "—"}", fontSize = 14.sp, color = TextMuted)
                    }
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Surface2)) {
                    Box(Modifier.fillMaxWidth((st.pct ?: 0) / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
                }
            }
        }
    }
}

@Composable
private fun NeedCard(need: SleepNeedUi, tonight: Boolean, is24h: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        CardTitle(if (tonight) "TONIGHT'S SLEEP NEED" else "NEXT NIGHT'S SLEEP NEED")
        NeedRow("Your baseline (Garmin)", Formatters.hoursMinutes(need.baseline))
        NeedRow("Garmin adjustment", Formatters.signedHoursMinutes(need.adjustment))
        if (need.reasons.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            need.reasons.forEach { r ->
                Text(r, Modifier.clip(RoundedCornerShape(8.dp)).background(ChipBg).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 12.sp, color = ChipText)
            }
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Surface2))
        val aim = Formatters.hoursMinutes(need.baseline + need.adjustment)
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text("Aim for", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                aim + (need.bedtimeStart?.let { " · bed ${Formatters.time(it, is24h)}" } ?: ""),
                Modifier.padding(start = 12.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = SleepPurpleLight,
            )
        }
    }
}

@Composable
private fun NeedRow(label: String, value: String) = Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
    Text(label, fontSize = 14.sp, color = TextBody)
    Text(value, fontSize = 14.sp)
}

@Composable
private fun Tiles(t: SleepTiles) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Overnight HRV", t.hrv, "ms", Modifier.weight(1f))
            Tile("Lowest heart rate", t.lowestHr, "bpm", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Body Battery charged", t.bodyBattery, "", Modifier.weight(1f))
            Tile("Avg SpO₂", t.spo2, "%", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, modifier: Modifier) {
    val spoken = if (value == "—") "$label, no value" else "$label $value $unit".trim()
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 16.dp, vertical = 14.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        Arrangement.spacedBy(4.dp),
    ) {
        Text(label, fontSize = 12.sp, color = TextMuted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
            if (value != "—" && unit.isNotEmpty()) Text(" $unit", Modifier.padding(bottom = 3.dp), fontSize = 14.sp, color = TextMuted)
        }
    }
}
