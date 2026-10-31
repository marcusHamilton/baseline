package dev.baseline.scores

import java.time.LocalDate
import kotlin.math.roundToInt

enum class FormState { CALIBRATING, NO_RECENT_TRAINING, OVERREACHING, PRODUCTIVE, MAINTAINING, FRESH, DETRAINING }

/**
 * The training-load read for [date], taken from the values through the day before (fitness-fatigue-form spec §3.2),
 * so it doesn't drift as the day's own load arrives. [formPct] is null for CALIBRATING and NO_RECENT_TRAINING;
 * [calibratingDaysLeft] is set only for CALIBRATING.
 */
data class TrainingLoadDay(
    val date: LocalDate,
    val fitness: Double,
    val fatigue: Double,
    val formPct: Int?,
    val state: FormState,
    val calibratingDaysLeft: Int?,
)

/** Fitness (42-day) and fatigue (7-day) exponentially weighted day loads, form and its state (fitness-fatigue-form spec §3). */
object TrainingLoad {
    /** Read this many days before the first shown date: starting at 0 that far back leaves ~5 % error in fitness. */
    const val WARM_UP_DAYS = 126L
    private const val FITNESS_DAYS = 42.0
    private const val FATIGUE_DAYS = 7.0
    private const val CALIBRATION_DAYS = 42L

    /** No recent training below this fraction of the strain calibration's tau, so form % can't blow up after a break. */
    private const val MIN_FITNESS_OF_TAU = 0.01

    fun stateOf(formPct: Int): FormState = when {
        formPct < -30 -> FormState.OVERREACHING
        formPct < -10 -> FormState.PRODUCTIVE
        formPct <= 5 -> FormState.MAINTAINING
        formPct <= 20 -> FormState.FRESH
        else -> FormState.DETRAINING
    }

    /** A missing, non-finite or negative load counts as a rest day. */
    internal fun loadOn(loads: Map<LocalDate, Double>, date: LocalDate): Double = loads[date]?.takeIf { it.isFinite() && it > 0 } ?: 0.0

    /**
     * One entry per date in [from]..[to]. The averages start at 0 on the earliest date in [loads] (callers pass
     * [WARM_UP_DAYS] of history before [from]); dates without a load count as 0.
     */
    fun series(loads: Map<LocalDate, Double>, from: LocalDate, to: LocalDate, earliestScore: LocalDate?, tau: Double?): List<TrainingLoadDay> {
        val out = ArrayList<TrainingLoadDay>()
        var fitness = 0.0
        var fatigue = 0.0
        var d = minOf(loads.keys.minOrNull() ?: from, from)
        while (!d.isAfter(to)) {
            if (!d.isBefore(from)) out += day(d, fitness, fatigue, earliestScore, tau)
            val load = loadOn(loads, d)
            fitness += (load - fitness) / FITNESS_DAYS
            fatigue += (load - fatigue) / FATIGUE_DAYS
            d = d.plusDays(1)
        }
        return out
    }

    private fun day(date: LocalDate, fitness: Double, fatigue: Double, earliestScore: LocalDate?, tau: Double?): TrainingLoadDay {
        val calibrating = when {
            earliestScore == null -> CALIBRATION_DAYS.toInt()
            earliestScore.isAfter(date.minusDays(CALIBRATION_DAYS)) -> (earliestScore.plusDays(CALIBRATION_DAYS).toEpochDay() - date.toEpochDay()).toInt()
            else -> null
        }
        if (calibrating != null) return TrainingLoadDay(date, fitness, fatigue, null, FormState.CALIBRATING, calibrating)
        if (fitness <= 0 || (tau != null && fitness < MIN_FITNESS_OF_TAU * tau)) {
            return TrainingLoadDay(date, fitness, fatigue, null, FormState.NO_RECENT_TRAINING, null)
        }
        val pct = ((fitness - fatigue) / fitness * 100).roundToInt()
        return TrainingLoadDay(date, fitness, fatigue, pct, stateOf(pct), null)
    }
}
