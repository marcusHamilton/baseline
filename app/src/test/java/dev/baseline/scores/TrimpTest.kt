package dev.baseline.scores

import dev.baseline.data.SportFamily
import dev.baseline.data.db.LoadSource
import dev.baseline.data.parse.DayParser
import dev.baseline.data.parse.fixture
import dev.baseline.fit.FitReader
import dev.baseline.fit.fitFixture
import dev.baseline.prefs.BanisterSet.FEMALE_FITTED
import dev.baseline.prefs.BanisterSet.MALE_FITTED
import kotlin.math.exp
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimpTest {
    private fun effort(bpm: Int, a: Double = 0.64, b: Double = 1.92): Double {
        val r = (bpm - 44) / 161.0
        return r * a * exp(b * r)
    }

    @Test fun trimpOfConstantHr() { // 61 points 1 s apart = 1 min at bpm 124 (last sample contributes nothing)
        val pts = (0 until 61).map { HrPoint(it.toLong(), 124) }
        assertEquals(effort(124), Trimp.load(pts, 44, 205, MALE_FITTED), 1e-6)
        assertEquals(60 / 60.0 * effort(124, 0.86, 1.67), Trimp.load(pts, 44, 205, FEMALE_FITTED), 1e-6)
    }

    @Test fun gapsCappedAt10s() {
        val pts = listOf(HrPoint(0, 124), HrPoint(30, 124))
        assertEquals(10 / 60.0 * effort(124), Trimp.load(pts, 44, 205, MALE_FITTED), 1e-9)
        assertEquals(10 / 30.0, Trimp.coverage(pts, 30.0), 1e-9)
    }

    @Test fun gapAttributedToEarlierSample() {
        val pts = listOf(HrPoint(0, 100), HrPoint(5, 200))
        assertEquals(5 / 60.0 * effort(100), Trimp.load(pts, 44, 205, MALE_FITTED), 1e-9)
    }

    @Test fun reserveClampedToZeroAndOne() {
        val pts = listOf(HrPoint(0, 30), HrPoint(10, 250), HrPoint(20, 250))
        assertEquals(10 / 60.0 * 0.64 * exp(1.92), Trimp.load(pts, 44, 205, MALE_FITTED), 1e-9)
    }

    @Test fun fixedDtCountsEverySampleAndFloorDropsLow() {
        val pts = listOf(HrPoint(0, 100), HrPoint(1, 150), HrPoint(2, 120))
        assertEquals(2.0 * (effort(150) + effort(120)), Trimp.load(pts, 44, 205, MALE_FITTED, floorBpm = 110, fixedDtSec = 120), 1e-9)
    }

    @Test fun toHrPointsSkipsMissingHr() {
        val fit = FitReader.read(fitFixture("strength"))
        val pts = fit.streams.toHrPoints()
        assertEquals(fit.streams.hr.count { it > 0 }, pts.size)
        assertTrue(pts.all { it.bpm > 0 })
    }

    @Test fun strengthFixtureCoverageIs098AndTakesHrPath() {
        val fit = FitReader.read(fitFixture("strength"))
        val pts = fit.streams.toHrPoints()
        val cov = Trimp.coverage(pts, fit.durationSec)
        assertEquals(0.9803, cov, 1e-4)
        val trimp = Trimp.load(pts, 44, 205, MALE_FITTED)
        assertEquals(6.5786, trimp, 1e-4) // regression pin of the measured value
        assertEquals(LoadSource.HR, ActivityLoad.resolve(trimp, cov, 2.88, 1.0)!!.source)
    }

    @Test fun lowCoverageFallsBackToScaledGarmin() {
        assertEquals(ResolvedLoad(140.0, LoadSource.GARMIN_SCALED), ActivityLoad.resolve(5.0, 0.3, 14.0, 10.0))
        assertEquals(null, ActivityLoad.resolve(5.0, 0.3, null, 10.0))
        assertEquals(null, ActivityLoad.resolve(null, null, 14.0, null))
    }

    @Test fun scalingIgnoresTinyGarminLoads() {
        val s = listOf(
            LoadSample(SportFamily.STRENGTH, 50.0, 0.98, 3.0), // garmin load too small
            LoadSample(SportFamily.RUN, 200.0, 0.9, 100.0), // 2.0
            LoadSample(SportFamily.RUN, 300.0, 0.9, 100.0), // 3.0
            LoadSample(SportFamily.RUN, 900.0, 0.3, 100.0), // low coverage
            LoadSample(SportFamily.RIDE, 100.0, 0.9, 50.0), // 2.0
        )
        val f = ActivityLoad.scalingFactors(s)
        assertEquals(2.5, f[SportFamily.RUN]!!, 1e-9) // even-sized median
        assertEquals(2.0, f[SportFamily.RIDE]!!, 1e-9)
        assertEquals(2.0, f[SportFamily.STRENGTH]!!, 1e-9) // global median of 2.0, 3.0, 2.0 (STRENGTH has no qualifying sample)
        // SWIM/OTHER never appear in samples (no FIT) but still get the global median
        assertEquals(SportFamily.entries.toSet(), f.keys)
        assertEquals(2.0, f[SportFamily.SWIM]!!, 1e-9)
        assertEquals(2.0, f[SportFamily.OTHER]!!, 1e-9)
        assertEquals(emptyMap<SportFamily, Double>(), ActivityLoad.scalingFactors(listOf(s[0])))
    }

    @Test fun nonActivityExcludesWindowsAndBelowFloor() {
        val (_, day) = DayParser.parseHeartRateDay(fixture("heartrate_day.json").jsonObject)
        val t0 = day[0].epochSec
        val window = (t0 + 59700)..(t0 + 60100) // covers 3 of the 8 samples >= 103
        val expected = day.filter { it.epochSec !in window && it.bpm >= 103 }.sumOf { 2.0 * effort(it.bpm) }
        val all = NonActivityLoad.compute(day, emptyList(), 103, 44, 205, MALE_FITTED)
        val got = NonActivityLoad.compute(day, listOf(window), 103, 44, 205, MALE_FITTED)
        assertTrue(expected > 0)
        assertEquals(expected, got, 1e-9)
        assertTrue(all > got)
    }

    @Test fun windowsAreInclusive() {
        val pts = listOf(HrPoint(100, 150), HrPoint(200, 150), HrPoint(300, 150))
        assertEquals(2.0 * effort(150), NonActivityLoad.compute(pts, listOf(100L..200L), 103, 44, 205, MALE_FITTED), 1e-9)
    }

    @Test fun noNaNOnEmptyAndIdenticalInputs() {
        assertEquals(0.0, Trimp.load(emptyList(), 44, 205, MALE_FITTED), 0.0)
        assertEquals(0.0, Trimp.coverage(emptyList(), 0.0), 0.0)
        val same = List(5) { HrPoint(100, 100) } // identical timestamps and bpm
        val degenerate = listOf(
            Trimp.load(same, 44, 205, MALE_FITTED), Trimp.load(same, 100, 100, MALE_FITTED),
            Trimp.load(same, 44, 205, MALE_FITTED, fixedDtSec = 120), Trimp.coverage(same, 10.0), Trimp.coverage(same, 0.0),
            NonActivityLoad.compute(emptyList(), emptyList(), 103, 44, 205, MALE_FITTED),
            NonActivityLoad.compute(same, listOf(0L..1000L), 103, 44, 205, MALE_FITTED),
        )
        assertTrue(degenerate.all { it.isFinite() })
        assertTrue(ActivityLoad.scalingFactors(emptyList()).isEmpty())
        assertTrue(ActivityLoad.scalingFactors(List(3) { LoadSample(SportFamily.RUN, 10.0, 1.0, 20.0) }).values.all { it.isFinite() })
    }
}
