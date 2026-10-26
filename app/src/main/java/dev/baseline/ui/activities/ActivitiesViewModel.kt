package dev.baseline.ui.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.data.Repository
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.ui.components.ActivityCardUi
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.today.prettyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Totals for the current week; [distanceKm] is always kilometres (the screen converts), [avgStrain] is null when nothing has a score. */
data class WeekSummary(val count: Int, val duration: Duration, val distanceKm: Double, val avgStrain: Double?)

data class DayGroup(val date: LocalDate, val items: List<ActivityCardUi>)

/** [filter] null means All. The week summary ignores the filter. */
data class ActivitiesUiState(
    val filter: SportFamily?,
    val week: WeekSummary,
    val days: List<DayGroup>,
    val today: LocalDate,
    val imperial: Boolean,
)

class ActivitiesViewModel(repo: Repository, private val todayFlow: StateFlow<LocalDate>) : ViewModel() {
    constructor(repo: Repository, today: LocalDate) : this(repo, MutableStateFlow(today))

    val today: LocalDate get() = todayFlow.value

    private val filter = MutableStateFlow<SportFamily?>(null)

    fun setFilter(f: SportFamily?) { filter.value = f }

    private class Data(
        val activities: List<ActivityEntity>, val strains: Map<Long, Double>, val sets: Map<Long, Int>,
        val firstDay: DayOfWeek, val imperial: Boolean,
    )

    private val data = combine(
        repo.activities(), repo.allActivityScores().map { all -> all.associate { it.activityId to it.strain } },
        repo.activeSetCounts(), repo.firstDayOfWeek, repo.imperial, ::Data,
    )

    val state: StateFlow<ActivitiesUiState> = combine(filter, data, todayFlow) { f, d, today ->
        buildActivitiesState(f, d.activities, d.strains, d.sets, d.firstDay, today, Formatters(d.imperial))
    }.flowOn(Dispatchers.Default) // 175+ rows, rebuilt on every sync write
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            buildActivitiesState(null, emptyList(), emptyMap(), emptyMap(), DayOfWeek.MONDAY, todayFlow.value, Formatters(false)),
        )
}

internal fun buildActivitiesState(
    filter: SportFamily?,
    activities: List<ActivityEntity>,
    strains: Map<Long, Double>,
    activeSets: Map<Long, Int>,
    firstDay: DayOfWeek,
    today: LocalDate,
    fmt: Formatters,
): ActivitiesUiState {
    val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDay))
    val thisWeek = activities.filter { it.day in weekStart..weekStart.plusDays(6) }
    val week = WeekSummary(
        thisWeek.size,
        Duration.ofSeconds(thisWeek.sumOf { it.durationSec.takeIf(Double::isFinite) ?: 0.0 }.toLong()),
        thisWeek.sumOf { it.distanceM?.takeIf { d -> d.isFinite() && d > 0 } ?: 0.0 } / 1000,
        thisWeek.mapNotNull { strains[it.id]?.takeIf(Double::isFinite) }.takeIf { it.isNotEmpty() }?.average(),
    )
    val days = activities.filter { filter == null || it.sportFamily == filter }
        .sortedByDescending { it.startTimeLocal }
        .groupBy { it.day }
        .map { (date, list) ->
            DayGroup(date, list.map { a ->
                ActivityCardUi(
                    a.id, a.name?.takeIf { it.isNotBlank() } ?: prettyType(a.typeKey), a.sportFamily, a.typeKey,
                    fmt.meta(a.sportFamily, a.distanceM, a.durationSec, a.avgPower, activeSets[a.id]), strains[a.id],
                )
            })
        }
    return ActivitiesUiState(filter, week, days, today, fmt.imperial)
}
