package dev.baseline.records

import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.BestEffortEntity
import dev.baseline.data.parse.ActivityParser
import dev.baseline.data.parse.fixture
import dev.baseline.fit.ActivityStreams
import dev.baseline.fit.FitReader
import dev.baseline.fit.SwimLength
import dev.baseline.fit.fitFixture
import dev.baseline.ui.activities.TODAY
import dev.baseline.ui.activities.act
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.records.buildRecordsState
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BestEffortsTest {
    private val missing = ActivityStreams.MISSING

    /** One record per second from epoch 0; [dist] and [power] map the second to a value. */
    private fun stream(n: Int, dist: ((Int) -> Float)? = null, power: ((Int) -> Int)? = null) = ActivityStreams(
        LongArray(n) { it.toLong() },
        IntArray(n) { -1 },
        dist?.let { f -> FloatArray(n) { f(it) } },
        null,
        power?.let { f -> IntArray(n) { f(it) } },
        null,
        null,
        null,
        null,
    )

    private fun splits(vararg kv: Pair<Int, Double>) = kv.joinToString(",", "{", "}") { "\"fastestSplit_${it.first}\":${it.second}" }

    private fun ride(typeKey: String = "road_biking", splitsJson: String? = null) = act(7, typeKey, distanceM = 50_000.0).copy(fastestSplitsJson = splitsJson)

    private fun swimmer(splitsJson: String? = null) = act(8, "lap_swimming", distanceM = 1000.0).copy(poolLengthM = 25.0, fastestSplitsJson = splitsJson)

    private fun len(sec: Double, active: Boolean = true) = SwimLength(0.0, sec, null, null, active)

    @Test fun runRecordsMatchFixture() {
        val fromGarmin = fixture("activities_all.json").jsonArray.map { ActivityParser.parseListItem(it.jsonObject) }
        val treadmill = act(1, "treadmill_running").copy(fastestSplitsJson = splits(5000 to 900.0)) // would win if it counted
        val r = BestEfforts.runFromSplits(fromGarmin + treadmill).associateBy { it.effort }
        assertEquals(1476.757, r.getValue("5000m").valueSec, 0.001)
        assertEquals(900000160L, r.getValue("5000m").activityId)
        assertEquals(SportFamily.RUN, r.getValue("5000m").sport)
        assertEquals(setOf("1000m", "1609m", "5000m", "10000m", "21098m", "42195m"), r.keys)
        assertTrue(r.values.none { it.activityId == 1L })
    }

    @Test fun fastestDistanceOnSyntheticStream() {
        val s = stream(800, dist = { if (it <= 600) 5f * it else 3000f + 10f * (it - 600) })
        assertEquals(100.0, BestEfforts.fastestDistance(s, 1000.0)!!, 0.01)
        assertEquals(50.0, BestEfforts.fastestDistance(s, 500.0)!!, 0.01)
        assertNull(BestEfforts.fastestDistance(s, 6000.0))
        assertNull(BestEfforts.fastestDistance(stream(10), 100.0)) // no distance stream
    }

    @Test fun fastestDistanceFindsWindowsThatStartOnASample() {
        // Only the mirrored pass sees this: the best 40 m starts at t=0 and ends 1/3 of the way to the last sample.
        val s =
            ActivityStreams(
                longArrayOf(0, 1, 11),
                IntArray(3) { -1 },
                floatArrayOf(0f, 30f, 60f),
                null,
                null,
                null,
                null,
                null,
                null,
            )
        assertEquals(4.333, BestEfforts.fastestDistance(s, 40.0)!!, 0.001)
    }

    @Test fun pausedTimeIsNotPartOfAnEffort() {
        // 12 km at 5 m/s with the records stopping for 20 minutes at 6 km: any 10 km window spans the stop.
        fun ride(stopSec: Long): ActivityStreams {
            // (time, distance): moving to 6 km, standing still until the timer restarts, then moving again.
            val pts =
                (0..1200).map { it.toLong() to 5f * it } +
                    (if (stopSec > 0) listOf(1200 + stopSec to 6000f) else emptyList()) +
                    (1201..2400).map { it + stopSec to 5f * it }
            return ActivityStreams(
                pts.map { it.first }.toLongArray(),
                IntArray(pts.size) { -1 },
                pts.map { it.second }.toFloatArray(),
                null,
                null,
                null,
                null,
                null,
                null,
            )
        }
        assertEquals(2000.0, BestEfforts.fastestDistance(ride(0), 10_000.0)!!, 0.01)
        assertEquals(2000.0, BestEfforts.fastestDistance(ride(1200), 10_000.0)!!, 0.01)
    }

    @Test fun gpsGlitchIgnored() {
        // 5 m/s with a 900 m jump in one second at t=100 (and no distance missing samples around it).
        val s = stream(400, dist = { if (it < 100) 5f * it else 5f * it + 900f })
        assertEquals(200.0, BestEfforts.fastestDistance(s, 1000.0)!!, 1.5)
    }

    @Test fun bestPowerOnSyntheticStream() {
        val s = stream(300, power = { if (it in 100..159) 400 else 200 })
        assertEquals(400, BestEfforts.bestPower(s, 60))
        assertEquals(240, BestEfforts.bestPower(s, 300))
        assertNull(BestEfforts.bestPower(s, 301))
    }

    @Test fun powerGapBreaksWindow() {
        // 30 s of data, a 10 s hole, 30 s of data: no 60 s window exists, but two seconds of dropout are carried forward.
        val gap =
            ActivityStreams(
                LongArray(60) { if (it < 30) it.toLong() else it + 10L },
                IntArray(60) { -1 },
                null,
                null,
                IntArray(60) { 300 },
                null,
                null,
                null,
                null,
            )
        assertNull(BestEfforts.bestPower(gap, 60))
        assertEquals(300, BestEfforts.bestPower(gap, 30))
        val every2s =
            ActivityStreams(
                LongArray(61) { it * 2L },
                IntArray(61) { -1 },
                null,
                null,
                IntArray(61) { 100 },
                null,
                null,
                null,
                null,
            )
        assertEquals(100, BestEfforts.bestPower(every2s, 60))
        // Valid zeros count; -1 samples do not.
        assertEquals(200, BestEfforts.bestPower(stream(60, power = { if (it < 30) 400 else 0 }), 60))
        assertNull(BestEfforts.bestPower(stream(60, power = { -1 }), 5))
        // One dropped sample is a 2 s gap between the valid ones, which is carried: the full 60 s window survives.
        assertEquals(300, BestEfforts.bestPower(stream(60, power = { if (it == 30) -1 else 300 }), 60))
    }

    @Test fun swimRestBreaksSequence() {
        val lengths = listOf(len(20.0), len(20.0), len(20.0), len(5.0, active = false), len(22.0), len(22.0), len(22.0), len(22.0))
        assertEquals(88.0, BestEfforts.fastestSwim(lengths, 25.0, 100)!!, 0.001)
        assertEquals(60.0, BestEfforts.fastestSwim(lengths, 25.0, 75)!!, 0.001)
        assertNull(BestEfforts.fastestSwim(lengths, 25.0, 400))
        assertNull(BestEfforts.fastestSwim(lengths, 22.86, 100)) // a 25 yd pool does not divide 100 m
    }

    @Test fun virtualRideCountsForPowerNotDistance() {
        val s = FitReader.read(fitFixture("virtual_ride")).streams
        val r = BestEfforts.rideEfforts(ride("virtual_ride"), s)
        assertTrue(r.any { it.effort == "power_5s" })
        assertTrue(r.none { it.effort.endsWith("m") })
        assertTrue(r.all { it.sport == SportFamily.RIDE })
        val road = BestEfforts.rideEfforts(ride(), FitReader.read(fitFixture("road_ride")).streams)
        assertNotNull(road.firstOrNull { it.effort == "10000m" })
        assertTrue(road.none { it.effort.startsWith("power_") }) // road_ride.fit has no power
    }

    @Test fun fallbackToGarminSplitWithoutStream() {
        val json = splits(40000 to 3600.0, 100 to 70.0)
        assertEquals(3600.0, BestEfforts.rideEfforts(ride(splitsJson = json), null).single().valueSec, 0.0)
        // With a stream the stream wins, even when Garmin's split is faster.
        val s = stream(800, dist = { 10f * it }) // 40 km is not reached
        assertTrue(BestEfforts.rideEfforts(ride(splitsJson = splits(40000 to 10.0)), s).none { it.effort == "40000m" })
        assertEquals(70.0, BestEfforts.swimEfforts(swimmer(json), emptyList()).single().valueSec, 0.0)
        assertTrue(BestEfforts.swimEfforts(swimmer(splits(100 to 10.0)), List(2) { len(30.0) }).isEmpty())
    }

    @Test fun newBadgeWithin30Days() {
        fun e(effort: String, daysAgo: Long) = BestEffortEntity(SportFamily.RUN, effort, 1476.757, 1, TODAY.minusDays(daysAgo))
        val s =
            buildRecordsState(
                SportFamily.RUN,
                listOf(e("5000m", 30), e("10000m", 31)),
                listOf(act(1)),
                TODAY,
                Formatters(false),
            )
        val byLabel = s.rows.associateBy { it.label }
        assertTrue(byLabel.getValue("5K").isNew)
        assertFalse(byLabel.getValue("10K").isNew)
        assertEquals("24:37", byLabel.getValue("5K").time)
        assertEquals("—", byLabel.getValue("1 km").time) // no record yet
        assertNull(byLabel.getValue("1 km").activityId)
    }
}
