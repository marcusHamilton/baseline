package dev.baseline.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.R
import dev.baseline.ui.components.CircleButton
import dev.baseline.ui.components.zoneColor
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.SleepPurpleLight
import dev.baseline.ui.theme.StrainBlueLight
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.theme.TextPrimary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val SelectedBg = Color(0xFF22272E)
private val SelectedBorder = Color(0xFF2E333B)
private val FutureText = Color(0xFF5B636D)
private val DotColor = StrainBlueLight
private val Title = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val CellDate = DateTimeFormatter.ofPattern("MMMM d", Locale.US)
private val CardDate = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val SWIPE_THRESHOLD = 60.dp
private val PageInset = 8.dp

@Composable
fun CalendarScreen(vm: CalendarViewModel, onBack: () -> Unit, onOpenDay: (LocalDate) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    CalendarContent(state, onBack, vm::prevMonth, vm::nextMonth, vm::select, onOpenDay)
}

internal fun cellDescription(c: CalendarCell): String = c.date.format(CellDate) + when {
    c.isFuture -> ", no data yet"
    c.recovery == null && c.activities == 0 -> ", no data"
    else -> (c.recovery?.let { ", recovery $it%" } ?: "") +
        (if (c.activities > 0) ", ${c.activities} ${if (c.activities == 1) "activity" else "activities"}" else "")
}

@Composable
fun CalendarContent(
    state: CalendarUiState,
    onBack: () -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().testTag("screen-calendar").verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 32.dp),
        Arrangement.spacedBy(16.dp),
    ) {
        // Page margin 12 dp, plus an 8 dp inset on everything but the grid, whose cells keep a full 48 dp touch width on a 360 dp phone; each cell draws its box inset by the same 8 dp.
        Row(Modifier.padding(horizontal = PageInset), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            CircleButton(R.drawable.ic_chevron_left, "Back to Today", true, onBack)
            Text(
                state.month.atDay(1).format(Title).uppercase(), Modifier.weight(1f),
                fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.88.sp,
            )
            CircleButton(R.drawable.ic_chevron_left, "Previous month", true, onPrevMonth)
            CircleButton(R.drawable.ic_chevron_right, "Next month", state.canGoNext, onNextMonth)
        }
        MonthGrid(state, onPrevMonth, onNextMonth, onSelect, Modifier)
        MonthSummary(state, Modifier.padding(horizontal = PageInset))
        // Tagged for tests: the card is a layout-only Column with no semantics node; tests compare its edge to the selected day box.
        DayCard(state, onOpenDay, Modifier.padding(horizontal = PageInset).testTag("day-card"))
    }
}

@Composable
private fun MonthGrid(state: CalendarUiState, onPrev: () -> Unit, onNext: () -> Unit, onSelect: (LocalDate) -> Unit, modifier: Modifier) {
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    val prev by rememberUpdatedState(onPrev)
    val next by rememberUpdatedState(onNext)
    Column(
        modifier.pointerInput(threshold) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = { if (total > threshold) prev() else if (total < -threshold) next() },
            ) { _, amount -> total += amount }
        },
        Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, Arrangement.Start) {
            repeat(7) { i ->
                val d = DayOfWeek.of((state.firstDayOfWeek.value - 1 + i) % 7 + 1)
                Text(
                    d.getDisplayName(TextStyle.NARROW, Locale.US), Modifier.weight(1f),
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextMuted, textAlign = TextAlign.Center,
                )
            }
        }
        state.cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), Arrangement.Start) {
                repeat(7) { i ->
                    val cell = week.getOrNull(i)
                    if (cell == null) Spacer(Modifier.weight(1f).height(58.dp))
                    else DayCell(cell, cell.date == state.selected, { onSelect(cell.date) }, Modifier.weight(1f))
                }
            }
        }
        Legend()
    }
}

@Composable
private fun DayCell(cell: CalendarCell, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val outline = when { cell.isToday -> TextPrimary; selected -> SelectedBorder; else -> Color.Transparent }
    val description = cellDescription(cell)
    // The touch target is the whole 48 dp+ column; the drawn box sits PageInset inside it so Sunday's ends at the cards' edge.
    Box(
        modifier.height(58.dp)
            .then(if (cell.isFuture) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .semantics(mergeDescendants = true) { contentDescription = description; this.selected = selected },
    ) {
    Column(
        // Tagged for tests: the drawn box is inset inside the described touch target and has no semantics node of its own.
        Modifier.fillMaxSize().padding(horizontal = PageInset).testTag("day-highlight").clip(shape)
            .background(if (selected) SelectedBg else Color.Transparent).border(1.dp, outline, shape).padding(vertical = 6.dp),
        Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(26.dp).border(3.dp, cell.zone?.let(::zoneColor) ?: Color.Transparent, CircleShape),
            Alignment.Center,
        ) {
            Text("${cell.date.dayOfMonth}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (cell.isFuture) FutureText else TextPrimary)
        }
        Row(Modifier.padding(top = 5.dp).height(5.dp), Arrangement.spacedBy(3.dp)) {
            repeat(cell.activityCount) { Spacer(Modifier.size(5.dp).clip(CircleShape).background(DotColor)) }
        }
    }
    }
}

@Composable
private fun Legend() {
    Row(Modifier.padding(start = PageInset, top = 6.dp).clearAndSetSemantics {}, Arrangement.spacedBy(14.dp)) {
        listOf("Recovered" to RecoveryGreen, "Maintaining" to RecoveryAmber, "Low" to RecoveryRed).forEach { (label, color) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(10.dp).border(2.dp, color, CircleShape))
                Text(label, fontSize = 11.sp, color = TextMuted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(5.dp).clip(CircleShape).background(DotColor))
            Text("Activity", fontSize = 11.sp, color = TextMuted)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, valueColor: Color, modifier: Modifier = Modifier, size: Int = 24, weight: FontWeight = FontWeight.SemiBold) {
    Column(modifier, Arrangement.spacedBy(2.dp)) {
        Text(value, fontFamily = BarlowCondensed, fontWeight = weight, fontSize = size.sp, color = valueColor)
        Text(label, fontSize = 11.sp, color = TextMuted)
    }
}

@Composable
private fun MonthSummary(state: CalendarUiState, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
        Arrangement.spacedBy(8.dp),
    ) {
        Stat(state.monthAvgRecovery?.let { "$it%" } ?: "—", "avg recovery", TextPrimary, Modifier.weight(1f))
        Stat(Formatters.hoursMinutes(state.monthAvgSleepSec) ?: "—", "avg sleep", TextPrimary, Modifier.weight(1f))
        Stat("${state.monthActivities}", "activities", TextPrimary, Modifier.weight(1f))
    }
}

@Composable
private fun DayCard(state: CalendarUiState, onOpenDay: (LocalDate) -> Unit, modifier: Modifier) {
    val s = state.summary
    val isToday = state.cells.firstOrNull { it?.date == s.date }?.isToday == true
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(
                (if (isToday) "Today · " else "") + s.date.format(CardDate),
                fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 0.54.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onOpenDay(s.date) }.padding(horizontal = 8.dp),
                Alignment.Center,
            ) { Text("Open day →", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = StrainBlueLight) }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            Stat(s.recovery?.let { "$it%" } ?: "—", "recovery", s.zone?.let(::zoneColor) ?: TextMuted, Modifier.weight(1f), 26, FontWeight.Bold)
            Stat(s.sleepPct?.let { "$it%" } ?: "—", "sleep", SleepPurpleLight, Modifier.weight(1f), 26, FontWeight.Bold)
            Stat(s.strain?.let { "%.1f".format(Locale.US, it) } ?: "—", "strain", StrainBlueLight, Modifier.weight(1f), 26, FontWeight.Bold)
        }
        Text(
            if (s.activityNames.isEmpty()) "Rest day — no activities" else s.activityNames.joinToString(" · "),
            fontSize = 13.sp, color = TextBody,
        )
    }
}
