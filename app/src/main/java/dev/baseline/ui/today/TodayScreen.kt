package dev.baseline.ui.today

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.R
import dev.baseline.garmin.KeyStatus
import dev.baseline.ui.activity.NOTE_UNREADABLE
import dev.baseline.ui.components.ActivityCard
import dev.baseline.ui.components.BodyBatteryCard
import dev.baseline.ui.components.CircleButton
import dev.baseline.ui.components.Dial
import dev.baseline.ui.components.KeyBanner
import dev.baseline.ui.components.StatRow
import dev.baseline.ui.components.UpdateBanner
import dev.baseline.ui.components.zoneColor
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.Outline
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.SleepPurple
import dev.baseline.ui.theme.SleepPurpleLight
import dev.baseline.ui.theme.StrainBlue
import dev.baseline.ui.theme.StrainBlueLight
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.theme.TextPrimary
import dev.baseline.update.UpdateState
import dev.baseline.update.UpdateUi
import dev.baseline.update.Updater
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** Today: the permission prompt, pull-to-refresh and the sync line around the stateless [TodayContent]. */
@Composable
fun TodayScreen(
    vm: TodayViewModel,
    onOpenCalendar: () -> Unit,
    onOpenSleep: (LocalDate) -> Unit,
    onOpenActivity: (Long) -> Unit,
    zone: StateFlow<ZoneId>,
    updater: Updater? = null,
) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        // A "no" only means no progress notification; sync runs either way.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            vm.claimNotificationPrompt()
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val keyStatus by vm.keyStatus.collectAsStateWithLifecycle()
    val zoneNow by zone.collectAsStateWithLifecycle()
    val update = updater?.ui?.collectAsStateWithLifecycle()?.value
    PullToRefreshBox(refreshing, vm::refresh, Modifier.fillMaxSize()) {
        TodayContent(
            state, sync, vm::prevDay, vm::nextDay, onOpenCalendar, { onOpenSleep(state.date) }, onOpenActivity, vm::toggleBodyBattery,
            keyStatus, vm::toggleEdit, vm::moveRow, vm::toggleHidden, zoneNow,
            update, { updater?.primary() }, { tag -> updater?.dismiss(tag) },
        )
    }
}

@Composable
fun TodayContent(
    state: TodayUiState,
    syncLine: String?,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    onToggleBodyBattery: () -> Unit,
    keyStatus: KeyStatus? = null,
    onToggleEdit: () -> Unit = {},
    onMoveRow: (from: Int, to: Int) -> Unit = { _, _ -> },
    onToggleHidden: (String) -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
    update: UpdateUi? = null,
    onUpdate: () -> Unit = {},
    onDismissUpdate: (String) -> Unit = {},
) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(Rect.Zero) }
    Column(
        Modifier.fillMaxSize().testTag("screen-today").onGloballyPositioned {
            viewport = it.boundsInWindow()
        }.verticalScroll(scroll).padding(bottom = 16.dp),
    ) {
        (update?.state as? UpdateState.Offer)?.takeIf { update.showBanner }?.let { offer ->
            UpdateBanner(offer, onUpdate, { onDismissUpdate(offer.release.tag) }, Modifier)
        }
        Header(state, zone, onPrevDay, onNextDay, onOpenCalendar)
        KeyBanner(keyStatus, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (state.unreadable) {
            Text(
                NOTE_UNREADABLE,
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                fontSize = 13.sp,
                color = RecoveryAmber,
                textAlign = TextAlign.Center,
            )
        }
        syncLine?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 20.dp), fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center) }
        Dials(state, onOpenSleep)
        BodyBatteryCard(state.bodyBattery, onToggleBodyBattery, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), state.isToday)
        InsightCard(state, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
        if (state.activities.isNotEmpty()) {
            Section(if (state.isToday) "TODAY'S ACTIVITIES" else "ACTIVITIES") {
                state.activities.forEach { a -> ActivityCard(a, { onOpenActivity(a.id) }) }
            }
        }
        BaselineSection(state, scroll, viewport, onToggleEdit, onMoveRow, onToggleHidden)
    }
}

@Composable
private fun BaselineSection(
    state: TodayUiState,
    scroll: ScrollState,
    viewport: Rect,
    onToggleEdit: () -> Unit,
    onMoveRow: (Int, Int) -> Unit,
    onToggleHidden: (String) -> Unit,
) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp), Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(
                "VS. 30-DAY BASELINE",
                Modifier.semantics { heading() },
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 1.28.sp,
                color = TextMuted,
            )
            // 48 dp touch target around the 36 dp pill of the mockup.
            Box(
                Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggleEdit)
                    .semantics { stateDescription = if (state.editing) "Editing" else "Not editing" },
                Alignment.Center,
            ) {
                Text(
                    if (state.editing) "Done" else "Edit",
                    Modifier.height(
                        36.dp,
                    ).border(1.dp, Outline, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp).wrapContentHeight(Alignment.CenterVertically),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (!state.editing) {
            state.rows.forEach { StatRow(it) }
            return@Column
        }
        Text("Drag to reorder. Tap the eye to hide a row.", Modifier.padding(bottom = 4.dp), fontSize = 12.sp, color = TextMuted)
        val gap = with(LocalDensity.current) { 8.dp.toPx() }
        val speed = with(LocalDensity.current) { 10.dp.toPx() }
        val drag = remember { RowDrag() }
        var listTop by remember { mutableFloatStateOf(0f) }
        // The view model saves the new order asynchronously, so keep showing the dropped order until state.rows catches up;
        // otherwise the row would slide back to its old slot and jump when the saved order arrives.
        var dropped by remember { mutableStateOf<Triple<List<StatRowUi>, Int, Int>?>(null) }
        // One-shot: once the saved order has arrived, forget the drop so a later return to the same list is not overridden.
        if (dropped?.let { it.first != state.rows } == true) dropped = null
        val shownRows = dropped?.takeIf { it.first == state.rows }?.let { (base, f, t) -> base.toMutableList().apply { add(t, removeAt(f)) } } ?: state.rows
        val rows by rememberUpdatedState(shownRows)
        val savedRows by rememberUpdatedState(state.rows)
        val area by rememberUpdatedState(viewport)
        val from = drag.from
        // Page auto-scroll: while the dragged row is held past the viewport's top or bottom edge, scroll that way and
        // count the distance into the row's offset so it stays under the finger.
        LaunchedEffect(from) {
            if (from == null) return@LaunchedEffect
            while (true) {
                withFrameNanos { }
                val hs = rows.map { drag.heights[it.id] ?: 0f }
                if (from > hs.lastIndex) break
                val top = listTop + slotTops(hs, gap)[from] + drag.offset
                val dy = when {
                    top + hs[from] > area.bottom -> speed
                    top < area.top -> -speed
                    else -> 0f
                }
                if (dy != 0f) drag.scrolled += scroll.scrollBy(dy)
            }
        }
        val heights = shownRows.map { drag.heights[it.id] ?: 0f }
        val target = from?.takeIf { it <= heights.lastIndex }?.let { dragTarget(heights, gap, it, drag.offset) }
        Column(Modifier.onGloballyPositioned { listTop = it.positionInWindow().y }, Arrangement.spacedBy(8.dp)) {
            shownRows.forEachIndexed { index, row ->
                key(row.id) {
                    val dragging = index == from
                    EditableRow(
                        row, index, shownRows.lastIndex, onMoveRow, onToggleHidden,
                        offset = if (dragging) {
                            drag.offset
                        } else if (from != null && target != null) {
                            makeRoom(heights, gap, from, target, index)
                        } else {
                            0f
                        },
                        dragging = dragging,
                        active = from != null,
                        modifier = Modifier.onSizeChanged { drag.heights[row.id] = it.height.toFloat() },
                        handle = Modifier.pointerInput(row.id) {
                            detectDragGestures(
                                onDragStart = { drag.start(rows.indexOfFirst { it.id == row.id }) },
                                onDragEnd = {
                                    val f = drag.from
                                    val hs = rows.map { drag.heights[it.id] ?: 0f }
                                    if (f != null &&
                                        f <= hs.lastIndex
                                    ) {
                                        dragTarget(hs, gap, f, drag.offset).let {
                                            if (it !=
                                                f
                                            ) {
                                                dropped = Triple(savedRows, f, it)
                                                onMoveRow(f, it)
                                            }
                                        }
                                    }
                                    drag.reset()
                                },
                                onDragCancel = { drag.reset() },
                            ) { change, amount ->
                                change.consume()
                                drag.delta += amount.y
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditableRow(
    row: StatRowUi,
    index: Int,
    last: Int,
    onMove: (Int, Int) -> Unit,
    onToggleHidden: (String) -> Unit,
    offset: Float,
    dragging: Boolean,
    active: Boolean,
    modifier: Modifier,
    handle: Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    // Rows making room slide while a drag is on; the dragged row follows the finger exactly, and a drop snaps (no slide).
    val shown by animateFloatAsState(offset, label = "row-shift")
    Row(
        modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
            translationY = if (dragging) {
                offset
            } else if (active) {
                shown
            } else {
                0f
            }
        }
            .fillMaxWidth().alpha(if (row.hidden) 0.45f else 1f).dashedBorder(shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_drag_handle),
            contentDescription = "Drag to reorder ${row.label}",
            Modifier.size(width = 48.dp, height = 48.dp).then(handle).padding(horizontal = 16.dp, vertical = 16.dp)
                .semantics {
                    // Drag is hard with a screen reader or a keyboard; these do the same through accessibility actions.
                    customActions = listOfNotNull(
                        if (index > 0) {
                            CustomAccessibilityAction("Move up") {
                                onMove(index, index - 1)
                                true
                            }
                        } else {
                            null
                        },
                        if (index < last) {
                            CustomAccessibilityAction("Move down") {
                                onMove(index, index + 1)
                                true
                            }
                        } else {
                            null
                        },
                    )
                },
            tint = TextMuted,
        )
        StatRow(row, Modifier.weight(1f))
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClick = { onToggleHidden(row.id) })
                .semantics {
                    toggleableState = if (row.hidden) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
                },
            Alignment.Center,
        ) {
            Icon(
                painterResource(if (row.hidden) R.drawable.ic_eye_off else R.drawable.ic_eye),
                contentDescription = (if (row.hidden) "Show " else "Hide ") + row.label,
                Modifier.size(20.dp),
                tint = TextBody,
            )
        }
    }
}

private fun Modifier.dashedBorder(shape: RoundedCornerShape) = drawWithContent {
    drawContent()
    val r = shape.topStart.toPx(size, this)
    drawRoundRect(
        Outline,
        cornerRadius = CornerRadius(r),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp), Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            Modifier.padding(bottom = 4.dp).semantics { heading() },
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            letterSpacing = 1.28.sp,
            color = TextMuted,
        )
        content()
    }
}

private val DateLine = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

private fun subtitle(date: LocalDate, today: LocalDate, lastSynced: Instant?, zone: ZoneId): String = listOfNotNull(
    date.format(DateLine),
    lastSynced?.let {
        Formatters.synced(it, today, zone)
    },
).joinToString(" · ")

@Composable
private fun Header(state: TodayUiState, zone: ZoneId, onPrev: () -> Unit, onNext: () -> Unit, onCalendar: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        CircleButton(R.drawable.ic_chevron_left, "Previous day", true, onPrev)
        Column(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open calendar", role = Role.Button, onClick = onCalendar)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            Arrangement.spacedBy(2.dp),
            Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, Modifier.size(18.dp))
                Text(
                    if (state.isToday) "TODAY" else state.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US).uppercase(),
                    Modifier.semantics { heading() },
                    fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    letterSpacing = 0.88.sp,
                )
            }
            Text(subtitle(state.date, state.today, state.lastSynced, zone), fontSize = 13.sp, color = TextMuted)
        }
        CircleButton(R.drawable.ic_chevron_right, "Next day", !state.isToday, onNext)
    }
}

// The dials at their natural size, each as (diameter, stroke, value text size in sp).
private val SideDial = Triple(96.dp, 8.dp, 30)
private val MiddleDial = Triple(132.dp, 7.dp, 44)
private val DialRowPadding = 20.dp
private val MinTouchTarget = 48.dp

/** One factor for all three dials: 1 while [available] fits them side by side at natural size, else the share that fits. */
internal fun dialScale(available: Dp): Float {
    val natural = SideDial.first * 2 + MiddleDial.first
    return (available / natural).coerceIn(0.5f, 1f)
}

@Composable
private fun Dials(state: TodayUiState, onOpenSleep: () -> Unit) {
    val recovery = state.recovery
    val recoveryColor = recovery?.let { zoneColor(it.zone) } ?: TextMuted
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = dialScale(maxWidth - DialRowPadding * 2)

        // Whole dp, rounded down, so the three never add up to more than the row has.
        fun scaled(d: Dp) = if (scale >= 1f) d else floor(d.value * scale).dp
        val side = scaled(SideDial.first)
        val sideStroke = SideDial.second * scale
        val sideText = (SideDial.third * scale).roundToInt()
        val middle = scaled(MiddleDial.first)
        Row(
            Modifier.fillMaxWidth().padding(start = DialRowPadding, end = DialRowPadding, top = 20.dp, bottom = 8.dp),
            Arrangement.SpaceBetween,
            Alignment.Bottom,
        ) {
            Column(
                Modifier.width(side).heightIn(min = MinTouchTarget).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpenSleep),
                Arrangement.spacedBy(8.dp),
                Alignment.CenterHorizontally,
            ) {
                Dial(
                    state.sleepPct?.let { it / 100f },
                    state.sleepPct?.toString() ?: "—",
                    if (state.sleepPct != null) "%" else "",
                    SleepPurple,
                    side,
                    sideStroke,
                    sideText,
                    state.sleepPct?.let { "Sleep performance $it percent" } ?: "Sleep unavailable",
                )
                DialLabel("SLEEP", SleepPurpleLight)
            }
            Column(Modifier.width(middle), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
                Dial(
                    recovery?.let { it.score / 100f }, recovery?.score?.toString() ?: "—", if (recovery != null) "%" else "", recoveryColor,
                    middle, MiddleDial.second * scale, (MiddleDial.third * scale).roundToInt(),
                    recovery?.let { "Recovery ${it.score} percent" } ?: "Recovery unavailable",
                    note = if (recovery?.partial == true) "Partial" else null,
                )
                DialLabel("RECOVERY", recoveryColor)
            }
            Column(Modifier.width(side), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
                val strain = state.strain
                Dial(
                    strain?.let { (it / 21.0).coerceIn(0.0, 1.0).toFloat() },
                    strain?.let { "%.1f".format(Locale.US, it) } ?: "—",
                    "",
                    StrainBlue,
                    side,
                    sideStroke,
                    sideText,
                    strain?.let { "Strain ${"%.1f".format(Locale.US, it)} of 21" } ?: "Strain unavailable",
                )
                DialLabel("STRAIN", StrainBlueLight)
            }
        }
    }
}

@Composable
private fun DialLabel(text: String, color: Color) = Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.96.sp, color = color)

private fun targetText(r: ClosedFloatingPointRange<Double>) = "${r.start.roundToInt()} – ${r.endInclusive.roundToInt()}"

@Composable
private fun InsightCard(state: TodayUiState, modifier: Modifier = Modifier) {
    val recovery = state.recovery
    val color = recovery?.let { zoneColor(it.zone) } ?: Surface2
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), Arrangement.spacedBy(8.dp)) {
        if (state.loading) {
            Text("Loading…", fontSize = 14.sp, color = TextMuted)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(
                (recovery?.let { zoneTitle(it.zone) } ?: "Not enough data for Recovery yet").uppercase(),
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                letterSpacing = 0.54.sp,
            )
        }
        val body = recovery?.let { insightSentence(it.top) } ?: state.recoveryReason?.replaceFirstChar(Char::uppercase)
        if (!body.isNullOrEmpty()) Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = TextBody)
        state.strainTarget?.let { Text("Strain target${if (state.isToday) " today" else ""}: ${targetText(it)}", fontSize = 12.sp, color = TextMuted) }
    }
}
