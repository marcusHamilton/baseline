package dev.baseline.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.data.Repository
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.ScoreEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.LocalDate
import kotlin.math.roundToInt

enum class TrendRange(val days: Int) {
    D7(7),
    D30(30),
    D90(90),
    ;

    companion object {
        val Default = D30
    }
}

/** Each average is the mean of the non-null values over the range; null when there are none. */
data class TrendAverages(val recovery: Int?, val hrv: Int?, val rhr: Int?, val sleep: Duration?)

/**
 * Header figures: the most recent non-null value in the range, and the HRV reference (the latest Garmin weekly
 * baseline, else the latest band midpoint, else null so the header falls back to the average).
 */
data class TrendLatest(val hrv: Int?, val hrvBaseline: Int?, val rhr: Int?)

/**
 * One entry per day of the range, oldest first, ending today. A day without data is null (a gap), never zero.
 * [hrvBand] only has the days where Garmin gave both bounds.
 */
data class TrendsUiState(
    val range: TrendRange,
    val recovery: List<Pair<LocalDate, Int?>>,
    val hrv: List<Pair<LocalDate, Int?>>,
    val hrvBand: List<Pair<LocalDate, ClosedRange<Int>>>,
    val rhr: List<Pair<LocalDate, Int?>>,
    val sleepVsNeed: List<Triple<LocalDate, Duration?, Duration?>>,
    val averages: TrendAverages,
    val latest: TrendLatest,
)

class TrendsViewModel(private val repo: Repository, private val todayFlow: StateFlow<LocalDate>) : ViewModel() {
    constructor(repo: Repository, today: LocalDate) : this(repo, MutableStateFlow(today))

    val today: LocalDate get() = todayFlow.value

    private val range = MutableStateFlow(TrendRange.Default)

    fun setRange(r: TrendRange) {
        range.value = r
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TrendsUiState> =
        combine(range, todayFlow, ::Pair)
            .flatMapLatest { (r, today) ->
                val from = today.minusDays(r.days - 1L)
                combine(repo.days(from, today), repo.scores(from, today)) { d, s -> buildTrendsState(r, today, d, s) }
            }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildTrendsState(range.value, todayFlow.value, emptyList(), emptyList()))
}

private fun Double?.finitePositive(): Double? = this?.takeIf { it.isFinite() && it > 0 }

internal fun buildTrendsState(range: TrendRange, today: LocalDate, days: List<DayEntity>, scores: List<ScoreEntity>): TrendsUiState {
    val dates = List(range.days) { today.minusDays(range.days - 1L - it) }
    val byDay = days.associateBy { it.date }
    val byScore = scores.associateBy { it.date }
    val recovery = dates.map { it to byScore[it]?.recovery }
    val hrv = dates.map { it to byDay[it]?.hrvLastNight.finitePositive()?.roundToInt() }
    val rhr = dates.map { it to byDay[it]?.restingHr?.takeIf { v -> v > 0 } }
    val sleep =
        dates.map { d ->
            val day = byDay[d]
            Triple(
                d,
                day?.sleepSeconds?.takeIf { it > 0 }?.let { Duration.ofSeconds(it.toLong()) },
                day?.sleepNeedMinutes?.takeIf { it > 0 }?.let { Duration.ofMinutes(it.toLong()) },
            )
        }
    val band =
        dates.mapNotNull { d ->
            val lo = byDay[d]?.hrvBalancedLow.finitePositive()?.roundToInt()
            val hi = byDay[d]?.hrvBalancedUpper.finitePositive()?.roundToInt()
            if (lo != null && hi != null && lo <= hi) d to (lo..hi) else null
        }
    val asleep = sleep.mapNotNull { it.second }
    val baseline =
        dates.lastNotNullOf { byDay[it]?.hrvWeeklyAvg.finitePositive()?.roundToInt() }
            ?: band.lastOrNull()?.let { (_, r) -> (r.start + r.endInclusive) / 2 }
    return TrendsUiState(
        range,
        recovery,
        hrv,
        band,
        rhr,
        sleep,
        TrendAverages(
            recovery.mean(),
            hrv.mean(),
            rhr.mean(),
            if (asleep.isEmpty()) null else Duration.ofSeconds((asleep.sumOf { it.seconds }.toDouble() / asleep.size).roundToInt().toLong()),
        ),
        TrendLatest(hrv.lastValue(), baseline, rhr.lastValue()),
    )
}

private inline fun <T, R : Any> List<T>.lastNotNullOf(f: (T) -> R?): R? {
    for (i in indices.reversed()) f(this[i])?.let { return it }
    return null
}

private fun List<Pair<LocalDate, Int?>>.lastValue(): Int? = lastNotNullOf { it.second }

/** "58 ms · baseline 55"; the reference falls back to the range average, and any missing piece shows a dash. */
internal fun TrendsUiState.hrvHeader(): String {
    val now = latest.hrv?.let { "$it ms" } ?: "—"
    return if (latest.hrvBaseline != null) "$now · baseline ${latest.hrvBaseline}" else "$now · avg ${averages.hrv ?: "—"}"
}

/** "47 bpm · avg 49". */
internal fun TrendsUiState.rhrHeader(): String = "${latest.rhr?.let { "$it bpm" } ?: "—"} · avg ${averages.rhr ?: "—"}"

private fun List<Pair<LocalDate, Int?>>.mean(): Int? = mapNotNull { it.second }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
