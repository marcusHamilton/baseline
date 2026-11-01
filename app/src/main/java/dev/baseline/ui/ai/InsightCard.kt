package dev.baseline.ui.ai

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.R
import dev.baseline.ai.context.AskScope
import dev.baseline.ai.context.subject
import dev.baseline.ai.engine.EngineKind
import dev.baseline.ai.insight.InsightState
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.ChipActive
import dev.baseline.ui.theme.SleepPurple
import dev.baseline.ui.theme.StrainBlue
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

private val BodyStyle = TextStyle(fontSize = 12.5.sp, lineHeight = 18.75.sp, color = TextBody)
private const val BODY_LINES = 3
private val TouchTarget = 48.dp
private val CONTENT_GAP = 6.dp

/**
 * One page's card, wired to its [InsightViewModel]: collects the insight for [scope] while the page is on screen, and names the scope in
 * the "Ask about …" link. [today] follows midnight, so "last night" turns into "the night of …" without the page reopening.
 */
@Composable
fun InsightSlot(vm: InsightViewModel, scope: AskScope, today: StateFlow<LocalDate>, onAsk: () -> Unit, modifier: Modifier = Modifier) {
    val state by remember(vm, scope) { vm.state(scope) }.collectAsStateWithLifecycle()
    val now by today.collectAsStateWithLifecycle()
    InsightCard(state, scope.subject(now), onAsk, vm::retry, modifier)
}

/**
 * The AI insight card (spec §3.8) for Sleep and an activity; Today puts its insight inside the Recovery card instead. [subject] is the scope
 * as it reads after "Ask about" (`AskScope.subject`). Hidden emits nothing at all, not even a spacer, so callers put their own padding in [modifier].
 */
@Composable
fun InsightCard(state: InsightState, subject: String, onAsk: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        InsightState.Hidden -> Unit

        is InsightState.Generating ->
            Shell(modifier.semantics(mergeDescendants = true) { contentDescription = "Insight loading" }) {
                Header()
                Body(state.partial)
                // The footer's room, so the card is already its final height.
                Spacer(Modifier.height(TouchTarget))
            }

        is InsightState.Ready -> {
            val made = if (state.label.kind == EngineKind.NANO) "Made on-device." else "Made in the cloud."
            // Insights end in a full stop already; adding one read "it.. Made in the cloud".
            val text = state.text.trimEnd().let { if (it.endsWith('.') || it.endsWith('!') || it.endsWith('?')) it else "$it." }
            Shell(
                modifier
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Insight: $text $made"
                        customActions = listOf(
                            CustomAccessibilityAction("Ask about $subject") {
                                onAsk()
                                true
                            },
                        )
                    }
                    .clickable(role = Role.Button, onClick = onAsk),
            ) {
                Header()
                Text(state.text, style = BodyStyle)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = TouchTarget).testTag("insight-footer"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(state.label.text, Modifier.weight(1f), color = TextMuted, fontSize = 11.sp)
                    Text(
                        "Ask about $subject ›",
                        Modifier.heightIn(min = TouchTarget).wrapContentHeight(Alignment.CenterVertically),
                        color = SleepPurple,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        is InsightState.Failed ->
            Shell(modifier) {
                Column(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = "Insight: ${state.message}" },
                    Arrangement.spacedBy(CONTENT_GAP),
                ) {
                    Header()
                    Text(state.message, style = BodyStyle.copy(color = TextMuted))
                }
                Text(
                    "Retry",
                    Modifier.heightIn(min = TouchTarget).widthIn(min = TouchTarget).clickable(role = Role.Button, onClick = onRetry)
                        .wrapContentHeight(Alignment.CenterVertically),
                    color = StrainBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
    }
}

@Composable
private fun Shell(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(12.dp),
        Arrangement.spacedBy(CONTENT_GAP),
    ) { content() }
}

@Composable
private fun Header() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_sparkle), contentDescription = null, Modifier.size(13.dp), tint = SleepPurple)
        Text("INSIGHT", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.96.sp, color = SleepPurple)
    }
}

/**
 * Three lines tall whatever is in it. While nothing has streamed in yet, three shimmer bars (96%, 88% and 54% wide) sit over the
 * space; once text arrives it replaces them, and stays within the same three lines.
 */
@Composable
private fun Body(partial: String) {
    Box {
        Text(partial, Modifier.fillMaxWidth(), style = BodyStyle, minLines = BODY_LINES)
        if (partial.isBlank()) Bars(Modifier.matchParentSize())
    }
}

@Composable
private fun BoxScope.Bars(modifier: Modifier) {
    val animate = !reducedMotion()
    val phase = if (animate) {
        rememberInfiniteTransition(label = "insight-shimmer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_400, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
    } else {
        null
    }
    // One line-height row per bar, so each bar sits where its line of text will.
    val line = with(LocalDensity.current) { BodyStyle.lineHeight.toDp() }
    Column(modifier.testTag(if (animate) "insight-shimmer" else "insight-bars-static")) {
        listOf(0.96f, 0.88f, 0.54f).forEach { fraction ->
            Box(Modifier.fillMaxWidth().height(line), Alignment.CenterStart) {
                Box(
                    Modifier.fillMaxWidth(fraction).height(8.dp).clip(RoundedCornerShape(4.dp)).background(ChipActive).drawBehind {
                        // Read in the draw phase, so the sweep redraws without recomposing.
                        val p = phase?.value ?: return@drawBehind
                        val start = size.width * (p * 2f - 0.5f)
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                                startX = start,
                                endX = start + size.width * 0.5f,
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** Animations off in developer options (or by a battery saver) is the system's reduced-motion switch: the bars stay still. */
@Composable
private fun reducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}
