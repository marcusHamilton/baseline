package dev.baseline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.baseline.scores.RecoveryZone
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.Surface2

private val MIN_VALUE_SIZE = 10.sp

fun zoneColor(zone: RecoveryZone): Color = when (zone) {
    RecoveryZone.GREEN -> RecoveryGreen
    RecoveryZone.AMBER -> RecoveryAmber
    RecoveryZone.RED -> RecoveryRed
}

/**
 * A ring filled clockwise from the top to [fraction] (null = no data: empty track and "—").
 * [description] is the TalkBack label; the number inside is hidden from it, but [note] stays readable.
 * [valueSize] is the number's size in sp at the default font scale; at a larger font scale it shrinks until the number fits the ring.
 */
@Composable
fun Dial(
    fraction: Float?,
    value: String,
    suffix: String,
    color: Color,
    size: Dp,
    strokeWidth: Dp,
    valueSize: Int,
    description: String,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Box(modifier.size(size).semantics { contentDescription = description }, Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round)
            val inset = strokeWidth.toPx() / 2
            val arc = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            drawArc(Surface2, 0f, 360f, false, Offset(inset, inset), arc, style = stroke)
            val sweep = (fraction?.coerceIn(0f, 1f) ?: 0f) * 360f
            if (sweep > 0f) drawArc(color, -90f, sweep, false, Offset(inset, inset), arc, style = stroke)
        }
        Column(Modifier.widthIn(max = size - strokeWidth * 2), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(
                buildAnnotatedString {
                    append(value)
                    if (suffix.isNotEmpty()) withStyle(SpanStyle(fontSize = 0.5.em)) { append(suffix) }
                },
                Modifier.semantics { hideFromAccessibility() }, // the dial's description already says the value
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold,
                // Shrinks only when the number would overflow the ring (a large font scale); identical to valueSize.sp otherwise.
                autoSize = TextAutoSize.StepBased(minFontSize = MIN_VALUE_SIZE, maxFontSize = valueSize.sp, stepSize = 0.5.sp),
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            if (note != null) Text(note, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
        }
    }
}
