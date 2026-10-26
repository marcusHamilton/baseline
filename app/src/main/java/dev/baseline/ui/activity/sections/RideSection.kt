package dev.baseline.ui.activity.sections

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.baseline.ui.activity.Section
import dev.baseline.ui.activity.Series
import dev.baseline.ui.charts.LineChart
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.StrainBlue

/** Speed, power (when the ride has any) and elevation, each as its own chart card. */
@Composable
fun RideSection(ride: Section.Ride) {
    ride.speed?.let { RideChart("SPEED", "section-ride", it, StrainBlue, "Speed over the ride") }
    ride.power?.let { RideChart("POWER", "section-power", it, RecoveryAmber, "Power over the ride") }
    ride.elevation?.let { RideChart("ELEVATION", "section-elevation", it, RecoveryGreen, "Elevation profile") }
}

@Composable
private fun RideChart(title: String, tag: String, series: Series, color: Color, description: String) {
    SectionCard(title, tag, caption = series.caption) {
        LineChart(series.points, color, "$description, ${series.caption}", Modifier.height(110.dp), fitRange(series.points))
    }
}
