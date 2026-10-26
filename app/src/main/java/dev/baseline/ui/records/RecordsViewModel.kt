package dev.baseline.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.data.Repository
import dev.baseline.data.SportFamily
import dev.baseline.data.countsForDistanceRecords
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.BestEffortEntity
import dev.baseline.records.BestEfforts
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.today.prettyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** [activityId] is null for an effort with no record yet (shown as "—", not tappable). */
data class RecordRow(val label: String, val time: String, val meta: String, val isNew: Boolean, val activityId: Long?)

data class PowerRow(val label: String, val watts: String)

/** [since] is the month of the oldest activity ("Aug 2025"); [power] is null outside the ride tab or when no ride has power. */
data class RecordsUiState(
    val tab: SportFamily,
    val rows: List<RecordRow>,
    val power: List<PowerRow>?,
    /** "Indoor" when no outdoor ride has power, else "All rides". */
    val powerLabel: String,
    val since: String?,
)

class RecordsViewModel(repo: Repository, private val todayFlow: StateFlow<LocalDate>) : ViewModel() {
    constructor(repo: Repository, today: LocalDate) : this(repo, MutableStateFlow(today))

    val today: LocalDate get() = todayFlow.value

    private val tab = MutableStateFlow(SportFamily.RUN)

    fun setTab(t: SportFamily) {
        tab.value = t
    }

    val state: StateFlow<RecordsUiState> =
        combine(tab, repo.bestEfforts(), repo.activities(), repo.imperial, todayFlow) { t, e, a, imperial, today ->
            buildRecordsState(t, e, a, today, Formatters(imperial))
        }.flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                buildRecordsState(SportFamily.RUN, emptyList(), emptyList(), todayFlow.value, Formatters(false)),
            )
}

private val RUN_LABELS = mapOf(1000 to "1 km", 1609 to "1 mile", 5000 to "5K", 10000 to "10K", 21098 to "Half marathon", 42195 to "Marathon")
private val RIDE_LABELS = mapOf(10000 to "10 km", 20000 to "20 km", 40000 to "40 km", 100000 to "100 km")
private val SWIM_LABELS = mapOf(100 to "100 m", 400 to "400 m", 1500 to "1500 m")
private val POWER_LABELS = mapOf(5 to "5 s", 60 to "1 min", 300 to "5 min", 1200 to "20 min", 3600 to "60 min")
private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
private const val NEW_DAYS = 30L

internal fun buildRecordsState(
    tab: SportFamily,
    efforts: List<BestEffortEntity>,
    activities: List<ActivityEntity>,
    today: LocalDate,
    fmt: Formatters,
): RecordsUiState {
    val byId = activities.associateBy { it.id }
    val mine = efforts.filter { it.sport == tab }.associateBy { it.effort }
    val labels =
        when (tab) {
            SportFamily.RIDE -> RIDE_LABELS
            SportFamily.SWIM -> SWIM_LABELS
            else -> RUN_LABELS
        }

    val rows =
        labels.map { (meters, label) ->
            val e = mine[BestEfforts.distanceKey(meters)]
            if (e == null) {
                RecordRow(label, "—", "No record yet", false, null)
            } else {
                val pace =
                    when (tab) {
                        SportFamily.RIDE -> fmt.speedValue(meters / e.valueSec)?.let { "$it ${fmt.speedUnit}" }
                        SportFamily.SWIM -> fmt.swimPace(e.valueSec / meters * 100)?.let { "$it ${fmt.swimPaceUnit}" }
                        else -> fmt.pace(fmt.secondsPerUnit(meters.toDouble(), e.valueSec))?.let { "$it ${fmt.paceUnit}" }
                    }
                val a = byId[e.activityId]
                val meta = listOfNotNull(pace, a?.let { it.name?.takeIf(String::isNotBlank) ?: prettyType(it.typeKey) }, e.achievedOn.format(DATE))
                RecordRow(label, fmt.duration(e.valueSec) ?: "—", meta.joinToString(" · "), e.achievedOn >= today.minusDays(NEW_DAYS), e.activityId)
            }
        }

    val power =
        if (tab == SportFamily.RIDE && mine.keys.any(BestEfforts::isPower)) {
            POWER_LABELS.map { (sec, label) -> PowerRow(label, mine[BestEfforts.powerKey(sec)]?.let { "${it.valueSec.roundToInt()}" } ?: "—") }
        } else {
            null
        }
    val outdoorPower = activities.any { it.sportFamily == SportFamily.RIDE && it.sportFamily.countsForDistanceRecords(it.typeKey) && it.avgPower != null }
    return RecordsUiState(tab, rows, power, if (outdoorPower) "All rides" else "Indoor", activities.minOfOrNull { it.day }?.format(MONTH))
}
