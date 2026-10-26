package dev.baseline.ui.charts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import java.time.Instant

/**
 * A minimal single-series line chart (no axes) fitted to its width; not scrollable or zoomable, so it can sit inside a
 * vertically scrolling screen. Points are placed by real timestamp, so uneven sampling keeps its true shape.
 */
@Composable
fun LineChart(
    points: List<Pair<Instant, Number>>,
    color: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    /** Fixed vertical range (e.g. 0..100 for Body Battery); null lets the chart fit the data. */
    yRange: ClosedFloatingPointRange<Double>? = null,
) {
    val series = remember(points) {
        val sorted = points.sortedBy { it.first }
        val t0 = sorted.firstOrNull()?.first?.epochSecond ?: 0L
        // Minutes since the first sample: small numbers keep Vico's x-step arithmetic well conditioned.
        sorted.map { (it.first.epochSecond - t0) / 60.0 } to sorted.map { it.second.toDouble() }
    }
    val model = remember(series) {
        if (series.first.size < 2) null else CartesianChartModel(LineCartesianLayerModel.build { series(series.first, series.second) })
    }
    val chartModifier = modifier.fillMaxWidth().height(120.dp).semantics { this.contentDescription = contentDescription }
    if (model == null) return androidx.compose.foundation.layout.Box(chartModifier)
    ProvideVicoTheme(rememberM3VicoTheme()) {
        val chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                LineCartesianLayer.LineProvider.series(LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(Fill(color)))),
                rangeProvider = remember(yRange) {
                    yRange?.let { CartesianLayerRangeProvider.fixed(minY = it.start, maxY = it.endInclusive) } ?: CartesianLayerRangeProvider.auto()
                },
            ),
            getXStep = { _, _, _ -> 1.0 },
        )
        CartesianChartHost(
            chart,
            model,
            chartModifier,
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content, minZoom = Zoom.Content, maxZoom = Zoom.Content),
        )
    }
}
