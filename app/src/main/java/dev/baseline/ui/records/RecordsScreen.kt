package dev.baseline.ui.records

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.R
import dev.baseline.data.SportFamily
import dev.baseline.ui.components.CircleButton
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.theme.TextPrimary

private val Tabs = listOf("Run" to SportFamily.RUN, "Ride" to SportFamily.RIDE, "Swim" to SportFamily.SWIM)
private val NewBg = Color(0xFF3A2F12)
private val NewFg = Color(0xFFFDE68A)

@Composable
fun RecordsScreen(vm: RecordsViewModel, onBack: () -> Unit, onOpenActivity: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    RecordsContent(state, vm::setTab, onBack, onOpenActivity)
}

@Composable
fun RecordsContent(state: RecordsUiState, onTab: (SportFamily) -> Unit, onBack: () -> Unit, onOpenActivity: (Long) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("screen-records"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") { Header(state.since, onBack) }
        item(key = "tabs") { TabRow(state.tab, onTab) }
        item(key = "heading") {
            Text(
                if (state.tab == SportFamily.SWIM) "FASTEST TIMES · POOL" else "FASTEST TIMES",
                Modifier.padding(top = 8.dp, bottom = 4.dp),
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 1.28.sp,
                color = TextMuted,
            )
        }
        items(state.rows, key = { "${state.tab}-${it.label}" }) { RecordCard(it, onOpenActivity) }
        state.power?.let { power -> item(key = "power") { PowerCard(power, state.powerLabel) } }
    }
}

@Composable
private fun Header(since: String?, onBack: () -> Unit) {
    Row(Modifier.padding(bottom = 8.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        CircleButton(R.drawable.ic_chevron_left, "Back to Activities", true, onBack)
        Column {
            Text("ALL-TIME RECORDS", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.88.sp)
            Text(
                "Best efforts from every activity" + (since?.let { " since $it" } ?: ""),
                fontSize = 13.sp,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun TabRow(selected: SportFamily, onTab: (SportFamily) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(4.dp), Arrangement.spacedBy(4.dp)) {
        Tabs.forEach { (label, family) ->
            val on = family == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Surface2 else Color.Transparent)
                    .clickable(role = Role.Tab) { onTab(family) }
                    .semantics { this.selected = on },
                Alignment.Center,
            ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) TextPrimary else TextMuted) }
        }
    }
}

@Composable
private fun RecordCard(r: RecordRow, onOpen: (Long) -> Unit) {
    val open = r.activityId
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Card)
            .then(if (open != null) Modifier.clickable(role = Role.Button) { onOpen(open) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        Arrangement.spacedBy(12.dp),
        Alignment.CenterVertically,
    ) {
        Text(r.label, Modifier.width(88.dp), fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 0.36.sp)
        Column(Modifier.weight(1f), Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.time, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                if (r.isNew) {
                    Text(
                        "NEW",
                        Modifier.clip(RoundedCornerShape(6.dp)).background(NewBg).padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = NewFg,
                    )
                }
            }
            Text(r.meta, fontSize = 12.sp, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (open != null) Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp), tint = TextMuted)
    }
}

@Composable
private fun PowerCard(power: List<PowerRow>, label: String) {
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), Arrangement.spacedBy(12.dp)) {
        Text(
            "BEST POWER · ${label.uppercase()}",
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            letterSpacing = 1.28.sp,
            color = TextMuted,
        )
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
            power.forEach { p ->
                Column(
                    Modifier.weight(1f).semantics(mergeDescendants = true) {
                        contentDescription =
                            if (p.watts == "—") "${p.label}: no record" else "${p.label}: ${p.watts} watts"
                    },
                    Arrangement.spacedBy(2.dp),
                    Alignment.CenterHorizontally,
                ) {
                    Text(p.watts, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(p.label, fontSize = 11.sp, color = TextMuted)
                }
            }
        }
        Text("Watts, best average over each duration", fontSize = 12.sp, color = TextMuted)
    }
}
