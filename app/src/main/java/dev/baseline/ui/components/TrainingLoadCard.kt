package dev.baseline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.scores.FormState
import dev.baseline.scores.WeekBudget
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.StrainBlue
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.today.TrainingLoadUi
import kotlin.math.abs
import kotlin.math.roundToInt

/** The top of the budget bar, in percent of a normal week (fitness-fatigue-form spec §3.4). */
private const val BAND_TOP = 130f

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

fun formStateMeaning(s: FormState): String? = when (s) {
    FormState.OVERREACHING -> "Fatigue is well above your fitness. Back off for a few days."
    FormState.PRODUCTIVE -> "You're building fitness at a pace you can absorb."
    FormState.MAINTAINING -> "Load matches your fitness."
    FormState.FRESH -> "You're rested. A good time for a hard session or a race."
    FormState.DETRAINING -> "You've trained well under your norm for a while; fitness is slipping."
    FormState.CALIBRATING, FormState.NO_RECENT_TRAINING -> null
}

private fun hardDays(n: Int, about: String) = "room for $about$n hard day${if (n == 1) "" else "s"}"

/** What follows the week's percentage, or null when only the percentage is shown (week over, or no calibration). */
private fun budgetTail(b: WeekBudget, about: String): String? = when {
    b.overBand -> "past your safe range"
    b.daysLeft == 0 || b.hardDaysLeft == null -> null
    b.hardDaysLeft == 0 -> "easy days from here"
    else -> hardDays(b.hardDaysLeft, about)
}

/** "62% of a normal week · room for ~2 hard days" and its variants (spec §4). */
fun budgetLine(b: WeekBudget): String = listOfNotNull("${b.pct}% of a normal week", budgetTail(b, "~")).joinToString(" · ")

private fun calibratingText(daysLeft: Int?) = "Calibrating · $daysLeft day${if (daysLeft == 1) "" else "s"} to go"

/** The bar's single TalkBack description; the expand action and state come from the click label and state description. */
fun trainingLoadDescription(ui: TrainingLoadUi): String {
    val d = ui.day
    if (d.state == FormState.CALIBRATING) {
        return "Training load: Calibrating, ${d.calibratingDaysLeft} day${if (d.calibratingDaysLeft == 1) "" else "s"} to go."
    }
    val form = d.formPct?.let {
        ", form ${if (it < 0) {
            "minus "
        } else if (it > 0) {
            "plus "
        } else {
            ""
        }}${abs(it)} percent"
    } ?: ""
    val week = ui.budget?.let { b -> " " + listOfNotNull("${b.pct} percent of a normal week", budgetTail(b, "about ")).joinToString(", ") + "." } ?: ""
    return "Training load: ${formStateLabel(d.state)}$form.$week"
}

/** Full-width bar under Body Battery: state and week budget; tapping shows form, fitness and fatigue. */
@Composable
fun TrainingLoadCard(ui: TrainingLoadUi, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val d = ui.day
    val b = ui.budget
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
            .clickable(
                onClickLabel = if (ui.expanded) "Hide training load details" else "Show training load details",
                role = Role.Button,
                onClick = onToggle,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = trainingLoadDescription(ui)
                stateDescription = if (ui.expanded) "Expanded" else "Collapsed"
            }
            .heightIn(min = 48.dp).padding(16.dp),
        Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Text("Training load", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextMuted)
            Text(
                if (d.state == FormState.CALIBRATING) calibratingText(d.calibratingDaysLeft) else formStateLabel(d.state),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = formStateColor(d.state),
            )
        }
        if (b != null) {
            Text(budgetLine(b), fontSize = 13.sp, color = if (b.overBand) RecoveryAmber else TextBody)
            BudgetBar(b)
        }
        if (ui.expanded && d.formPct != null) {
            Text("Form ${formPctText(d.formPct)}: ${formStateMeaning(d.state)}", fontSize = 13.sp, color = TextBody)
            Text("Fitness ${d.fitness.roundToInt()} load · your 6-week average", fontSize = 13.sp, color = TextBody)
            Text("Fatigue ${d.fatigue.roundToInt()} load · your last-week average", fontSize = 13.sp, color = TextBody)
        }
    }
}

/** A track from 0 to 130 % of a normal week, filled to the week so far, with a tick at 100 %. */
@Composable
private fun BudgetBar(b: WeekBudget) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(Surface2)) {
            val fill = (b.pct.coerceIn(0, BAND_TOP.toInt()) / BAND_TOP)
            if (fill > 0f) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(5.dp)).background(if (b.overBand) RecoveryAmber else StrainBlue))
            }
        }
        Box(Modifier.offset(x = maxWidth * (100f / BAND_TOP)).width(1.dp).fillMaxHeight().background(TextMuted))
    }
}
