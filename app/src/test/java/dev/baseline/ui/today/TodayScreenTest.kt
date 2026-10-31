package dev.baseline.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.baseline.data.SportFamily
import dev.baseline.scores.Contributor
import dev.baseline.scores.FormState
import dev.baseline.scores.RecoveryResult
import dev.baseline.scores.RecoveryZone
import dev.baseline.scores.TrainingLoadDay
import dev.baseline.scores.WeekBudget
import dev.baseline.ui.components.ActivityCardUi
import dev.baseline.ui.components.BODY_BATTERY_FILL_TAG
import dev.baseline.ui.components.BodyBatteryFillColor
import dev.baseline.ui.components.bodyBatteryChartDescription
import dev.baseline.ui.components.bodyBatteryColor
import dev.baseline.ui.components.statRowDescription
import dev.baseline.ui.theme.BaselineTheme
import dev.baseline.update.Release
import dev.baseline.update.UpdateState
import dev.baseline.update.UpdateUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TodayScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val date = LocalDate.of(2026, 10, 24)
    private val curve = (0..40).map { Instant.ofEpochSecond(1_792_738_800L + it * 240L) to 30 + it }
    private val run = ActivityCardUi(9, "Morning Run", SportFamily.RUN, "running", "7:05 AM · 10.21 km · 52:14", 9.8)
    private val rows =
        listOf(
            StatRowUi("hrv", "HRV (overnight)", "58", "ms", "55 ms", "▲ 6%", true, "better"),
            StatRowUi("rhr", "Resting heart rate", "49", "bpm", "47 bpm", "▲ 2", false, "worse"),
            StatRowUi("steps", "Steps", "6,214", "", "9,120", "so far", null),
        )
    private val green = RecoveryResult(72, false, RecoveryZone.GREEN, listOf(Contributor("hrv", 1.0, 6.2), Contributor("rhr", 1.0, -2.0)))
    private val full =
        TodayUiState(
            date,
            true,
            86,
            green,
            null,
            11.4,
            14.0..18.0,
            emptyList(),
            rows,
            BodyBatteryUi(80, curve, 62, null, false),
            Instant.ofEpochSecond(1_792_738_800L),
            date,
        )

    private fun show(
        initial: TodayUiState,
        syncLine: String? = null,
        onPrev: () -> Unit = {},
        onNext: () -> Unit = {},
        onCalendar: () -> Unit = {},
        onSleep: () -> Unit = {},
        onActivity: (Long) -> Unit = {},
    ) {
        var state by mutableStateOf(initial)
        rule.setContent {
            BaselineTheme {
                TodayContent(
                    state,
                    syncLine,
                    onPrev,
                    onNext,
                    onCalendar,
                    onSleep,
                    onActivity,
                    onToggleBodyBattery = { state = state.copy(bodyBattery = state.bodyBattery.copy(expanded = !state.bodyBattery.expanded)) },
                )
            }
        }
    }

    @Test
    fun activitiesHeaderHiddenWhenNone() {
        var state by mutableStateOf(full.copy(activities = emptyList()))
        rule.setContent { BaselineTheme { TodayContent(state, null, {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithText("TODAY'S ACTIVITIES").assertDoesNotExist()
        rule.onNodeWithText("VS. 30-DAY BASELINE").assertExists()

        state = state.copy(activities = listOf(run))
        rule.waitForIdle()
        val activities =
            rule
                .onNodeWithText("TODAY'S ACTIVITIES")
                .assertExists()
                .getUnclippedBoundsInRoot()
                .top
        val baseline =
            rule
                .onNodeWithText("VS. 30-DAY BASELINE")
                .assertExists()
                .getUnclippedBoundsInRoot()
                .top
        assert(activities < baseline) { "activities header at $activities should be above baseline header at $baseline" }
        rule.onNodeWithText("Morning Run").assertExists()
        rule.onNodeWithText("7:05 AM · 10.21 km · 52:14").assertExists()
        rule.onNodeWithText("9.8").assertExists()
    }

    @Test
    fun dialsHaveContentDescriptions() {
        show(full)
        rule.onNodeWithContentDescription("Recovery 72 percent").assertExists()
        rule.onNodeWithContentDescription("Sleep performance 86 percent").assertExists()
        rule.onNodeWithContentDescription("Strain 11.4 of 21").assertExists()
    }

    @Test
    fun dialsWithoutDataSayUnavailableAndShowDash() {
        show(full.copy(sleepPct = null, recovery = null, recoveryReason = "no sleep recorded", strain = null, strainTarget = null))
        rule.onNodeWithContentDescription("Recovery unavailable").assertExists()
        rule.onNodeWithContentDescription("Sleep unavailable").assertExists()
        rule.onNodeWithContentDescription("Strain unavailable").assertExists()
    }

    @Test
    fun partialRecoveryIsLabelled() {
        show(full.copy(recovery = green.copy(partial = true)))
        rule.onNodeWithText("Partial").assertExists()
    }

    @Test
    fun insightCardShowsZoneSentenceAndTarget() {
        show(full)
        rule.onNodeWithText("RECOVERED").assertExists()
        rule.onNodeWithText("HRV is 6% above your 30-day baseline and resting HR is 2 bpm below.").assertExists()
        rule.onNodeWithText("Strain target today: 14 – 18").assertExists()
    }

    @Test
    fun noRecoveryShowsReason() {
        show(full.copy(recovery = null, recoveryReason = "calibrating — need 7 nights", strainTarget = null))
        rule.onNodeWithText("Not enough data for Recovery yet", ignoreCase = true).assertExists()
        rule.onNodeWithText("calibrating — need 7 nights", ignoreCase = true).assertExists()
        rule.onNodeWithText("RECOVERED").assertDoesNotExist()
    }

    @Test
    fun baselineRowsShowVerdictWordsNotJustColour() {
        show(full)
        rule.onNodeWithContentDescription("HRV (overnight) 58 ms, 6% above baseline 55 ms, better").assertExists()
        rule.onNodeWithContentDescription("Resting heart rate 49 bpm, 2 bpm above baseline 47 bpm, worse").assertExists()
        rule.onNodeWithContentDescription("Steps 6,214, so far today").assertExists()
    }

    @Test
    fun rowDescriptionsCoverEveryShape() {
        fun d(row: StatRowUi) = statRowDescription(row)
        assertEquals("Sleep 7h 54m, 21m above baseline 7h 33m, better", d(StatRowUi("sleep", "Sleep", "7h 54m", "", "7h 33m", "▲ 21m", true, "better")))
        assertEquals(
            "Respiration (sleep) 12.0 brpm, 1.0 brpm below baseline 13.0 brpm, normal",
            d(StatRowUi("resp", "Respiration (sleep)", "12.0", "brpm", "13.0 brpm", "▼ 1.0", null, "normal")),
        )
        assertEquals("Avg stress 24, same as baseline 24", d(StatRowUi("stress", "Avg stress", "24", "", "24", "same", null)))
        assertEquals("Avg stress no value, baseline 24", d(StatRowUi("stress", "Avg stress", "—", "", "24", null, null)))
    }

    @Test
    fun verdictWordsAreShownAsText() {
        val rows =
            listOf(
                StatRowUi("hrv", "HRV (overnight)", "58", "ms", "55 ms", "▲ 6%", true, "better"),
                StatRowUi("resp", "Respiration (sleep)", "12.0", "brpm", "13.0 brpm", "▼ 1.0", null, "normal"),
                StatRowUi("rhr", "Resting heart rate", "49", "bpm", "47 bpm", "▲ 2", false, "worse"),
            )
        show(full.copy(rows = rows))
        listOf("better", "normal", "worse").forEach { rule.onNodeWithText(it, useUnmergedTree = true).assertExists() }
    }

    @Test
    fun loadingShowsAPlaceholderNotNoDataText() {
        show(full.copy(recovery = null, recoveryReason = "calibrating — need 7 nights", loading = true))
        rule.onNodeWithText("Loading…").assertExists()
        rule.onNodeWithText("Not enough data for Recovery yet", ignoreCase = true).assertDoesNotExist()
    }

    private fun chart() = rule.onNode(
        SemanticsMatcher("Body Battery chart description") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == "Body Battery today, ranging 30 to 70, now 80" } == true
        },
        useUnmergedTree = true,
    )

    @Test
    fun bodyBatteryBarBandsAndExpand() {
        var state by mutableStateOf(full)
        rule.setContent {
            BaselineTheme {
                TodayContent(
                    state,
                    null,
                    {},
                    {},
                    {},
                    {},
                    {},
                    onToggleBodyBattery = { state = state.copy(bodyBattery = state.bodyBattery.copy(expanded = !state.bodyBattery.expanded)) },
                )
            }
        }
        listOf(80 to 0xFF34D399, 60 to 0xFFA3D94C, 40 to 0xFFFBBF24, 10 to 0xFFF87171).forEach { (level, argb) ->
            state = state.copy(bodyBattery = BodyBatteryUi(level, curve, 62, null, false))
            rule.waitForIdle()
            rule
                .onNodeWithTag(BODY_BATTERY_FILL_TAG, useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(BodyBatteryFillColor, Color(argb)))
            rule.onNodeWithText(level.toString()).assertExists()
        }
        state = state.copy(bodyBattery = BodyBatteryUi(null, emptyList(), null, null, false))
        rule.waitForIdle()
        rule.onNodeWithText("—").assertExists()
        rule.onNodeWithTag(BODY_BATTERY_FILL_TAG, useUnmergedTree = true).assertDoesNotExist()

        state = state.copy(bodyBattery = BodyBatteryUi(80, curve, 62, null, false))
        rule.waitForIdle()
        chart().assertDoesNotExist()
        rule.onNodeWithText("+62 charged").assertExists() // the overnight charge is on the strip itself
        rule.onNodeWithText("BODY BATTERY").performClick()
        chart().assertExists()
        rule.onNodeWithText("BODY BATTERY").performClick()
        chart().assertDoesNotExist()
    }

    @Test
    fun bodyBatteryBandBoundaries() {
        val expect =
            mapOf(
                100 to 0xFF34D399,
                76 to 0xFF34D399,
                75 to 0xFFA3D94C,
                51 to 0xFFA3D94C,
                50 to 0xFFFBBF24,
                26 to 0xFFFBBF24,
                25 to 0xFFF87171,
                0 to 0xFFF87171,
            )
        expect.forEach { (level, argb) -> assertEquals("level $level", Color(argb), bodyBatteryColor(level)) }
    }

    @Test
    fun bodyBatteryWithoutReadingShowsDashAndEmptyTrack() {
        show(full.copy(bodyBattery = BodyBatteryUi(null, emptyList(), null, null, false)))
        rule.onNodeWithText("BODY BATTERY").assertExists()
        rule.onNodeWithText("charged", substring = true).assertDoesNotExist()
        rule.onNode(hasContentDescription("Body Battery, no reading yet")).assertExists()
        rule.onNodeWithTag(BODY_BATTERY_FILL_TAG, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("—").assertExists()
    }

    @Test
    fun chartDescriptionAndClickLabel() {
        val bb = BodyBatteryUi(48, curve.take(3), 74, null, false)
        assertEquals("Body Battery today, ranging 30 to 32, now 48", bodyBatteryChartDescription(bb, true))
        assertEquals("Body Battery that day, ranging 30 to 32, ending at 48", bodyBatteryChartDescription(bb, false))
        assertEquals("Body Battery today", bodyBatteryChartDescription(BodyBatteryUi(null, emptyList(), null, null, true), true))
        var state by mutableStateOf(full)
        rule.setContent {
            BaselineTheme {
                TodayContent(state, null, {}, {}, {}, {}, {}, {
                    state =
                        state.copy(bodyBattery = state.bodyBattery.copy(expanded = !state.bodyBattery.expanded))
                })
            }
        }

        fun label() = rule
            .onNodeWithText("BODY BATTERY")
            .fetchSemanticsNode()
            .config
            .getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            ?.label
        assertEquals("Show Body Battery curve", label())
        rule.onNodeWithText("BODY BATTERY").performClick()
        assertEquals("Hide Body Battery curve", label())
    }

    @Test
    fun expandedTotalsShowDrainedWhenKnown() {
        show(full.copy(bodyBattery = BodyBatteryUi(80, curve, 62, 48, true)))
        rule.onNodeWithText("Drained −48 today").assertExists()
    }

    @Test
    fun bodyBatteryIsASlimStripUnderTheDials() {
        show(full)
        val strip = rule.onNode(hasClickAction() and hasContentDescription("Body Battery 80, charged 62 overnight"))
        strip.assertExists()
        assertTrue(strip.getUnclippedBoundsInRoot().let { it.bottom - it.top } >= 48.dp)
        rule.onNodeWithText("Body Battery").assertDoesNotExist() // the old card's title
    }

    @Test
    fun navigationCallbacksAndNextDisabledOnToday() {
        var calendar = 0
        var prev = 0
        var next = 0
        var sleep = 0
        var opened = -1L
        show(full.copy(activities = listOf(run)), null, { prev++ }, { next++ }, { calendar++ }, { sleep++ }, { opened = it })
        rule.onNodeWithContentDescription("Next day").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Previous day").assertIsEnabled().performClick()
        rule.onNodeWithText("TODAY").performClick()
        rule.onNodeWithContentDescription("Sleep performance 86 percent").performClick()
        rule.onNodeWithText("Morning Run").performScrollTo().performClick()
        assertEquals(listOf(1, 1, 1), listOf(prev, calendar, sleep))
        assertEquals(9L, opened)
        assertEquals(0, next)
    }

    @Test
    fun nextDayIsEnabledOnPastDays() {
        var next = 0
        show(full.copy(date = date.minusDays(1), isToday = false), onNext = { next++ })
        rule.onNodeWithText("FRIDAY").assertExists()
        rule.onNodeWithContentDescription("Next day").assertIsEnabled().performClick()
        assertEquals(1, next)
    }

    @Test
    fun headerShowsDateAndSyncedTimeAndSyncLine() {
        show(full, syncLine = "Syncing… 3/10")
        rule.onNodeWithText("Syncing… 3/10").assertExists()
        rule
            .onNode(
                SemanticsMatcher("subtitle") { n ->
                    n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Sat, Oct 24 · Synced ") } == true
                },
            ).assertExists()
    }

    @Test
    fun syncedTimeFollowsTheZoneWithoutTheDateChanging() {
        var zone by mutableStateOf<java.time.ZoneId>(java.time.ZoneOffset.UTC)
        val at = full.copy(lastSynced = java.time.Instant.parse("2026-10-24T19:23:00Z"))
        rule.setContent { BaselineTheme { TodayContent(at, null, {}, {}, {}, {}, {}, {}, zone = zone) } }
        rule.onNode(SemanticsMatcher("utc") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.endsWith(" 7:23 PM") } == true }).assertExists()
        zone = java.time.ZoneOffset.ofHours(-7)
        rule.onNode(SemanticsMatcher("pdt") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.endsWith(" 12:23 PM") } == true }).assertExists()
    }

    @Test
    fun editModeShowsHandlesAndEyesAndDimsHiddenRows() {
        val rows =
            listOf(
                StatRowUi("hrv", "HRV (overnight)", "98", "ms", "90 ms", null, null),
                StatRowUi("steps", "Steps", "—", "", null, null, null, hidden = true),
            )
        var state by mutableStateOf(full.copy(rows = rows.take(1)))
        rule.setContent {
            BaselineTheme {
                TodayContent(state, null, {}, {}, {}, {}, {}, {}, onToggleEdit = {
                    state =
                        if (state.editing) full.copy(rows = rows.take(1)) else full.copy(rows = rows, editing = true)
                })
            }
        }
        rule.onNodeWithText("Edit").assertExists()
        rule.onNodeWithContentDescription("Drag to reorder HRV (overnight)").assertDoesNotExist()
        rule.onNodeWithText("Edit").performScrollTo().performClick()
        rule.onNodeWithText("Done").assertExists()
        rule.onNodeWithText("Drag to reorder. Tap the eye to hide a row.").assertExists()
        rule.onNodeWithContentDescription("Drag to reorder HRV (overnight)").assertExists()
        rule.onNodeWithContentDescription("Hide HRV (overnight)").assertExists()
        rule.onNodeWithContentDescription("Show Steps").assertExists() // hidden rows can be brought back
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Show Steps").assertDoesNotExist()
    }

    @Test
    fun editModeRowKeepsItsLabelReadableAt320dp() {
        val row = StatRowUi("resp", "Respiration (sleep)", "14.2", "brpm", "14 brpm", "▲ 0.2", true, verdict = "better")
        var state by mutableStateOf(full.copy(rows = listOf(row)))
        rule.setContent {
            BaselineTheme {
                Box(Modifier.width(320.dp)) { TodayContent(state, null, {}, {}, {}, {}, {}, {}, onToggleEdit = { state = state.copy(editing = true) }) }
            }
        }
        rule.onNodeWithText("Edit").performScrollTo().performClick()
        // Robolectric lays text out narrower than a device, so this asserts the width given to the label column (86 dp before the
        // fix, 142 dp after) rather than the line count; on a device the fixed label gets about 80 dp, a word of "Respiration".
        rule.onNodeWithTag("stat-label", useUnmergedTree = true).assertWidthIsAtLeast(110.dp)
    }

    private fun editRows(n: Int) = (0 until n).map { StatRowUi("r$it", "Row $it", "$it", "ms", "1 ms", null, null) }

    private fun setEditContent(rows: List<StatRowUi>, boxHeight: androidx.compose.ui.unit.Dp, moves: MutableList<Pair<Int, Int>>) {
        rule.setContent {
            BaselineTheme {
                Box(Modifier.height(boxHeight)) {
                    TodayContent(full.copy(rows = rows, editing = true), null, {}, {}, {}, {}, {}, {}, onMoveRow = { f, t -> moves += f to t })
                }
            }
        }
    }

    private fun scrollValue() = rule
        .onNodeWithTag("screen-today")
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()

    @Test
    fun draggingARowOverItsNeighbourMovesItThere() {
        val moves = mutableListOf<Pair<Int, Int>>()
        setEditContent(editRows(3), 1200.dp, moves)
        val h0 = rule.onNodeWithContentDescription("Drag to reorder Row 0").performScrollTo().getUnclippedBoundsInRoot()
        val stride =
            rule
                .onNodeWithContentDescription("Drag to reorder Row 1")
                .getUnclippedBoundsInRoot()
                .top.value - h0.top.value
        rule.onNodeWithContentDescription("Drag to reorder Row 0").performTouchInput {
            down(center)
            moveBy(Offset(0f, 5f))
            moveBy(Offset(0f, stride.dp.toPx() * 1.1f))
            up()
        }
        assertEquals(listOf(0 to 1), moves)
    }

    @Test
    fun droppedRowStaysInItsNewSlotUntilTheSavedOrderArrives() {
        val rows = editRows(3)
        var state by mutableStateOf(full.copy(rows = rows, editing = true))
        rule.setContent { BaselineTheme { Box(Modifier.height(1200.dp)) { TodayContent(state, null, {}, {}, {}, {}, {}, {}, onMoveRow = { _, _ -> }) } } }

        fun top(i: Int) = rule.onNodeWithContentDescription("Drag to reorder Row $i").getUnclippedBoundsInRoot().top
        rule.onNodeWithContentDescription("Drag to reorder Row 1").performScrollTo()
        val (t0, t1) = top(0) to top(1)
        rule.onNodeWithContentDescription("Drag to reorder Row 0").performTouchInput {
            down(center)
            moveBy(Offset(0f, 5f))
            moveBy(Offset(0f, (t1 - t0).toPx() * 1.1f))
            up()
        }
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(t1.value, top(0).value, 1f) // not slid back to the old slot
        assertEquals(t0.value, top(1).value, 1f)
        state = state.copy(rows = listOf(rows[1], rows[0], rows[2])) // the view model's saved order arrives
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(t1.value, top(0).value, 1f) // and does not jump
        assertEquals(t0.value, top(1).value, 1f)
    }

    @Test
    fun droppedMoveDoesNotOverrideASavedOrderThatReturnsToTheBase() {
        val rows = editRows(3)
        var state by mutableStateOf(full.copy(rows = rows, editing = true))
        rule.setContent { BaselineTheme { Box(Modifier.height(1200.dp)) { TodayContent(state, null, {}, {}, {}, {}, {}, {}, onMoveRow = { _, _ -> }) } } }

        fun top(i: Int) = rule.onNodeWithContentDescription("Drag to reorder Row $i").getUnclippedBoundsInRoot().top
        rule.onNodeWithContentDescription("Drag to reorder Row 1").performScrollTo()
        val (t0, t1) = top(0) to top(1)
        rule.onNodeWithContentDescription("Drag to reorder Row 0").performTouchInput {
            down(center)
            moveBy(Offset(0f, 5f))
            moveBy(Offset(0f, (t1 - t0).toPx() * 1.1f))
            up()
        }
        state = state.copy(rows = listOf(rows[1], rows[0], rows[2])) // saved
        rule.waitForIdle()
        state = state.copy(rows = rows) // then moved back (e.g. through TalkBack "Move up")
        rule.waitForIdle()
        assertEquals(t0.value, top(0).value, 1f)
        assertEquals(t1.value, top(1).value, 1f)
    }

    @Test
    fun holdingARowAtTheBottomEdgeScrollsThePageAndCountsTheDistance() {
        val moves = mutableListOf<Pair<Int, Int>>()
        setEditContent(editRows(14), 400.dp, moves)
        val handle = rule.onNodeWithContentDescription("Drag to reorder Row 6").performScrollTo()
        val before = scrollValue()
        rule.mainClock.autoAdvance = false // the edge-scroll loop runs a frame loop for as long as the finger is down
        handle.performTouchInput {
            down(center)
            moveBy(Offset(0f, 5f))
            moveBy(Offset(0f, 150.dp.toPx()))
        }
        repeat(200) {
            rule.mainClock.advanceTimeByFrame()
            org.robolectric.shadows.ShadowLooper
                .idleMainLooper()
        }
        assertTrue("page scrolled", scrollValue() > before)
        handle.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        // Without scrolling, 400 dp is only about five rows; the scrolled distance carries the row to the end.
        assertEquals(listOf(6 to 13), moves)
    }

    @Test
    fun dragTargetAndMakeRoomFollowTheDraggedRowsCentre() {
        val hs = listOf(40f, 40f, 40f)
        assertEquals(0, dragTarget(hs, 8f, 0, 10f))
        assertEquals(1, dragTarget(hs, 8f, 0, 40f))
        assertEquals(2, dragTarget(hs, 8f, 0, 500f))
        assertEquals(0, dragTarget(hs, 8f, 2, -500f))
        assertEquals(-48f, makeRoom(hs, 8f, 0, 2, 1))
        assertEquals(48f, makeRoom(hs, 8f, 2, 0, 1))
        assertEquals(0f, makeRoom(hs, 8f, 0, 1, 2))
    }

    @Test
    fun keyBannerAndUnreadableNoteShowOnToday() {
        var status: dev.baseline.garmin.KeyStatus? by mutableStateOf(null)
        rule.setContent {
            BaselineTheme { TodayContent(full.copy(unreadable = true), null, {}, {}, {}, {}, {}, {}, keyStatus = status) }
        }
        rule.onNodeWithText("Couldn't read this data").assertExists()
        rule.onNodeWithText("Garmin rejected your key", substring = true).assertDoesNotExist()
        status = dev.baseline.garmin.KeyStatus.Invalid
        rule.onNodeWithText("Garmin rejected your key, so new data can't sync. Paste a new key in Settings.").assertExists()
    }

    private val offer = Release("v0.05", "Notes", "https://x", "https://x/a.apk", 54_815_418, null)

    @Test
    fun anOfferedUpdateShowsAboveTheDateHeaderUntilDismissed() {
        var tapped = 0
        var dismissed: String? = null
        var update by mutableStateOf(UpdateUi("0.04", true, state = UpdateState.Available(offer)))
        rule.setContent {
            BaselineTheme {
                TodayContent(full, null, {}, {}, {}, {}, {}, {}, update = update, onUpdate = { tapped++ }, onDismissUpdate = {
                    dismissed = it
                    update = update.copy(dismissedTag = it)
                })
            }
        }
        rule.onNodeWithText("Baseline 0.05 is available.").assertIsDisplayed()
        val banner = rule.onNodeWithText("Baseline 0.05 is available.").getUnclippedBoundsInRoot()
        val header = rule.onNodeWithContentDescription("Previous day").getUnclippedBoundsInRoot()
        assertTrue("banner ${banner.top} should be above the header ${header.top}", banner.bottom <= header.top)
        rule.onNodeWithText("Update (55 MB)").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, tapped)
        rule.onNodeWithContentDescription("Hide this update").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals("v0.05", dismissed)
        rule.onNodeWithText("Baseline 0.05 is available.").assertDoesNotExist()
    }

    @Test
    fun theBannerReportsDownloadProgressToAccessibility() {
        val update = UpdateUi("0.04", true, state = UpdateState.Downloading(offer, 25, 100))
        rule.setContent { BaselineTheme { TodayContent(full, null, {}, {}, {}, {}, {}, {}, update = update) } }
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f))).assertExists()
        rule.onNodeWithText("Cancel").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun noUpdateBannerWhenUpToDateOrInADebugBuild() {
        var update by mutableStateOf(UpdateUi("0.04", true))
        rule.setContent { BaselineTheme { TodayContent(full, null, {}, {}, {}, {}, {}, {}, update = update) } }
        rule.onNodeWithContentDescription("Hide this update").assertDoesNotExist()
        update = UpdateUi("0.04", enabled = false, state = UpdateState.Available(offer))
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Hide this update").assertDoesNotExist()
    }

    private fun dialWidths(): List<Float> = listOf("Sleep performance 86 percent", "Recovery 72 percent", "Strain 11.4 of 21").map {
        rule.onNodeWithContentDescription(it, useUnmergedTree = true).getUnclippedBoundsInRoot().width.value
    }

    @Test
    @Config(qualifiers = "w320dp-h800dp")
    fun dialsShrinkTogetherOnANarrowScreen() {
        show(full)
        val bounds = listOf("Sleep performance 86 percent", "Recovery 72 percent", "Strain 11.4 of 21").map {
            rule.onNodeWithContentDescription(it, useUnmergedTree = true).getUnclippedBoundsInRoot()
        }
        val (sleep, recovery, strain) = bounds
        assertEquals(sleep.width.value, strain.width.value, 0.01f) // Strain is no longer squeezed below Sleep
        assertTrue("recovery stays the largest", recovery.width > sleep.width)
        assertTrue("sleep is shrunk", sleep.width < 96.dp)
        assertTrue("sleep touch target", sleep.width >= 48.dp)
        bounds.forEach { assertTrue("$it inside the 320 dp screen", it.left >= 0.dp && it.right <= 320.dp) }
        assertTrue(sleep.right <= recovery.left && recovery.right <= strain.left)
    }

    @Test
    @Config(qualifiers = "w480dp-h800dp")
    fun dialsKeepTheirNaturalSizeWhenThereIsRoom() {
        show(full)
        assertEquals(listOf(96f, 132f, 96f), dialWidths())
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun sleepAndStrainDialsStayEqualOnA360Phone() {
        // 360 - 40 padding = 320 dp for 324 dp of dials: a barely perceptible shrink that keeps the three in the row.
        show(full)
        val (sleep, _, strain) = dialWidths()
        assertEquals(sleep, strain, 0.01f)
    }

    @Test
    fun dialScaleIsOneWhenTheDialsFitAndProportionalWhenTheyDoNot() {
        assertEquals(1f, dialScale(324.dp), 0f)
        assertEquals(1f, dialScale(500.dp), 0f)
        assertEquals(0.5f, dialScale(162.dp), 0.001f)
        assertEquals(0.5f, dialScale(100.dp), 0f) // never smaller than half
    }

    @Test
    fun strainTargetOnlySaysTodayOnToday() {
        var state by mutableStateOf(full)
        rule.setContent { BaselineTheme { TodayContent(state, null, {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithText("Strain target today: 14 – 18").assertExists()
        state = full.copy(date = date.minusDays(1), isToday = false)
        rule.onNodeWithText("Strain target: 14 – 18").assertExists()
        rule.onNodeWithText("Strain target today", substring = true).assertDoesNotExist()
    }

    @Test
    fun subtitleWordsTheSyncFromTheStatesTodayNotTheClock() {
        // 2026-10-23 is long past on the host clock, yet it is "today" as far as the state is concerned.
        val syncedOn23 = Instant.parse("2026-10-23T19:23:00Z")
        val asToday = full.copy(date = LocalDate.of(2026, 10, 23), today = LocalDate.of(2026, 10, 23), lastSynced = syncedOn23)
        var state by mutableStateOf(asToday)
        rule.setContent { BaselineTheme { TodayContent(state, null, {}, {}, {}, {}, {}, {}, zone = java.time.ZoneOffset.UTC) } }
        rule.onNodeWithText("Fri, Oct 23 · Synced 7:23 PM").assertExists()
        // The day rolls over: the same sync reads as yesterday's.
        state = asToday.copy(today = LocalDate.of(2026, 10, 24), isToday = false)
        rule.onNodeWithText("Fri, Oct 23 · Synced Oct 23, 7:23 PM").assertExists()
    }

    @Test
    fun sectionTitlesAndTheDayTitleAreHeadings() {
        show(full.copy(activities = listOf(run)))
        listOf("VS. 30-DAY BASELINE", "TODAY'S ACTIVITIES", "TODAY").forEach {
            rule.onNodeWithText(it, useUnmergedTree = true).assert(isHeading())
        }
    }

    private val weekStart = date.minusDays(2)

    private fun load(state: FormState, budget: WeekBudget? = WeekBudget(weekStart, 62, false, 2, 4), formPct: Int? = -18) =
        TrainingLoadUi(TrainingLoadDay(date, 64.0, 76.0, formPct, state, if (state == FormState.CALIBRATING) 12 else null), budget)

    @Test
    fun aNormalWeekIsAQuietTagAndAFooterLine() {
        show(full.copy(trainingLoad = load(FormState.PRODUCTIVE)))
        rule.onNodeWithText("Productive week").assertExists()
        rule.onNodeWithText("This week: 62% of normal · ~2 hard days left").assertExists()
        rule.onNodeWithText("Strain target today: 14 – 18").assertExists()
        rule.onNodeWithText("Keep today easy", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Training load").assertDoesNotExist() // no separate card any more
    }

    @Test
    fun anOverreachingWeekCallsItOutAndEasesTheTarget() {
        show(full.copy(trainingLoad = load(FormState.OVERREACHING, formPct = -60), strainTarget = 10.0..14.0, strainTargetEased = true))
        rule.onNodeWithText("Overreaching week").assertExists()
        rule.onNodeWithText("Fatigue is well above your fitness. Keep today easy.").assertExists()
        rule.onNodeWithText("Strain target today: 10 – 14 (eased for this week)").assertExists()
    }

    @Test
    fun aFreshWeekCallsOutTheOpportunity() {
        show(full.copy(trainingLoad = load(FormState.FRESH, formPct = 12)))
        rule.onNodeWithText("You're rested. A good day for a hard session.").assertExists()
    }

    @Test
    fun pastTheSafeRangeCallsItOut() {
        show(full.copy(trainingLoad = load(FormState.PRODUCTIVE, WeekBudget(weekStart, 141, true, 0, 4))))
        rule.onNodeWithText("You're past your safe range for the week. Keep the rest easy.").assertExists()
        rule.onNodeWithText("This week: 141% of normal").assertExists()
    }

    @Test
    fun calibratingIsAMutedTagWithoutAWeekLine() {
        show(full.copy(trainingLoad = load(FormState.CALIBRATING, budget = null, formPct = null)))
        rule.onNodeWithText("Calibrating").assertExists()
        rule.onNodeWithText("This week", substring = true).assertDoesNotExist()
    }

    @Test
    fun tappingTheRecoveryCardOpensTrends() {
        var opened = 0
        rule.setContent {
            BaselineTheme { TodayContent(full.copy(trainingLoad = load(FormState.PRODUCTIVE)), null, {}, {}, {}, {}, {}, {}, onOpenTrends = { opened++ }) }
        }
        val card = rule.onNode(hasClickAction() and hasText("Productive week", substring = true))
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals("Open training load in Trends", card.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        assertTrue(card.getUnclippedBoundsInRoot().let { it.bottom - it.top } >= 48.dp)
        card.performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun withoutTrainingLoadTheRecoveryCardIsNotClickable() {
        show(full.copy(trainingLoad = null))
        rule.onNodeWithText("RECOVERED").assertExists()
        rule.onNode(hasClickAction() and hasText("RECOVERED", substring = true)).assertDoesNotExist()
    }
}
