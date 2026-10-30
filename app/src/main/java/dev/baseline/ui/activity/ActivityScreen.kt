package dev.baseline.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.R
import dev.baseline.ui.activity.sections.HrSection
import dev.baseline.ui.activity.sections.RideSection
import dev.baseline.ui.activity.sections.RouteMap
import dev.baseline.ui.activity.sections.RunningSection
import dev.baseline.ui.activity.sections.SplitsSection
import dev.baseline.ui.activity.sections.StrengthSection
import dev.baseline.ui.activity.sections.SummaryGrid
import dev.baseline.ui.activity.sections.SwimSection
import dev.baseline.ui.activity.sections.ZonesSection
import dev.baseline.ui.components.CircleButton
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.TextMuted

/** [mapContent] draws the route; tests swap in a stub because MapLibre's native code cannot run under Robolectric. */
@Composable
fun ActivityScreen(
    vm: ActivityViewModel,
    onBack: () -> Unit,
    mapContent: @Composable (List<Pair<Double, Double>>) -> Unit = { RouteMap(it, Modifier.fillMaxSize()) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    ActivityContent(state, onBack, mapContent)
}

@Composable
fun ActivityContent(state: ActivityUiState, onBack: () -> Unit, mapContent: @Composable (List<Pair<Double, Double>>) -> Unit) {
    Column(
        Modifier.fillMaxSize().testTag("screen-activity").verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 32.dp),
        Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(R.drawable.ic_chevron_left, "Back", true, onBack)
            Column {
                Text(
                    (state.header?.title ?: "ACTIVITY").uppercase(),
                    Modifier.semantics { heading() },
                    fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    letterSpacing = 0.88.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.header?.let { Text(it.subtitle, fontSize = 13.sp, color = TextMuted) }
            }
        }
        if (state.header == null) {
            Text(if (state.loading) "Loading…" else "Activity not found", Modifier.padding(vertical = 24.dp), fontSize = 14.sp, color = TextMuted)
            return@Column
        }
        // The map leads (as in the design); the summary tiles follow it, then the remaining sections in order.
        state.sections.filterIsInstance<Section.Map>().firstOrNull()?.let { map ->
            Box(Modifier.fillMaxWidth().height(220.dp).testTag("section-map").clip(RoundedCornerShape(16.dp)).background(Card)) { mapContent(map.latLon) }
        }
        SummaryGrid(state.summary)
        state.note?.let {
            Text(
                it,
                Modifier.fillMaxWidth().testTag("note").clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp),
                fontSize = 14.sp,
                color = TextMuted,
            )
        }
        state.sections.forEach { section ->
            when (section) {
                is Section.Map -> {}
                is Section.Hr -> HrSection(section)
                is Section.Zones -> ZonesSection(section)
                is Section.Splits -> SplitsSection(section)
                is Section.Running -> RunningSection(section)
                is Section.Ride -> RideSection(section)
                is Section.Swim -> SwimSection(section)
                is Section.Strength -> StrengthSection(section)
            }
        }
    }
}
