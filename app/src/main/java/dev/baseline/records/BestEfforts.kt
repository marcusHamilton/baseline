package dev.baseline.records

import dev.baseline.data.SportFamily
import dev.baseline.data.countsForDistanceRecords
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.BestEffortEntity
import dev.baseline.fit.ActivityStreams
import dev.baseline.fit.SwimLength
import dev.baseline.ui.activity.isPause
import dev.baseline.ui.activity.valid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** All-time best efforts. Keys are "<metres>m" and "power_<seconds>s"; power rows hold watts in `valueSec`. */
object BestEfforts {
    val RUN_M = listOf(1000, 1609, 5000, 10000, 21098, 42195)
    val RIDE_M = listOf(10000, 20000, 40000, 100000)
    val SWIM_M = listOf(100, 400, 1500)
    val POWER_SEC = listOf(5, 60, 300, 1200, 3600)

    fun distanceKey(meters: Int) = "${meters}m"
    fun powerKey(seconds: Int) = "power_${seconds}s"
    fun isPower(effort: String) = effort.startsWith("power_")

    /** Faster than any runner or cyclist; a sample pair implying more is a GPS jump. */
    private const val MAX_SPEED_MS = 35.0

    /** A power sample is held for this many seconds at most; a longer silence ends the window. */
    private const val MAX_CARRY_SEC = 3L

    private const val SPLIT_PREFIX = "fastestSplit_"

    private fun effort(a: ActivityEntity, key: String, value: Double) = BestEffortEntity(a.sportFamily, key, value, a.id, a.day)

    /** Garmin's own fastest splits as metres to seconds; unparseable JSON gives an empty map. */
    private fun splits(a: ActivityEntity): Map<Int, Double> =
        a.fastestSplitsJson?.let { json -> runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() }
            ?.mapNotNull { (k, v) ->
                val m = k.takeIf { it.startsWith(SPLIT_PREFIX) }?.substring(SPLIT_PREFIX.length)?.toIntOrNull()
                val sec = (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
                if (m != null && sec != null) m to sec else null
            }?.toMap().orEmpty()

    /** The fastest of each run distance across [activities], from Garmin's splits; treadmill runs do not count. */
    fun runFromSplits(activities: List<ActivityEntity>): List<BestEffortEntity> = best(
        activities.filter { it.sportFamily == SportFamily.RUN && it.sportFamily.countsForDistanceRecords(it.typeKey) }
            .flatMap { a -> splits(a).filterKeys { it in RUN_M }.map { (m, sec) -> effort(a, distanceKey(m), sec) } },
    )

    /**
     * One ride's efforts. Power counts for any ride with a power stream, distance only outdoors. Without a stream
     * (FIT not downloaded yet) the 40 km falls back to Garmin's split; once there is a stream that split is ignored.
     */
    fun rideEfforts(a: ActivityEntity, s: ActivityStreams?): List<BestEffortEntity> {
        if (a.sportFamily != SportFamily.RIDE) return emptyList()
        val out = mutableListOf<BestEffortEntity>()
        if (a.sportFamily.countsForDistanceRecords(a.typeKey)) {
            if (s == null) {
                splits(a)[40000]?.let { out += effort(a, distanceKey(40000), it) }
            } else {
                RIDE_M.forEach { m -> fastestDistance(s, m.toDouble())?.let { out += effort(a, distanceKey(m), it) } }
            }
        }
        if (s != null) POWER_SEC.forEach { w -> bestPower(s, w)?.let { out += effort(a, powerKey(w), it.toDouble()) } }
        return out
    }

    /** One pool swim's efforts; with no lengths yet the 100 m falls back to Garmin's split. */
    fun swimEfforts(a: ActivityEntity, lengths: List<SwimLength>): List<BestEffortEntity> {
        val pool = a.poolLengthM
        if (a.typeKey != "lap_swimming" || pool == null) return emptyList()
        if (lengths.isEmpty()) return listOfNotNull(splits(a)[100]?.let { effort(a, distanceKey(100), it) })
        return SWIM_M.mapNotNull { m -> fastestSwim(lengths, pool, m)?.let { effort(a, distanceKey(m), it) } }
    }

    /** One row per (sport, effort): the fastest time or, for power, the most watts; the earliest activity wins a tie. */
    fun best(all: List<BestEffortEntity>): List<BestEffortEntity> = all.sortedBy { it.achievedOn }.groupBy { it.sport to it.effort }.values.map { g ->
        if (isPower(g[0].effort)) g.maxBy { it.valueSec } else g.minBy { it.valueSec }
    }

    /**
     * The least time over any stretch covering at least [meters], with the boundary interpolated; null when the
     * stream has no distance or is shorter. Cumulative distance is rebuilt from per-sample deltas, and a delta that
     * is negative, has no time behind it or implies more than 35 m/s counts as 0 m (the time still counts). Paused
     * time (StreamMath's definition) is cut out of the time axis, so a stop mid-effort does not slow it.
     */
    fun fastestDistance(s: ActivityStreams, meters: Double): Double? {
        val dist = s.distanceM
        if (dist == null || meters <= 0) return null
        val t = ArrayList<Double>()
        val d = ArrayList<Double>()
        var cum = 0.0
        var clock = 0.0
        var lastT = 0L
        var lastD = 0f
        for (i in dist.indices) {
            if (!dist[i].valid()) continue
            val ti = s.epochSec[i]
            if (t.isNotEmpty()) {
                val dt = ti - lastT
                val dd = (dist[i] - lastD).toDouble()
                if (dt > 0 && dd >= 0 && dd / dt <= MAX_SPEED_MS) cum += dd
                if (dt > 0 && !isPause(dt, dd)) clock += dt
            }
            t += clock
            d += cum
            lastT = ti
            lastD = dist[i]
        }
        if (t.size < 2) return null
        val fwd = shortestEndingAtSample(t, d, meters)
        // Mirrored in time, so windows that start on a sample (and end between two) are covered as well.
        val back = shortestEndingAtSample(t.map { -it }.asReversed(), d.map { cum - it }.asReversed(), meters)
        return listOfNotNull(fwd, back).minOrNull()
    }

    private fun shortestEndingAtSample(t: List<Double>, d: List<Double>, m: Double): Double? {
        var best: Double? = null
        var i = 0
        for (j in d.indices) {
            val target = d[j] - m
            if (target < d[0]) continue
            while (i + 1 < d.size && d[i + 1] <= target) i++
            val start = t[i] + (target - d[i]) / (d[i + 1] - d[i]) * (t[i + 1] - t[i])
            best = best?.let { minOf(it, t[j] - start) } ?: (t[j] - start)
        }
        return best
    }

    /**
     * The highest mean watts over [windowSec] seconds, on a 1 s grid. A sample is held until the next one when that is
     * [MAX_CARRY_SEC] or less away; a longer silence splits the stream and no window spans the split. A missing
     * sample (-1) is skipped, so the dropout is just a longer gap between the valid ones: carried when it comes to 3 s
     * or less, a split otherwise. 0 W is a valid reading. Null without a power stream or a long enough unbroken stretch.
     */
    fun bestPower(s: ActivityStreams, windowSec: Int): Int? {
        val p = s.powerW
        if (p == null || windowSec <= 0) return null
        var best = -1.0
        val seg = ArrayList<Int>() // watts for each second of the current unbroken stretch
        fun close() {
            var sum = 0L
            for (k in seg.indices) {
                sum += seg[k]
                if (k >= windowSec) sum -= seg[k - windowSec]
                if (k >= windowSec - 1) best = max(best, sum.toDouble() / windowSec)
            }
            seg.clear()
        }
        var prevT = 0L
        var prevP = -1
        for (i in p.indices) {
            if (p[i] < 0) continue
            if (prevP >= 0) {
                val gap = s.epochSec[i] - prevT
                when {
                    gap == 0L -> {}

                    // a duplicate second: the later reading replaces it
                    gap in 1..MAX_CARRY_SEC -> repeat(gap.toInt()) { seg += prevP }

                    else -> {
                        seg += prevP
                        close()
                    }
                }
            }
            prevT = s.epochSec[i]
            prevP = p[i]
        }
        if (prevP >= 0) {
            seg += prevP
            close()
        }
        return if (best >= 0) best.roundToInt() else null
    }

    /**
     * The least time over consecutive active lengths adding up to [targetM]; a rest length breaks the run. Null when
     * the pool does not divide the distance (a 25 yd pool and 100 m), or there is no such run.
     */
    fun fastestSwim(lengths: List<SwimLength>, poolLengthM: Double, targetM: Int): Double? {
        if (poolLengthM <= 0) return null
        val n = (targetM / poolLengthM).roundToInt()
        if (n < 1 || abs(n * poolLengthM - targetM) > 0.5) return null
        return lengths.windowed(n).filter { w -> w.all { it.active && it.durationSec > 0 } }.minOfOrNull { w -> w.sumOf { it.durationSec } }
    }
}
