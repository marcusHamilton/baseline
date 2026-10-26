package dev.baseline.scores

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln

/** [provisional]: made while the backfill was still pending, so it is replaced once the backfill completes. */
data class StrainCalibration(val tau: Double, val version: Int, val provisional: Boolean = false)

object Strain {
    /** `tau = p90 / ln(21/4)` with p90 by nearest rank. Empty list or p90 <= 0 gives tau = 1.0 (never divides by zero). */
    fun calibrate(dayLoads: List<Double>, version: Int): StrainCalibration {
        val p90 = dayLoads.sorted().let { if (it.isEmpty()) 0.0 else it[ceil(0.9 * it.size).toInt() - 1] }
        return StrainCalibration(if (p90 > 0) p90 / ln(21.0 / 4.0) else 1.0, version)
    }

    /** `21 * (1 - exp(-load/tau))` rounded to 0.1 and clamped to 0..21. */
    fun of(load: Double, cal: StrainCalibration): Double =
        (Math.round(21.0 * (1 - exp(-load / cal.tau)) * 10) / 10.0).coerceIn(0.0, 21.0)
}
