package dev.baseline.ui.settings

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.baseline.ui.theme.ChipActive
import dev.baseline.ui.theme.Outline
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted

/** The light primary button with dark text. Shared by Settings' key card and onboarding, which are the same form. */
@Composable
internal fun primaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2F4F7), contentColor = Color(0xFF0E1013))

/** The "Create a key in Garmin Connect" link text. */
internal val LinkColor = Color(0xFF93C5FD)

/** Selected chip = the Trends range chip's fill with the accent text, so it never reads as bare green text. */
@Composable
internal fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = ChipActive,
    selectedLabelColor = RecoveryGreen,
    labelColor = TextBody,
    disabledSelectedContainerColor = ChipActive,
    disabledLabelColor = TextMuted,
)

@Composable
internal fun chipBorder(selected: Boolean, enabled: Boolean) =
    FilterChipDefaults.filterChipBorder(enabled, selected, borderColor = Outline, selectedBorderColor = Outline)
