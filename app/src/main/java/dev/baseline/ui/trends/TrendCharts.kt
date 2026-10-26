package dev.baseline.ui.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextMuted

/** [fraction] is 0..1 of the chart height; [cap] sits on top of it in [capColor] (the unmet part of a target). */
internal class Bar(val fraction: Float, val color: Color, val cap: Float = 0f)

private val BandColor = Color(0xFF1F2A26)
private val MIN_BAR = 2.dp

/** A null entry is a missing day: an empty slot, not a short bar. */
@Composable
internal fun BarChart(bars: List<Bar?>, capColor: Color, description: String, modifier: Modifier = Modifier) {
    ChartBox(bars.any { it != null }, 96.dp, description, modifier) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val slot = size.width / bars.size
            val gap = 1.dp.toPx().coerceAtMost(slot / 3)
            bars.forEachIndexed { i, b ->
                if (b == null) return@forEachIndexed
                val h = (b.fraction.coerceIn(0f, 1f) * size.height).coerceAtLeast(MIN_BAR.toPx())
                val cap = (b.cap.coerceIn(0f, 1f) * size.height).coerceAtMost(size.height - h)
                val x = i * slot
                drawRect(b.color, Offset(x, size.height - h), Size(slot - gap, h))
                if (cap > 0f) drawRect(capColor, Offset(x, size.height - h - cap), Size(slot - gap, cap))
            }
        }
    }
}

/**
 * [values] has one entry per day; a null breaks the line (runs of two or more days are joined, a lone day is a dot).
 * [band] is the same length, null where there is no band. [midline] is drawn dashed.
 */
@Composable
internal fun TrendLine(
    values: List<Int?>,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    band: List<ClosedRange<Int>?> = emptyList(),
    midline: Int? = null,
    height: androidx.compose.ui.unit.Dp = 96.dp,
) {
    ChartBox(values.any { it != null }, height, description, modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val all = values.filterNotNull() + band.filterNotNull().flatMap { listOf(it.start, it.endInclusive) } + listOfNotNull(midline)
            val lo = all.min().toFloat()
            val hi = all.max().toFloat()
            val pad = 6.dp.toPx()
            fun x(i: Int) = if (values.size == 1) size.width / 2 else i * size.width / (values.size - 1)
            fun y(v: Int) = if (hi == lo) size.height / 2 else size.height - pad - (v - lo) / (hi - lo) * (size.height - 2 * pad)
            drawBand(band, ::x, ::y)
            midline?.let {
                drawLine(Surface2, Offset(0f, y(it)), Offset(size.width, y(it)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 12f)))
            }
            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            runs(values.map { it != null }).forEach { run ->
                if (run.size == 1) {
                    drawCircle(color, 2.5.dp.toPx(), Offset(x(run[0]), y(values[run[0]]!!)))
                } else {
                    drawPath(
                        Path().apply {
                            run.forEachIndexed { k, i ->
                                if (k ==
                                    0
                                ) {
                                    moveTo(x(i), y(values[i]!!))
                                } else {
                                    lineTo(x(i), y(values[i]!!))
                                }
                            }
                        },
                        color,
                        style = stroke,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawBand(band: List<ClosedRange<Int>?>, x: (Int) -> Float, y: (Int) -> Float) {
    val half = if (band.size > 1) size.width / (band.size - 1) / 2 else size.width / 2
    runs(band.map { it != null }).forEach { run ->
        // A lone day has no neighbour to join to, so it becomes a one-day-wide block.
        val xs = if (run.size == 1) listOf((x(run[0]) - half).coerceAtLeast(0f), (x(run[0]) + half).coerceAtMost(size.width)) else run.map(x)
        val his = if (run.size == 1) List(2) { band[run[0]]!!.endInclusive } else run.map { band[it]!!.endInclusive }
        val los = if (run.size == 1) List(2) { band[run[0]]!!.start } else run.map { band[it]!!.start }
        val path = Path().apply {
            xs.forEachIndexed { k, px -> if (k == 0) moveTo(px, y(his[k])) else lineTo(px, y(his[k])) }
            xs.indices.reversed().forEach { k -> lineTo(xs[k], y(los[k])) }
            close()
        }
        drawPath(path, BandColor)
    }
}

/** Indices of each maximal stretch of consecutive true entries. */
internal fun runs(present: List<Boolean>): List<List<Int>> {
    val out = mutableListOf<MutableList<Int>>()
    present.forEachIndexed { i, p ->
        if (p) {
            if (i > 0 && present[i - 1]) out.last().add(i) else out.add(mutableListOf(i))
        }
    }
    return out
}

@Composable
private fun ChartBox(hasData: Boolean, height: androidx.compose.ui.unit.Dp, description: String, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }, Alignment.Center) {
        if (hasData) content() else Text("No data in this range", fontSize = 12.sp, color = TextMuted)
    }
}
