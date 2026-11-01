package dev.baseline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.scores.FormState
import dev.baseline.scores.TrainingLoad
import dev.baseline.scores.WeekBudget
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.StrainBlue
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.theme.TextPrimary
import dev.baseline.ui.today.TrainingLoadUi
import kotlin.math.roundToInt

const val WEEK_BAR_TRACK_TAG = "week-bar-track"
const val WEEK_BAR_FILL_TAG = "week-bar-fill"
const val WEEK_BAR_NORMAL_TAG = "week-bar-normal"
const val WEEK_BAR_SAFE_TAG = "week-bar-safe"

/** The fill's colour, exposed so tests can check it without reading pixels. */
val WeekBarFillColor = SemanticsPropertyKey<Color>("WeekBarFillColor")
private var SemanticsPropertyReceiver.weekBarFillColor by WeekBarFillColor

/** The track runs from 0 to this many percent of a normal week; the model's safe limit and a normal week are marked on it. */
private const val SCALE_MAX_PCT = 200
private const val NORMAL_PCT = 100
private val SAFE_PCT = (TrainingLoad.SAFE_BAND * 100).roundToInt()
private val TRACK_HEIGHT = 8.dp
private val TICK_WIDTH = 1.5.dp
private val TICK_HEIGHT = 14.dp
private const val DASH = "—"

/** How far along the 0–200 % track [pct] reaches: 0 to 1, capped at the end. */
fun weekBarFraction(pct: Int): Float = pct.coerceIn(0, SCALE_MAX_PCT) / SCALE_MAX_PCT.toFloat()

private val NormalAt = weekBarFraction(NORMAL_PCT)
private val SafeAt = weekBarFraction(SAFE_PCT)

private fun fillColor(b: WeekBudget) = if (b.overBand) RecoveryAmber else StrainBlue

/** What stands on the right when there is no budget to draw: the state while it explains why, else a dash. */
private fun noBudgetText(ui: TrainingLoadUi?): String = when (ui?.day?.state) {
    FormState.CALIBRATING, FormState.NO_RECENT_TRAINING -> formStateLabel(ui.day.state)
    else -> DASH
}

/**
 * The muted line under the track: "Productive week · about 3 hard days left before you overreach.". It says nothing about hard days
 * when [weekLine] wouldn't (past the safe range, the week's last day, no strain calibration).
 */
fun weekBarLine(ui: TrainingLoadUi): String {
    val tail = ui.budget?.let(::weekHardDays)?.let {
        if (it == 0) "easy days from here" else "about $it hard day${if (it == 1) "" else "s"} left before you overreach"
    }
    return weekTag(ui.day) + (tail?.let { " · $it." } ?: "")
}

/** "Week load 67% of normal. Productive range up to 130%. About 3 hard days left." */
fun weekBarDescription(ui: TrainingLoadUi?): String {
    val b = ui?.budget
    if (b == null) return noBudgetText(ui).let { if (it == DASH) "Week load unavailable." else "Week load: $it." }
    val tail = weekHardDays(b)?.let { if (it == 0) " Easy days from here." else " About $it hard day${if (it == 1) "" else "s"} left." } ?: ""
    return "Week load ${b.pct}% of normal. Productive range up to $SAFE_PCT%." + (if (b.overBand) " Past it." else "") + tail
}

/**
 * This week's load against a normal week (fitness-fatigue-form spec §3.4 and §5): [label] and "67% of normal" over a track that runs
 * to 200 %, with a tick at a normal week and one at the safe limit, the stretch between them tinted green. Without a budget (still
 * calibrating, no recent training) only the label and the state show. One TalkBack node.
 */
@Composable
fun WeekLoadBar(ui: TrainingLoadUi?, label: String, modifier: Modifier = Modifier) {
    val budget = ui?.budget
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = weekBarDescription(ui) },
        Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Bottom) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.96.sp, color = TextMuted)
            if (budget != null) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = TextPrimary)) {
                            append("${budget.pct}%")
                        }
                        withStyle(SpanStyle(fontSize = 13.sp, color = TextBody)) { append(" of normal") }
                    },
                )
            } else {
                Text(noBudgetText(ui), fontSize = 13.sp, color = TextBody)
            }
        }
        if (ui != null && budget != null) {
            Track(budget)
            Text(weekBarLine(ui), fontSize = 12.sp, color = TextMuted)
        }
    }
}

@Composable
private fun Track(budget: WeekBudget) {
    val fraction = weekBarFraction(budget.pct)
    val color = fillColor(budget)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(TICK_HEIGHT), Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(TRACK_HEIGHT).clip(RoundedCornerShape(TRACK_HEIGHT / 2)).background(Surface2).testTag(WEEK_BAR_TRACK_TAG)) {
                // The productive stretch, from a normal week to the safe limit, under the fill.
                Row(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(NormalAt))
                    Box(Modifier.weight(SafeAt - NormalAt).fillMaxHeight().background(RecoveryGreen.copy(alpha = 0.28f)))
                    Spacer(Modifier.weight(1f - SafeAt))
                }
                if (fraction > 0f) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(TRACK_HEIGHT / 2)).background(color)
                            .testTag(WEEK_BAR_FILL_TAG).semantics { weekBarFillColor = color },
                    )
                }
            }
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(NormalAt))
                Box(Modifier.width(TICK_WIDTH).fillMaxHeight().background(TextMuted).testTag(WEEK_BAR_NORMAL_TAG))
                Spacer(Modifier.weight(SafeAt - NormalAt))
                Box(Modifier.width(TICK_WIDTH).fillMaxHeight().background(RecoveryGreen).testTag(WEEK_BAR_SAFE_TAG))
                Spacer(Modifier.weight(1f - SafeAt))
            }
        }
        // "normal" ends at its tick and "safe limit" starts at its own, so the two never run into each other.
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(NormalAt), Alignment.CenterEnd) {
                Text("normal", Modifier.padding(end = 4.dp), fontSize = 11.sp, color = TextMuted, maxLines = 1)
            }
            Box(Modifier.weight(SafeAt - NormalAt), Alignment.CenterStart) {
                Text(
                    "safe limit",
                    Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(start = 4.dp),
                    fontSize = 11.sp,
                    color = RecoveryGreen,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Spacer(Modifier.weight(1f - SafeAt))
        }
    }
}
