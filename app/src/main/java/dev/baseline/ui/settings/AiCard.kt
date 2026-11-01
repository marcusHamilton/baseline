package dev.baseline.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiMode
import dev.baseline.ai.ModeOption
import dev.baseline.ai.engine.NanoStatus
import dev.baseline.ui.theme.Outline
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.SleepPurple
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import kotlinx.coroutines.delay
import java.util.Locale

private const val HINT = "Add an API key for better answers to training and long-term questions."
private const val CLEARED_MS = 2_000L
private const val CONFIRM_MS = 4_000L

private fun modeLabel(mode: AiMode) = when (mode) {
    AiMode.OFF -> "Off"
    AiMode.ON_DEVICE -> "On-device only"
    AiMode.CLOUD -> "Cloud only"
    AiMode.HYBRID -> "Hybrid"
}

/** The stored model id without its vendor prefix: "deepseek/deepseek-v4.1-flash" shows as "deepseek-v4.1-flash" (spec §3.2). */
private fun shortModel(id: String) = id.substringAfterLast('/')

/** The AI assistant card (spec: AI assistant §3.2): mode, Nano, the cloud key, a hint, the privacy line and "Clear AI insights". */
@Composable
fun AiCard(
    state: AiSettingsState,
    onMode: (AiMode) -> Unit,
    onDownload: () -> Unit,
    onAddKey: () -> Unit,
    onChangeModel: () -> Unit,
    onReplaceKey: () -> Unit,
    onRemove: () -> Unit,
    onClearInsights: () -> Unit,
) {
    // "Remove key?" is armed by the first tap and disarmed by a timeout or a tap on anything else in the card.
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(CONFIRM_MS)
            confirming = false
        }
    }
    val disarm: () -> Unit = { confirming = false }
    SettingsCard("AI ASSISTANT") {
        Text("Mode", fontSize = 14.sp, color = TextBody)
        Column {
            state.options.forEach { option ->
                ModeRow(option, option.mode == state.mode) {
                    disarm()
                    onMode(it)
                }
            }
        }
        if (state.nanoKnown) {
            Text("On-device", fontSize = 14.sp, color = TextBody)
            NanoRow(state.nano) {
                disarm()
                onDownload()
            }
        }
        Text("API key", fontSize = 14.sp, color = TextBody)
        when (val key = state.key) {
            AiKeyStatus.Missing -> OutlinedButton(
                onClick = {
                    disarm()
                    onAddKey()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                border = BorderStroke(1.dp, Outline),
            ) { Text("Add API key", fontWeight = FontWeight.SemiBold) }

            is AiKeyStatus.Set -> KeyRows(
                key,
                confirming,
                onChangeModel = {
                    disarm()
                    onChangeModel()
                },
                onReplaceKey = {
                    disarm()
                    onReplaceKey()
                },
                onRemove = {
                    confirming = false
                    onRemove()
                },
                onArm = { confirming = true },
            )
        }
        if (state.showHint) Text(HINT, fontSize = 13.sp, lineHeight = 19.sp, color = TextBody)
        (state.key as? AiKeyStatus.Set)?.let { key ->
            Text(
                "Cloud requests send this page's numbers, and any history the model looks up, to ${key.host}. Never GPS, routes or names. Not medical advice.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = TextMuted,
            )
        }
        ClearInsights {
            disarm()
            onClearInsights()
        }
    }
}

@Composable
private fun ModeRow(option: ModeOption, selected: Boolean, onMode: (AiMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai-mode-${option.mode}")
            .selectable(selected, enabled = option.enabled, role = Role.RadioButton) { onMode(option.mode) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected,
            onClick = null,
            enabled = option.enabled,
            colors = RadioButtonDefaults.colors(selectedColor = SleepPurple, unselectedColor = TextMuted),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(modeLabel(option.mode), fontSize = 14.sp, color = if (option.enabled) TextBody else TextMuted)
            option.reason?.let { Text(it, fontSize = 12.sp, color = TextMuted) }
        }
    }
}

@Composable
private fun NanoRow(nano: NanoStatus, onDownload: () -> Unit) {
    when (nano) {
        is NanoStatus.Ready -> Text("Gemini Nano (${nano.modelName}) · Ready · ${"%,d".format(Locale.US, nano.tokenLimit)}-token window", fontSize = 14.sp)

        NanoStatus.Downloadable -> {
            OutlinedButton(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                border = BorderStroke(1.dp, Outline),
            ) { Text("Download", fontWeight = FontWeight.SemiBold) }
            Text("Uses Wi-Fi; may take a few minutes", fontSize = 12.sp, color = TextMuted)
        }

        is NanoStatus.Downloading -> {
            val bar = Modifier.fillMaxWidth().semantics { contentDescription = "Downloading Gemini Nano" }
            val progress = nano.progress
            if (progress == null) {
                LinearProgressIndicator(bar, color = SleepPurple, trackColor = Surface2)
            } else {
                LinearProgressIndicator({ progress.coerceIn(0f, 1f) }, bar, color = SleepPurple, trackColor = Surface2)
            }
        }

        NanoStatus.Unavailable -> Text("Not supported on this phone", fontSize = 14.sp, color = TextMuted)

        is NanoStatus.Resting -> Text("On-device AI has reached today's limit.", fontSize = 14.sp, color = TextMuted)
    }
}

@Composable
private fun KeyRows(key: AiKeyStatus.Set, confirming: Boolean, onChangeModel: () -> Unit, onReplaceKey: () -> Unit, onRemove: () -> Unit, onArm: () -> Unit) {
    // A second tap on "Remove key?" deletes the key; there's no undo, so it takes two deliberate taps.
    InfoRow("Host", key.host)
    InfoRow("Model", shortModel(key.model))
    if (key.toolsSupported) {
        Text("✓ Tools", fontSize = 13.sp, color = RecoveryGreen)
    } else {
        Text("⚠ No tools: answers use page data only", fontSize = 13.sp, color = RecoveryAmber)
    }
    if (key.invalid) {
        Text(
            "Key rejected. Replace it to keep using the cloud.",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            fontSize = 14.sp,
            color = RecoveryRed,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onChangeModel, Modifier.heightIn(min = 48.dp), border = BorderStroke(1.dp, Outline)) {
            Text("Change model", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = onReplaceKey, Modifier.heightIn(min = 48.dp), border = BorderStroke(1.dp, Outline)) {
            Text("Replace key", fontWeight = FontWeight.SemiBold)
        }
        TextButton(
            onClick = if (confirming) onRemove else onArm,
            Modifier.heightIn(min = 48.dp),
        ) { Text(if (confirming) "Remove key?" else "Remove", color = RecoveryRed, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ClearInsights(onClear: () -> Unit) {
    var cleared by remember { mutableStateOf(false) }
    LaunchedEffect(cleared) {
        if (cleared) {
            delay(CLEARED_MS)
            cleared = false
        }
    }
    OutlinedButton(
        onClick = {
            cleared = true
            onClear()
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
        border = BorderStroke(1.dp, Outline),
    ) { Text(if (cleared) "Cleared" else "Clear AI insights", fontWeight = FontWeight.SemiBold) }
}
