package dev.baseline.scores

import dev.baseline.data.SportFamily
import dev.baseline.data.db.LoadSource
import dev.baseline.fit.ActivityStreams
import dev.baseline.prefs.BanisterSet
import kotlin.math.exp
import kotlin.math.min

/** Samples with a missing (-1) or non-positive bpm are dropped. */
fun ActivityStreams.toHrPoints(): List<HrPoint> = epochSec.indices.filter { hr[it] > 0 }.map { HrPoint(epochSec[it], hr[it]) }

object Trimp {
    /**
     * Banister TRIMP. Each sample `a` is paired with the next valid sample: the gap (capped at [maxGapSec]) is
     * attributed to a's bpm, and the last sample contributes nothing. With [fixedDtSec] every sample, last
     * included, contributes that fixed step. Samples below [floorBpm] are dropped before pairing.
     * Contract: hrMax <= hrRest returns 0.0; callers must validate the HR zones.
     */
    fun load(points: List<HrPoint>, hrRest: Int, hrMax: Int, set: BanisterSet, maxGapSec: Int = 10, floorBpm: Int? = null, fixedDtSec: Int? = null): Double {
        val pts = if (floorBpm == null) points else points.filter { it.bpm >= floorBpm }
        val reserve = (hrMax - hrRest).toDouble()
        if (reserve <= 0.0) return 0.0

        fun effort(a: HrPoint): Double {
            val r = ((a.bpm - hrRest) / reserve).coerceIn(0.0, 1.0)
            return r * set.a * exp(set.b * r)
        }
        if (fixedDtSec != null) return pts.sumOf { fixedDtSec / 60.0 * effort(it) }
        return pts.zipWithNext().sumOf { (a, b) -> min(b.epochSec - a.epochSec, maxGapSec.toLong()).coerceAtLeast(0) / 60.0 * effort(a) }
    }

    /** Sum of capped gaps between consecutive points over [durationSec], clamped to 0..1; 0 when durationSec <= 0. */
    fun coverage(points: List<HrPoint>, durationSec: Double, maxGapSec: Int = 10): Double {
        if (durationSec <= 0.0) return 0.0
        val covered = points.zipWithNext().sumOf { (a, b) -> min(b.epochSec - a.epochSec, maxGapSec.toLong()).coerceAtLeast(0) }
        return (covered / durationSec).coerceIn(0.0, 1.0)
    }
}

object NonActivityLoad {
    /** All-day 2-min HR; samples inside any (inclusive) window or below [zone1FloorBpm] are excluded, each counts 120 s. */
    fun compute(day: List<HrPoint>, activityWindows: List<LongRange>, zone1FloorBpm: Int, hrRest: Int, hrMax: Int, set: BanisterSet): Double = Trimp.load(
        day.filter { p -> activityWindows.none { p.epochSec in it } },
        hrRest,
        hrMax,
        set,
        floorBpm = zone1FloorBpm,
        fixedDtSec = 120,
    )
}

data class ResolvedLoad(val load: Double, val source: LoadSource)

data class LoadSample(val family: SportFamily, val trimp: Double, val coverage: Double, val garminLoad: Double?)

object ActivityLoad {
    const val MIN_COVERAGE = 0.6

    /** HR path when coverage >= 0.6, else `garminLoad * k`; null when neither is possible. */
    fun resolve(trimp: Double?, coverage: Double?, garminLoad: Double?, k: Double?): ResolvedLoad? = when {
        trimp != null && coverage != null && coverage >= MIN_COVERAGE -> ResolvedLoad(trimp, LoadSource.HR)
        garminLoad != null && k != null -> ResolvedLoad(garminLoad * k, LoadSource.GARMIN_SCALED)
        else -> null
    }

    /**
     * Per-family median of trimp/garminLoad over samples with coverage >= 0.6 and garminLoad > 10. Families present
     * (or absent from [samples], e.g. a family whose activities all lack a FIT) with no qualifying sample get the global median, so every SportFamily has a key. Empty map when nothing qualifies at all; the
     * caller then falls back to k = 1.0 (Task 14).
     */
    fun scalingFactors(samples: List<LoadSample>): Map<SportFamily, Double> {
        val ratios =
            samples
                .filter { it.coverage >= MIN_COVERAGE && (it.garminLoad ?: 0.0) > 10.0 }
                .map { it.family to it.trimp / it.garminLoad!! }
        if (ratios.isEmpty()) return emptyMap()
        val global = median(ratios.map { it.second })
        val perFamily = ratios.groupBy({ it.first }, { it.second }).mapValues { median(it.value) }
        return SportFamily.entries.associateWith { perFamily[it] ?: global }
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }
}
