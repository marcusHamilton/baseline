package dev.baseline.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.Outline
import dev.baseline.ui.theme.StrainBlue
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.update.UpdateState

/** The one line that says where an offered update is; shared by Today's banner and Settings' App card. */
fun updateHeadline(state: UpdateState.Offer): String {
    val v = state.release.version
    return when (state) {
        is UpdateState.Available -> "Baseline $v is available."
        is UpdateState.NeedsPermission -> "To update, allow Baseline to install apps."
        is UpdateState.Downloading -> "Downloading Baseline $v… ${percent(state.done, state.total)} %"
        is UpdateState.Ready -> "Baseline $v is ready to install."
        is UpdateState.Installing -> "Installing Baseline $v…"
        is UpdateState.Failed -> state.message
    }
}

/** The label on the one button ([dev.baseline.update.Updater.primary]); null while the system installer has it. */
fun updateButton(state: UpdateState.Offer): String? = when (state) {
    is UpdateState.Available -> "Update (${state.release.apkSize.toMegabytes()} MB)"
    is UpdateState.NeedsPermission -> "Allow"
    is UpdateState.Downloading -> "Cancel"
    is UpdateState.Ready -> "Install"
    is UpdateState.Installing -> null
    is UpdateState.Failed -> "Retry"
}

internal fun percent(done: Long, total: Long) = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0

/** Decimal megabytes, as Android's own storage screens count them. */
internal fun Long.toMegabytes() = (this + 500_000) / 1_000_000

/** Today's update notice: neutral, not alarming, under the key banner. Dismissing it hides this release only. */
@Composable
fun UpdateBanner(state: UpdateState.Offer, onPrimary: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).border(BorderStroke(1.dp, Outline), RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                updateHeadline(state),
                Modifier.weight(1f).padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = TextBody,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clearAndSetSemantics { contentDescription = "Hide this update" },
            ) { Text("✕", color = TextMuted, fontSize = 16.sp) }
        }
        if (state is UpdateState.Downloading) {
            UpdateProgress(state, Modifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp))
        }
        updateButton(state)?.let { label ->
            OutlinedButton(
                onClick = onPrimary,
                modifier = Modifier.padding(end = 10.dp, top = 4.dp).heightIn(min = 48.dp),
                border = BorderStroke(1.dp, Outline),
            ) { Text(label, fontWeight = FontWeight.SemiBold, color = StrainBlue) }
        }
    }
}

@Composable
fun UpdateProgress(state: UpdateState.Downloading, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { percent(state.done, state.total) / 100f },
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Download ${percent(state.done, state.total)} percent" },
        color = StrainBlue,
        trackColor = Surface2,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
