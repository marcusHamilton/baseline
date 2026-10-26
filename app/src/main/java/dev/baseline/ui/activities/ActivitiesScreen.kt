package dev.baseline.ui.activities

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.R
import dev.baseline.data.SportFamily
import dev.baseline.ui.components.ActivityCard
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.theme.Bg
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.StrainBlueLight
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.theme.TextPrimary
import java.util.Locale

private val Chips: List<Pair<String, SportFamily?>> = listOf(
    "All" to null, "Run" to SportFamily.RUN, "Ride" to SportFamily.RIDE,
    "Strength" to SportFamily.STRENGTH, "Swim" to SportFamily.SWIM, "Other" to SportFamily.OTHER,
)

private val RecordsBg = Color(0xFF3A2F12)
private val RecordsFg = Color(0xFFFDE68A)
private val ChipBorder = Color(0xFF2E333B)
private const val KM_PER_MILE = 1.609344

@Composable
fun ActivitiesScreen(vm: ActivitiesViewModel, onOpenActivity: (Long) -> Unit, onOpenRecords: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    ActivitiesContent(state, vm::setFilter, onOpenRecords, onOpenActivity)
}

@Composable
fun ActivitiesContent(
    state: ActivitiesUiState,
    onFilter: (SportFamily?) -> Unit,
    onOpenRecords: () -> Unit,
    onOpenActivity: (Long) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("screen-activities"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
    ) {
        item(key = "header") { Header(state.filter, onFilter, onOpenRecords) }
        item(key = "week") { WeekCard(state.week, state.imperial) }
        state.days.forEach { group ->
            item(key = "day-${group.date}") {
                Text(
                    Formatters.dayHeader(group.date, state.today),
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMuted,
                )
            }
            items(group.items, key = { it.id }) { a ->
                ActivityCard(a, { onOpenActivity(a.id) }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(selected: SportFamily?, onFilter: (SportFamily?) -> Unit, onOpenRecords: () -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp), Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("ACTIVITIES", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = 1.12.sp)
            Row(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(RecordsBg)
                    .semantics { contentDescription = "Records" }
                    .clickable(onClickLabel = "Open records", role = Role.Button, onClick = onOpenRecords)
                    .padding(horizontal = 14.dp),
                Arrangement.spacedBy(6.dp), Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_trophy), contentDescription = null, Modifier.size(16.dp), tint = RecordsFg)
                Text("Records", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RecordsFg)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chips.forEach { (label, family) ->
                val on = family == selected
                FilterChip(
                    on, { onFilter(family) }, { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent, labelColor = TextBody,
                        selectedContainerColor = TextPrimary, selectedLabelColor = Bg,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = on, borderColor = ChipBorder, selectedBorderColor = TextPrimary,
                    ),
                    shape = RoundedCornerShape(18.dp),
                )
            }
        }
    }
}

@Composable
private fun WeekCard(week: WeekSummary, imperial: Boolean) {
    val count = week.count.toString()
    val hours = Formatters.hoursColonMinutes(week.duration)
    val distance = (if (imperial) week.distanceKm / KM_PER_MILE else week.distanceKm).takeIf { it > 0 }
        ?.let { "%.1f".format(Locale.US, it) } ?: "—"
    val strain = week.avgStrain?.let { "%.1f".format(Locale.US, it) } ?: "—"
    val unit = if (imperial) "mi" else "km"
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
            .semantics(mergeDescendants = true) {
                contentDescription = "This week: $count activities, $hours hours, $distance $unit, average strain $strain"
            }
            .padding(16.dp),
        Arrangement.spacedBy(8.dp),
    ) {
        Stat(count, "this week", Modifier.weight(1f))
        Stat(hours, "hours", Modifier.weight(1f))
        Stat(distance, unit, Modifier.weight(1f))
        Stat(strain, "avg strain", Modifier.weight(1f), StrainBlueLight)
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, color: Color = Color.Unspecified) {
    Column(modifier, Arrangement.spacedBy(2.dp)) {
        Text(value, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = color)
        Text(label, fontSize = 11.sp, color = TextMuted)
    }
}
