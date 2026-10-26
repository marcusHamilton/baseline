package dev.baseline.scores

import dev.baseline.data.db.DayEntity
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class Baseline(
    val lnHrvMean: Double,
    val lnHrvSd: Double,
    val rhrMean: Double,
    val rhrSd: Double,
    val respMean: Double?,
    val respSd: Double?,
    val nights: Int,
) {
    init {
        require(lnHrvSd > 0 && rhrSd > 0 && (respSd ?: 1.0) > 0) { "SDs must be positive" }
    }
}

object Baselines {
    private const val MIN_NIGHTS = 7

    /**
     * Mean/SD over day-30..day-1 (inclusive). A valid day has hrvLastNight > 0 and restingHr > 0; fewer than 7
     * gives null. SDs are sample SDs (n-1) floored at 0.05 / 1.5 / 0.3. Respiration (> 0) is taken from valid days
     * only and is null with fewer than 7 of them.
     */
    fun at(day: LocalDate, history: List<DayEntity>): Baseline? {
        val from = day.minusDays(30)
        val to = day.minusDays(1)
        val valid = history.filter { it.date in from..to && (it.hrvLastNight ?: 0.0) > 0 && (it.restingHr ?: 0) > 0 }
        if (valid.size < MIN_NIGHTS) return null
        val hrv = valid.map { ln(it.hrvLastNight!!) }
        val rhr = valid.map { it.restingHr!!.toDouble() }
        val resp = valid.mapNotNull { it.respirationAvg?.takeIf { r -> r > 0 } }.takeIf { it.size >= MIN_NIGHTS }
        return Baseline(
            hrv.average(),
            max(sd(hrv), 0.05),
            rhr.average(),
            max(sd(rhr), 1.5),
            resp?.average(),
            resp?.let { max(sd(it), 0.3) },
            valid.size,
        )
    }

    private fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }
}
