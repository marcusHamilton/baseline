package dev.baseline.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.theme.Bg
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.StrainBlue
import dev.baseline.ui.theme.StrainBlueLight
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.theme.TextPrimary
import dev.baseline.update.UpdateState
import dev.baseline.update.megabytes
import dev.baseline.update.percentOf

/** The one line that says where an offered update is; shared by Today's banner and Settings' App card. */
fun updateHeadline(state: UpdateState.Offer): String {
    val v = state.release.version
    return when (state) {
        is UpdateState.Available -> "Baseline $v is available."
        is UpdateState.NeedsPermission -> "To update, allow Baseline to install apps."
        is UpdateState.Downloading -> "Downloading Baseline $v… ${percentOf(state.done, state.total)} %"
        is UpdateState.Ready -> "Baseline $v is ready to install."
        is UpdateState.Installing -> "Installing Baseline $v…"
        is UpdateState.Failed -> state.message
    }
}

/** The label on the one button ([dev.baseline.update.Updater.primary]); null while the system installer has it. */
fun updateButton(state: UpdateState.Offer): String? = when (state) {
    is UpdateState.Available -> "Update (${megabytes(state.release.apkSize)} MB)"
    is UpdateState.NeedsPermission -> "Allow"
    is UpdateState.Downloading -> "Cancel"
    is UpdateState.Ready -> "Install"
    is UpdateState.Installing -> null
    is UpdateState.Failed -> "Retry"
}

/**
 * Today's update notice, the first thing on the screen: a slim full-width blue-tinted strip with a download glyph, not alarming. While
 * downloading, the blue fill sweeps across it with the progress. Dismissing it hides this release only.
 */
@Composable
fun UpdateBanner(state: UpdateState.Offer, onPrimary: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val target = if (state is UpdateState.Downloading) percentOf(state.done, state.total) / 100f else 0f
    val fill by animateFloatAsState(target, label = "update-fill")
    Row(
        modifier.fillMaxWidth().background(Card)
            .drawBehind { drawRect(StrainBlue.copy(alpha = 0.3f), size = Size(size.width * fill, size.height)) }
            .background(Brush.horizontalGradient(listOf(StrainBlue.copy(alpha = 0.14f), Color.Transparent)))
            .drawBehind { drawLine(StrainBlue.copy(alpha = 0.35f), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx()) }
            .then(if (state is UpdateState.Downloading) Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) } else Modifier)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadGlyph()
        Text(
            updateHeadline(state),
            Modifier.weight(1f).padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp).updateAnnouncements(state),
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
        )
        updateButton(state)?.let { label -> BannerButton(label, onPrimary) }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clearAndSetSemantics { contentDescription = "Hide this update" },
        ) { Text("✕", color = TextMuted, fontSize = 14.sp) }
    }
}

/** A small pill whose tap area is still 48 dp tall (spec §9); the visible part is 32 dp. */
@Composable
private fun BannerButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            label,
            Modifier.clip(RoundedCornerShape(50)).background(TextPrimary).padding(horizontal = 12.dp, vertical = 6.dp),
            color = Bg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A downward arrow over a tray in a tinted disc; the banner's only picture, so it is hidden from TalkBack. */
@Composable
private fun DownloadGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier.size(28.dp).clearAndSetSemantics { }) {
        drawCircle(StrainBlue.copy(alpha = 0.2f))
        val c = center
        val u = size.minDimension / 40f
        val stroke = Stroke(width = 2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arrow = Path().apply {
            moveTo(c.x, c.y - 8f * u)
            lineTo(c.x, c.y + 4f * u)
            moveTo(c.x - 5f * u, c.y - 1f * u)
            lineTo(c.x, c.y + 4f * u)
            lineTo(c.x + 5f * u, c.y - 1f * u)
        }
        val tray = Path().apply {
            moveTo(c.x - 8f * u, c.y + 9f * u)
            lineTo(c.x + 8f * u, c.y + 9f * u)
        }
        drawPath(arrow, StrainBlueLight, style = stroke)
        drawPath(tray, StrainBlueLight, style = stroke)
    }
}

/**
 * The headline speaks its state changes (available, ready, failed…) but not each percent of a download: the progress
 * bar carries that, so TalkBack isn't flooded with 100 announcements.
 */
fun Modifier.updateAnnouncements(state: UpdateState.Offer): Modifier = if (state is UpdateState.Downloading) {
    this
} else {
    semantics {
        liveRegion =
            LiveRegionMode.Polite
    }
}

@Composable
fun UpdateProgress(state: UpdateState.Downloading, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { percentOf(state.done, state.total) / 100f },
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Download ${percentOf(state.done, state.total)} percent" },
        color = StrainBlue,
        trackColor = Surface2,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
