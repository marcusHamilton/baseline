package dev.baseline.ui.components

import androidx.compose.ui.graphics.Color
import dev.baseline.scores.FormState
import dev.baseline.scores.TrainingLoadDay
import dev.baseline.scores.WeekBudget
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.StrainBlue
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.today.TrainingLoadUi

fun formStateLabel(s: FormState): String = when (s) {
    FormState.CALIBRATING -> "Calibrating"
    FormState.NO_RECENT_TRAINING -> "No recent training"
    FormState.OVERREACHING -> "Overreaching"
    FormState.PRODUCTIVE -> "Productive"
    FormState.MAINTAINING -> "Maintaining"
    FormState.FRESH -> "Fresh"
    FormState.DETRAINING -> "Detraining"
}

fun formStateColor(s: FormState): Color = when (s) {
    FormState.OVERREACHING -> RecoveryRed
    FormState.PRODUCTIVE -> RecoveryGreen
    FormState.FRESH -> StrainBlue
    FormState.DETRAINING, FormState.NO_RECENT_TRAINING -> RecoveryAmber
    FormState.MAINTAINING, FormState.CALIBRATING -> TextMuted
}

/** "−18%" (a real minus sign), "+5%", "0%". */
fun formPctText(pct: Int): String = when {
    pct < 0 -> "−${-pct}%"
    pct > 0 -> "+$pct%"
    else -> "0%"
}

/** The Recovery card's tag (fitness-fatigue-form spec §4): "Productive week", or the state alone before there is one. */
fun weekTag(d: TrainingLoadDay): String = when (d.state) {
    FormState.CALIBRATING, FormState.NO_RECENT_TRAINING -> formStateLabel(d.state)
    else -> "${formStateLabel(d.state)} week"
}

/** "This week: 67% of normal · ~3 hard days left", and the variants for a full week, a past week or no calibration. */
fun weekLine(b: WeekBudget): String {
    val tail = when {
        b.overBand || b.daysLeft == 0 || b.hardDaysLeft == null -> null
        b.hardDaysLeft == 0 -> "easy days from here"
        else -> "~${b.hardDaysLeft} hard day${if (b.hardDaysLeft == 1) "" else "s"} left"
    }
    return listOfNotNull("This week: ${b.pct}% of normal", tail).joinToString(" · ")
}

/** What a week that needs attention says on the Recovery card; null for a normal week, so the card stays quiet. */
fun weekCallout(ui: TrainingLoadUi): String? = when {
    ui.day.state == FormState.OVERREACHING -> "Fatigue is well above your fitness. Keep today easy."
    ui.budget?.overBand == true -> "You're past your safe range for the week. Keep the rest easy."
    ui.day.state == FormState.FRESH -> "You're rested. A good day for a hard session."
    ui.day.state == FormState.DETRAINING -> "You've trained well under your norm for a while; fitness is slipping."
    else -> null
}

/** The callout's (and the tag's) colour; the tag is muted when there is no callout. */
fun weekCalloutColor(ui: TrainingLoadUi): Color =
    if (ui.day.state != FormState.OVERREACHING && ui.budget?.overBand == true) RecoveryAmber else formStateColor(ui.day.state)
