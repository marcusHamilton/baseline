package dev.baseline.ui.activity

import dev.baseline.fit.ActivityStreams
import java.time.Instant
import kotlin.math.floor

/** [elevChangeM] and [avgHr] are null when the stream has no altitude (heart rate) in the split. */
internal class RawSplit(val distanceM: Double, val seconds: Double, val avgHr: Int?, val elevChangeM: Double?)

// A gap this long with (almost) no distance covered is a pause: timer stopped, no records written.
private const val PAUSE_GAP_SEC = 30L
private const val PAUSE_DISTANCE_M = 5.0
private const val MIN_REMAINDER_M = 10.0
private const val VALID_ABOVE = -1e8f // ActivityStreams.MISSING is -1e9; a real altitude never gets near this

/** A gap this long with this little distance covered between two samples is a pause, not riding. */
internal fun isPause(gapSec: Long, movedM: Double) = gapSec > PAUSE_GAP_SEC && movedM < PAUSE_DISTANCE_M

/** False for the stream's MISSING sentinel. */
internal fun Float.valid() = this > VALID_ABOVE

/**
 * Per-[unitM] splits (plus a final remainder of at least 10 m) from the distance stream. Times are interpolated at each
 * boundary and exclude pauses, so a stop at a traffic light does not wreck a split. Empty without a distance stream.
 */
internal fun computeSplits(s: ActivityStreams, unitM: Double): List<RawSplit> {
    val dist = s.distanceM ?: return emptyList()
    val idx = ArrayList<Int>()
    var last = -1f
    for (i in dist.indices) {
        if (dist[i].valid() && dist[i] >= last) {
            idx += i
            last = dist[i]
        }
    }
    if (idx.size < 2) return emptyList()
    val d = DoubleArray(idx.size) { dist[idx[it]].toDouble() }
    val t = DoubleArray(idx.size)
    for (k in 1 until idx.size) {
        val dt = s.epochSec[idx[k]] - s.epochSec[idx[k - 1]]
        val paused = isPause(dt, d[k] - d[k - 1])
        t[k] = t[k - 1] + if (paused) 0.0 else dt.toDouble()
    }
    val total = d.last()
    val full = floor(total / unitM).toInt()
    val bounds = (1..full).map { it * unitM }.toMutableList()
    if (total - full * unitM >= MIN_REMAINDER_M) bounds += total
    if (bounds.isEmpty()) return emptyList()

    /** Index of the segment [j, j+1] holding distance [x]. */
    fun segment(x: Double): Int {
        var j = d.binarySearch(x).let { if (it >= 0) it else -it - 2 }
        j = j.coerceIn(0, d.size - 2)
        while (j < d.size - 2 && d[j + 1] <= x) j++ // a repeated distance (standing still): take the last one
        return j
    }
    fun at(x: Double, v: (Int) -> Double?): Double? {
        val j = segment(x)
        val a = v(j)
        val b = v(j + 1)
        val span = d[j + 1] - d[j]
        return when {
            a == null || b == null -> a ?: b
            span <= 0 -> b
            else -> a + (b - a) * ((x - d[j]) / span).coerceIn(0.0, 1.0)
        }
    }
    val alt = s.altitudeM
    val altAt = { k: Int -> alt?.get(idx[k])?.takeIf { it.valid() }?.toDouble() }
    val hrSum = LongArray(bounds.size)
    val hrCount = IntArray(bounds.size)
    for (k in idx.indices) {
        val h = s.hr[idx[k]]
        val n = if (h > 0) bounds.indexOfFirst { d[k] <= it } else -1
        if (n >= 0) {
            hrSum[n] += h
            hrCount[n]++
        }
    }

    var prev = 0.0
    var prevT = 0.0
    var prevAlt = idx.indices.firstNotNullOfOrNull(altAt) // the first *valid* altitude, not necessarily the first record's
    return bounds.mapIndexed { n, b ->
        val tb = at(b) { t[it] } ?: prevT
        val ab = alt?.let { at(b, altAt) }
        RawSplit(
            b - prev,
            tb - prevT,
            if (hrCount[n] > 0) Math.round(hrSum[n].toDouble() / hrCount[n]).toInt() else null,
            if (ab != null && prevAlt != null) ab - prevAlt else null,
        ).also {
            prev = b
            prevT = tb
            prevAlt = ab
        }
    }
}

/**
 * At most [max] points that keep the shape: the first and last, and the lowest and highest [value] of each bucket, in
 * original order. Smaller inputs come back unchanged.
 */
internal fun <T> downsample(points: List<T>, max: Int = 600, value: (T) -> Double): List<T> {
    if (points.size <= max || max < 4) return points
    val inner = points.size - 2
    val buckets = (max - 2) / 2
    val keep = sortedSetOf(0, points.size - 1)
    for (b in 0 until buckets) {
        val from = 1 + b * inner / buckets
        val to = 1 + (b + 1) * inner / buckets
        if (from >= to) continue
        keep += (from until to).minBy { value(points[it]) }
        keep += (from until to).maxBy { value(points[it]) }
    }
    return keep.map { points[it] }
}

/** Both coordinates present and plausible; MISSING (-1e9) and the (0, 0) of a GPS that never fixed are dropped. */
internal fun routePoints(s: ActivityStreams): List<Pair<Double, Double>> {
    val lat = s.lat ?: return emptyList()
    val lon = s.lon ?: return emptyList()
    return lat.indices.mapNotNull { i ->
        val a = lat[i]
        val o = lon[i]
        if (a in -90.0..90.0 && o in -180.0..180.0 && !(a == 0.0 && o == 0.0)) a to o else null
    }
}

/**
 * (time, value) for every record whose [value] is non-null, at most 600 of them. By default thinned by [downsample]
 * (keeps peaks); with [average] each bucket becomes its mean (for noisy signals such as speed, where min/max
 * buckets would read as a 0-to-peak band).
 */
internal fun series(s: ActivityStreams, average: Boolean = false, value: (Int) -> Double?): List<Pair<Instant, Number>> =
    s.epochSec.indices.mapNotNull { i -> value(i)?.let { Instant.ofEpochSecond(s.epochSec[i]) to it } }
        .let { pts -> if (average) downsampleMean(pts, 600) else downsample(pts, 600) { it.second } }

private const val SMOOTH_WINDOW = 5 // buckets; about two minutes on a long ride

/**
 * At most [max] points: consecutive chunks replaced by their mean value at the chunk's middle time, then a short centred
 * rolling mean so stop-and-go riding reads as a curve rather than a 0-to-peak band.
 */
internal fun downsampleMean(points: List<Pair<Instant, Double>>, max: Int = 600): List<Pair<Instant, Double>> {
    if (points.size <= max) return points
    val step = Math.ceil(points.size / max.toDouble()).toInt()
    val means = points.chunked(step).map { c -> c[c.size / 2].first to c.sumOf { it.second } / c.size }
    val half = SMOOTH_WINDOW / 2
    return means.mapIndexed { i, (t, _) ->
        val w = means.subList(maxOf(0, i - half), minOf(means.size, i + half + 1))
        t to w.sumOf { it.second } / w.size
    }
}

internal class RunningDynamics(val cadenceSpm: Int?, val powerW: Int?, val strideM: Double?)

private const val PER_LEG_BELOW = 120 // FIT running cadence is strides per minute for one leg (70-100); steps are 150-200

/**
 * Cadence in steps per minute (doubled when the stream looks per-leg), average power, and stride length
 * (distance covered while stepping / steps taken). Zero and missing cadence (standing still) are ignored.
 */
internal fun runningDynamics(s: ActivityStreams, avgPower: Double?): RunningDynamics {
    val cad = s.cadence
    val dist = s.distanceM
    val power = s.powerW?.filter { it > 0 }?.takeIf { it.isNotEmpty() }?.average()
    val powerW = (avgPower?.takeIf { it.isFinite() && it > 0 } ?: power)?.let { Math.round(it).toInt() }
    val moving = cad?.indices?.filter { cad[it] > 0 }.orEmpty()
    if (cad == null || moving.isEmpty()) return RunningDynamics(null, powerW, null)
    val median = moving.map { cad[it] }.sorted()[moving.size / 2]
    val factor = if (median < PER_LEG_BELOW) 2 else 1
    var steps = 0.0
    var metres = 0.0
    for (i in 1 until cad.size) {
        val dt = s.epochSec[i] - s.epochSec[i - 1]
        if (dt <= 0 || dt > PAUSE_GAP_SEC || cad[i] <= 0) continue
        steps += cad[i] * factor * dt / 60.0
        val a = dist?.get(i - 1)
        val b = dist?.get(i)
        if (a != null && b != null && a.valid() && b.valid() && b >= a) metres += b - a
    }
    return RunningDynamics(
        Math.round(moving.sumOf { cad[it] } * factor / moving.size.toDouble()).toInt(),
        powerW,
        if (steps > 0 && metres > 0) metres / steps else null,
    )
}
