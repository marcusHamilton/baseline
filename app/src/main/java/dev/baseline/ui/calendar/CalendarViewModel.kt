package dev.baseline.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.data.Repository
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.scores.RecoveryZone
import dev.baseline.scores.SleepPerformance
import dev.baseline.ui.today.prettyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

private const val MAX_DOTS = 3

/** [activityCount] is capped at [MAX_DOTS] (one dot each); [activities] is the real count, for the spoken label. */
data class CalendarCell(
    val date: LocalDate,
    val zone: RecoveryZone?,
    val recovery: Int?,
    val activityCount: Int,
    val activities: Int,
    val isToday: Boolean,
    val isFuture: Boolean,
)

/** The selected day's card: every figure is null when there is nothing to show. */
data class DaySummary(
    val date: LocalDate,
    val recovery: Int?,
    val zone: RecoveryZone?,
    val sleepPct: Int?,
    val strain: Double?,
    val activityNames: List<String>,
)

/** [cells] has one null per blank slot before the 1st (no trailing blanks). */
data class CalendarUiState(
    val month: YearMonth,
    val firstDayOfWeek: DayOfWeek,
    val cells: List<CalendarCell?>,
    val selected: LocalDate,
    val summary: DaySummary,
    val monthAvgRecovery: Int?,
    val monthAvgSleepSec: Double?,
    val monthActivities: Int,
    val canGoNext: Boolean,
)

class CalendarViewModel(private val repo: Repository, private val todayFlow: StateFlow<LocalDate>) : ViewModel() {
    constructor(repo: Repository, today: LocalDate) : this(repo, MutableStateFlow(today))

    val today: LocalDate get() = todayFlow.value
    private val month = MutableStateFlow(YearMonth.from(todayFlow.value))
    private val selected = MutableStateFlow(todayFlow.value)

    init {
        // Past midnight: a calendar left on the current day/month follows to the new one; anything else stays put.
        viewModelScope.launch {
            var prev = todayFlow.value
            todayFlow.collect { now ->
                val onToday = selected.value == prev
                val onMonth = month.value == YearMonth.from(prev)
                prev = now
                if (onMonth) month.value = YearMonth.from(now)
                if (onToday) {
                    selected.value = now
                } else if (selected.value.isAfter(now) || YearMonth.from(selected.value) != month.value) {
                    selected.value = now
                }
            }
        }
    }

    fun select(d: LocalDate) {
        if (!d.isAfter(today) && YearMonth.from(d) == month.value) selected.value = d
    }

    fun prevMonth() = goTo(month.value.minusMonths(1))

    fun nextMonth() = goTo(month.value.plusMonths(1))

    /** Keeps the day of the month (clamped to the month and to today), so the card follows the user around. */
    private fun goTo(m: YearMonth) {
        if (m.atDay(1).isAfter(today)) return
        month.value = m
        selected.value = minOf(m.atDay(minOf(selected.value.dayOfMonth, m.lengthOfMonth())), today)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<CalendarUiState> =
        month
            .flatMapLatest { m ->
                val from = m.atDay(1)
                val to = m.atEndOfMonth()
                combine(
                    repo.days(from, to),
                    repo.scores(from, to),
                    repo.activitiesBetween(from, to),
                    repo.firstDayOfWeek,
                    selected,
                    todayFlow,
                ) { values ->
                    @Suppress("UNCHECKED_CAST")
                    val days = values[0] as List<DayEntity>
                    val scores = values[1] as List<ScoreEntity>
                    val activities = values[2] as List<ActivityEntity>
                    val first = values[3] as DayOfWeek
                    val sel = values[4] as LocalDate
                    val today = values[5] as LocalDate
                    buildCalendarState(m, first, sel, today, days, scores, activities)
                }
            }.flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                buildCalendarState(month.value, DayOfWeek.MONDAY, todayFlow.value, todayFlow.value, emptyList(), emptyList(), emptyList()),
            )
}

internal fun buildCalendarState(
    month: YearMonth,
    firstDay: DayOfWeek,
    selected: LocalDate,
    today: LocalDate,
    days: List<DayEntity>,
    scores: List<ScoreEntity>,
    activities: List<ActivityEntity>,
): CalendarUiState {
    val dayBy = days.associateBy { it.date }
    val scoreBy = scores.associateBy { it.date }
    val actBy = activities.groupBy { it.day }
    val lead = Math.floorMod(month.atDay(1).dayOfWeek.value - firstDay.value, 7)
    val cells =
        List(lead) { null } +
            (1..month.lengthOfMonth()).map { n ->
                val d = month.atDay(n)
                val recovery = scoreBy[d]?.recovery
                val count = actBy[d]?.size ?: 0
                CalendarCell(
                    d,
                    recovery?.let(RecoveryZone::of),
                    recovery,
                    minOf(count, MAX_DOTS),
                    count,
                    isToday = d == today,
                    isFuture = d.isAfter(today),
                )
            }
    val score = scoreBy[selected]
    val perf = score?.sleepPerformance ?: SleepPerformance.of(dayBy[selected]?.sleepSeconds, dayBy[selected]?.sleepNeedMinutes)
    val summary =
        DaySummary(
            selected,
            score?.recovery,
            score?.recovery?.let(RecoveryZone::of),
            perf?.takeIf { it.isFinite() }?.let { (it * 100).roundToInt() },
            score?.strain?.takeIf { it.isFinite() },
            actBy[selected].orEmpty().map { a -> a.name?.takeIf { it.isNotBlank() } ?: prettyType(a.typeKey) },
        )
    return CalendarUiState(
        month,
        firstDay,
        cells,
        selected,
        summary,
        scores
            .mapNotNull { it.recovery }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.roundToInt(),
        days.mapNotNull { it.sleepSeconds?.takeIf { s -> s > 0 }?.toDouble() }.takeIf { it.isNotEmpty() }?.average(),
        activities.size,
        canGoNext = month.plusMonths(1).atDay(1) <= today,
    )
}
