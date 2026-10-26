package dev.baseline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.charts.LineChart
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.today.BodyBatteryUi

/** Garmin's Body Battery bands. */
fun bodyBatteryColor(level: Int): Color = when {
    level >= 76 -> Color(0xFF34D399)
    level >= 51 -> Color(0xFFA3D94C)
    level >= 26 -> Color(0xFFFBBF24)
    else -> Color(0xFFF87171)
}

/** The fill's colour, exposed so tests can check the band without reading pixels. */
val BodyBatteryFillColor = SemanticsPropertyKey<Color>("BodyBatteryFillColor")
private var SemanticsPropertyReceiver.bodyBatteryFillColor by BodyBatteryFillColor

const val BODY_BATTERY_FILL_TAG = "body-battery-fill"
/** "Body Battery today, ranging 22 to 83, now 48" (past days say "that day" and "ending at"). */
fun bodyBatteryChartDescription(bb: BodyBatteryUi, isToday: Boolean): String {
    val levels = bb.curve.map { it.second }
    val range = if (levels.isEmpty()) "" else ", ranging ${levels.min()} to ${levels.max()}"
    val last = bb.latest?.let { if (isToday) ", now $it" else ", ending at $it" } ?: ""
    return "Body Battery ${if (isToday) "today" else "that day"}$range$last"
}

/** Full-width bar: title, latest level and a track filled to level/100. Tapping toggles the day's curve and charged/drained totals. */
@Composable
fun BodyBatteryCard(bb: BodyBatteryUi, onToggle: () -> Unit, modifier: Modifier = Modifier, isToday: Boolean = true) {
    val level = bb.latest?.coerceIn(0, 100)
    val color = level?.let(::bodyBatteryColor) ?: Surface2
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
            .clickable(
                onClickLabel = if (bb.expanded) "Hide Body Battery curve" else "Show Body Battery curve",
                role = Role.Button, onClick = onToggle,
            )
            .semantics { stateDescription = if (bb.expanded) "Expanded" else "Collapsed" }
            .heightIn(min = 48.dp).padding(16.dp),
        Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Body Battery", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextMuted)
            Text(level?.toString() ?: "—", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Surface2)) {
            if (level != null && level > 0) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(level / 100f).clip(RoundedCornerShape(5.dp)).background(color)
                        .testTag(BODY_BATTERY_FILL_TAG).semantics { bodyBatteryFillColor = color },
                )
            }
        }
        if (bb.expanded) {
            if (bb.curve.size >= 2) {
                LineChart(bb.curve, color, bodyBatteryChartDescription(bb, isToday), yRange = 0.0..100.0)
            } else {
                Text("No Body Battery curve for this day", fontSize = 13.sp, color = TextMuted)
            }
            val totals = listOfNotNull(bb.charged?.let { "Charged +$it" }, bb.drained?.let { "Drained −$it" }).joinToString(" · ")
            if (totals.isNotEmpty()) Text(totals, fontSize = 13.sp, color = TextBody)
        }
    }
}
