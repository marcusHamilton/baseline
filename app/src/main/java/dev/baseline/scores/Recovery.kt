package dev.baseline.scores

import dev.baseline.data.db.DayEntity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

enum class RecoveryZone {
    GREEN,
    AMBER,
    RED,
    ;

    companion object {
        fun of(score: Int) = when {
            score >= 67 -> GREEN
            score >= 34 -> AMBER
            else -> RED
        }
    }
}

/** [metric] is one of "hrv", "rhr", "sleep", "resp"; [delta] is in human units (% / bpm / percentage points / breaths per minute). */
data class Contributor(val metric: String, val z: Double, val delta: Double)

data class RecoveryResult(val score: Int, val partial: Boolean, val zone: RecoveryZone, val top: List<Contributor>)

object Recovery {
    private class Term(val weight: Double, val c: Contributor)

    /** Null without sleep performance or a baseline. Terms with a null/non-positive/non-finite input are dropped and the weights renormalised. */
    fun compute(day: DayEntity, b: Baseline?, sleepPerf: Double?): RecoveryResult? {
        if (sleepPerf == null || b == null || !sleepPerf.isFinite()) return null
        val hrv = day.hrvLastNight?.takeIf { it > 0 && it.isFinite() }
        val rhr = day.restingHr?.takeIf { it > 0 }
        val resp = day.respirationAvg?.takeIf { it > 0 && it.isFinite() }

        val terms =
            listOfNotNull(
                hrv?.let { Term(0.50, Contributor("hrv", (ln(it) - b.lnHrvMean) / b.lnHrvSd, (it / exp(b.lnHrvMean) - 1) * 100)) },
                rhr?.let { Term(0.25, Contributor("rhr", -(it - b.rhrMean) / b.rhrSd, it - b.rhrMean)) },
                Term(0.20, Contributor("sleep", ((sleepPerf - 0.85) / 0.10).coerceIn(-3.0, 2.0), (sleepPerf - 0.85) * 100)),
                if (resp != null && b.respMean != null && b.respSd != null) {
                    Term(0.05, Contributor("resp", -max(0.0, (resp - b.respMean) / b.respSd), resp - b.respMean))
                } else {
                    null
                },
            )
        val c = terms.sumOf { it.weight * it.c.z } / terms.sumOf { it.weight }
        val score = Math.round(100.0 / (1 + exp(-(1.2 * c + 0.4)))).toInt().coerceIn(0, 100)
        return RecoveryResult(
            score,
            hrv == null || rhr == null,
            RecoveryZone.of(score),
            terms.sortedByDescending { abs(it.weight * it.c.z) }.take(2).map { it.c },
        )
    }
}

object StrainTarget {
    fun forRecovery(score: Int): ClosedFloatingPointRange<Double> = when (RecoveryZone.of(score)) {
        RecoveryZone.GREEN -> 14.0..18.0
        RecoveryZone.AMBER -> 10.0..14.0
        RecoveryZone.RED -> 0.0..10.0
    }
}
