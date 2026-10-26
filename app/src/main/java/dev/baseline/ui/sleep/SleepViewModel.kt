package dev.baseline.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.data.Repository
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.TaskDao
import dev.baseline.data.db.TaskStatus
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.scores.HrPoint
import dev.baseline.scores.SleepPerformance
import dev.baseline.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** [seconds] and [pct] (of the four stages together) are null when Garmin sent no stage figures. */
data class StageUi(val label: String, val seconds: Int?, val pct: Int?)

/** [adjustment] is Garmin's actual minus its baseline; [reasons] are chip labels such as "HRV ▲". */
data class SleepNeedUi(val baseline: Duration, val adjustment: Duration, val reasons: List<String>, val bedtimeStart: LocalTime?)

/** Already formatted without units; "—" when missing. */
data class SleepTiles(val hrv: String, val lowestHr: String, val bodyBattery: String, val spo2: String)

/** [asleep] and [need] are [Duration.ZERO] when unknown. [overnightLoading]: the HRV day is still being fetched. */
data class SleepUiState(
    val date: LocalDate,
    val isToday: Boolean,
    val sleepPct: Int?,
    val asleep: Duration,
    val need: Duration,
    val garminScore: Int?,
    val bed: LocalTime?,
    val wake: LocalTime?,
    val hrvSeries: List<Pair<Instant, Int>>,
    val hrSeries: List<Pair<Instant, Int>>,
    val overnightLoading: Boolean,
    val stages: List<StageUi>,
    val needCard: SleepNeedUi?,
    val tiles: SleepTiles,
    /** True until the first real emission. */
    val loading: Boolean = false,
    /** The night's data arrived but could not be parsed (spec §10). */
    val unreadable: Boolean = false,
)

/** Sleep for the night ending on [date]. Missing overnight rows are queued once (priority 0) and then arrive through the Flows. */
class SleepViewModel(
    private val repo: Repository,
    private val taskDao: TaskDao,
    private val scheduler: SyncScheduler,
    val date: LocalDate,
    today: LocalDate,
    private val zone: ZoneId,
) : ViewModel() {
    private class Inputs(val day: DayEntity?, val score: ScoreEntity?, val hr: List<HrPoint>)
    private class Overnight(val hrv: List<Pair<Instant, Int>>?, val task: TaskStatus?, val unreadable: Boolean)

    val state: StateFlow<SleepUiState> = combine(
        combine(
            repo.day(date), repo.score(date), repo.heartRate(date.minusDays(1)), repo.heartRate(date),
        ) { day, score, hr0, hr1 -> Inputs(day, score, hr0 + hr1) },
        combine(repo.hrvReadings(date), repo.taskStatus(GarminEndpoint.HRV_DAY, date.toString()), repo.dayUnreadable(date, sleepOnly = true), ::Overnight),
    ) { a, b -> buildSleepState(date, today, a.day, a.score, a.hr, b.hrv, b.task, zone, b.unreadable) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildSleepState(date, today, null, null, emptyList(), null, null, zone).copy(loading = true))

    init {
        // Once per screen instance: a night synced long ago has no HRV/SpO2 day rows yet.
        viewModelScope.launch {
            if (repo.hrvReadings(date).first() == null) {
                taskDao.upsertAll(listOf(GarminEndpoint.HRV_DAY, GarminEndpoint.SPO2_DAY).map {
                    SyncTaskEntity(it.name, date.toString(), 0, TaskStatus.PENDING, 0, null)
                })
                scheduler.syncNow()
            }
        }
    }
}

private const val MISSING = "—"
private val REASON_LABELS = mapOf(
    "trainingFeedback" to "Recent training", "hrvAdjustment" to "HRV",
    "sleepHistoryAdjustment" to "Sleep history", "napAdjustment" to "Naps",
)

/** "trainingFeedback=INCREASING,hrvAdjustment=DECREASING" -> ["Recent training ▲", "HRV ▼"]; unknown keys are skipped. */
internal fun reasonChips(stored: String?): List<String> = stored.orEmpty().split(',').mapNotNull { part ->
    val (key, value) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    REASON_LABELS[key]?.let { label ->
        when (value) { "INCREASING" -> "$label ▲"; "DECREASING" -> "$label ▼"; else -> label }
    }
}

private fun minutesOfDay(min: Int): LocalTime = LocalTime.ofSecondOfDay(Math.floorMod(min, 24 * 60) * 60L)

@Suppress("LongParameterList")
internal fun buildSleepState(
    date: LocalDate,
    today: LocalDate,
    day: DayEntity?,
    score: ScoreEntity?,
    heartRate: List<HrPoint>,
    hrv: List<Pair<Instant, Int>>?,
    hrvTask: TaskStatus?,
    zone: ZoneId,
    unreadable: Boolean = false,
): SleepUiState {
    val perf = score?.sleepPerformance ?: SleepPerformance.of(day?.sleepSeconds, day?.sleepNeedMinutes)
    val start = day?.sleepStart
    val end = day?.sleepEnd
    val hr = if (start == null || end == null) emptyList() else heartRate.asSequence()
        .map { Instant.ofEpochSecond(it.epochSec) to it.bpm }
        .filter { it.first >= start && it.first <= end }
        .distinctBy { it.first }.sortedBy { it.first }.toList()
    val stageSecs = listOf("Deep" to day?.deepSeconds, "REM" to day?.remSeconds, "Light" to day?.lightSeconds, "Awake" to day?.awakeSeconds)
    val stageTotal = stageSecs.sumOf { it.second ?: 0 }
    val baseline = day?.nextSleepNeedBaselineMin
    return SleepUiState(
        unreadable = unreadable,
        date = date,
        isToday = date == today,
        sleepPct = perf?.takeIf { it.isFinite() }?.let { (it * 100).roundToInt() },
        asleep = Duration.ofSeconds((day?.sleepSeconds ?: 0).coerceAtLeast(0).toLong()),
        need = Duration.ofMinutes((day?.sleepNeedMinutes ?: 0).coerceAtLeast(0).toLong()),
        garminScore = day?.sleepScore,
        bed = start?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0),
        wake = end?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0),
        hrvSeries = hrv.orEmpty(),
        hrSeries = hr,
        overnightLoading = hrv == null && hrvTask != TaskStatus.EMPTY && hrvTask != TaskStatus.FAILED,
        stages = stageSecs.map { (label, s) ->
            StageUi(label, s, if (s != null && stageTotal > 0) (s * 100.0 / stageTotal).roundToInt() else null)
        },
        needCard = baseline?.let {
            SleepNeedUi(
                Duration.ofMinutes(it.toLong()),
                Duration.ofMinutes(((day.nextSleepNeedActualMin ?: it) - it).toLong()),
                reasonChips(day.nextSleepNeedReasons),
                day.recommendedBedtimeMin?.let(::minutesOfDay),
            )
        },
        tiles = SleepTiles(
            hrv = day?.hrvLastNight?.takeIf { it.isFinite() && it > 0 }?.let { it.roundToInt().toString() } ?: MISSING,
            lowestHr = hr.minOfOrNull { it.second }?.toString() ?: MISSING,
            bodyBattery = day?.bodyBatteryCharged?.let { "+$it" } ?: MISSING,
            spo2 = day?.spo2Avg?.takeIf { it.isFinite() && it > 0 }?.let { it.roundToInt().toString() } ?: MISSING,
        ),
    )
}
