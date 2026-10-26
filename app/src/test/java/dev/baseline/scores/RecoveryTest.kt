package dev.baseline.scores

import dev.baseline.data.db.DayEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp

class RecoveryTest {
    private val b = Baseline(lnHrvMean = 4.0, lnHrvSd = 0.2, rhrMean = 50.0, rhrSd = 4.0, respMean = 14.0, respSd = 1.0, nights = 30)
    private val d = LocalDate.of(2026, 10, 24)

    private fun avgDay() = DayEntity(d, restingHr = 50, respirationAvg = 14.0, hrvLastNight = exp(4.0))

    private fun logistic(c: Double) = Math.round(100.0 / (1 + exp(-(1.2 * c + 0.4)))).toInt()

    @Test fun averageDayIsAbout60() {
        val r = Recovery.compute(avgDay(), b, 0.85)!!
        assertEquals(60, r.score)
        assertEquals(logistic(0.0), r.score)
        assertEquals(false, r.partial)
        assertEquals(RecoveryZone.AMBER, r.zone)
    }

    @Test fun zonesAtBoundaries() {
        assertEquals(RecoveryZone.GREEN, RecoveryZone.of(67))
        assertEquals(RecoveryZone.AMBER, RecoveryZone.of(66))
        assertEquals(RecoveryZone.AMBER, RecoveryZone.of(34))
        assertEquals(RecoveryZone.RED, RecoveryZone.of(33))
    }

    @Test fun missingHrvIsPartialAndRenormalised() {
        val r = Recovery.compute(avgDay().copy(hrvLastNight = null, restingHr = 46), b, 0.85)!!
        assertTrue(r.partial)
        assertEquals(73, r.score) // c = 0.25 / 0.5 = 0.5
        assertEquals(logistic(0.5), r.score)
        assertTrue(r.top.none { it.metric == "hrv" })
    }

    @Test fun droppedRespIsNotPartial() {
        val r = Recovery.compute(avgDay().copy(respirationAvg = null), b, 0.85)!!
        assertEquals(false, r.partial)
        assertEquals(60, r.score)
        val noBaseResp = Recovery.compute(avgDay(), b.copy(respMean = null, respSd = null), 0.85)!!
        assertEquals(false, noBaseResp.partial)
    }

    @Test fun missingRhrIsPartial() {
        val r = Recovery.compute(avgDay().copy(restingHr = null), b, 0.85)!!
        assertTrue(r.partial)
        assertTrue(r.top.none { it.metric == "rhr" })
    }

    @Test fun upperSleepClampIs2() {
        val r = Recovery.compute(avgDay(), b, 1.5)!!
        assertEquals(2.0, r.top.first { it.metric == "sleep" }.z, 1e-9)
        assertEquals(71, r.score) // c = 0.2 * 2 / 1.0 = 0.4
    }

    @Test fun droppedRespRenormalisesWithNonZeroZ() {
        // z_rhr = 2 (rhr 42), weights 0.5 + 0.25 + 0.2 = 0.95, c = 0.5 / 0.95 -> 74 (dividing by 1.0 would give 73)
        val r = Recovery.compute(avgDay().copy(restingHr = 42), b.copy(respMean = null, respSd = null), 0.85)!!
        assertEquals(74, r.score)
        assertEquals(false, r.partial)
    }

    @Test fun baselineRejectsNonPositiveSd() {
        assertThrows(IllegalArgumentException::class.java) { b.copy(rhrSd = 0.0) }
    }

    @Test fun nonPositiveInputsAreDropped() {
        val r = Recovery.compute(avgDay().copy(hrvLastNight = 0.0, restingHr = 0), b, 0.85)!!
        assertTrue(r.partial)
        assertTrue(r.top.none { it.metric == "hrv" || it.metric == "rhr" })
    }

    @Test fun respirationOnlyHurtsWhenHigher() {
        val base = Recovery.compute(avgDay(), b, 0.85)!!.score
        val lower = Recovery.compute(avgDay().copy(respirationAvg = 10.0), b, 0.85)!!.score
        val higher = Recovery.compute(avgDay().copy(respirationAvg = 20.0), b, 0.85)!!.score
        assertEquals(base, lower)
        assertTrue(higher < base)
        // z_resp = -6, c = 0.05 * -6 / 1.0
        assertEquals(51, higher)
    }

    @Test fun noSleepNoRecovery() {
        assertNull(Recovery.compute(avgDay(), b, null))
        assertNull(Recovery.compute(avgDay(), null, 0.85))
    }

    @Test fun topContributorsAreTwoLargest() {
        // hrv z = +1 (0.50), rhr z = -2 (0.50 after weight 0.25), sleep z = +1 (0.20), resp 0
        val day = avgDay().copy(hrvLastNight = exp(4.2), restingHr = 58)
        val r = Recovery.compute(day, b, 0.95)!!
        assertEquals(2, r.top.size)
        assertEquals(setOf("hrv", "rhr"), r.top.map { it.metric }.toSet())
        val rhr = r.top.first { it.metric == "rhr" }
        assertEquals(-2.0, rhr.z, 1e-9)
        assertEquals(8.0, rhr.delta, 1e-9)
        val hrv = r.top.first { it.metric == "hrv" }
        assertEquals(1.0, hrv.z, 1e-9)
        assertEquals((exp(0.2) - 1) * 100, hrv.delta, 1e-9)
    }

    @Test fun topIsOrderedByAbsWeightedZ() {
        val r = Recovery.compute(avgDay().copy(restingHr = 54), b, 0.85)!! // rhr -0.25
        assertEquals("rhr", r.top[0].metric)
        val s = Recovery.compute(avgDay(), b, 0.65)!! // sleep z = -2 -> 0.4
        assertEquals("sleep", s.top[0].metric)
        assertEquals(-20.0, s.top[0].delta, 1e-9)
    }

    @Test fun sleepZIsClamped() {
        val hi = Recovery.compute(avgDay(), b, 1.0)!!.top.first { it.metric == "sleep" }
        assertEquals(1.5, hi.z, 1e-9)
        val lo = Recovery.compute(avgDay(), b, 0.01)!!.top.first { it.metric == "sleep" }
        assertEquals(-3.0, lo.z, 1e-9)
    }

    @Test fun extremeInputsStayFiniteAndInRange() {
        val cases =
            listOf(
                avgDay().copy(hrvLastNight = 1e300),
                avgDay().copy(hrvLastNight = 1e-300),
                avgDay().copy(restingHr = 0),
                avgDay().copy(restingHr = Int.MAX_VALUE),
                avgDay().copy(hrvLastNight = Double.POSITIVE_INFINITY),
                avgDay().copy(respirationAvg = Double.NaN),
                avgDay().copy(respirationAvg = 1e300),
            )
        for (day in cases) {
            for (sp in listOf(0.0, 1.0)) {
                val r = Recovery.compute(day, b, sp)!!
                assertTrue(r.score in 0..100)
                assertTrue(r.top.all { it.z.isFinite() && it.delta.isFinite() })
            }
        }
        val tiny = Recovery.compute(avgDay().copy(hrvLastNight = 1e-300, restingHr = Int.MAX_VALUE), b, 0.0)!!
        assertEquals(0, tiny.score)
        val huge = Recovery.compute(avgDay().copy(hrvLastNight = 1e300), b, 1.0)!!
        assertEquals(100, huge.score)
    }

    @Test fun sleepPerformanceCapsAt1AndNullSafe() {
        assertEquals(1.0, SleepPerformance.of(31740, 490)!!, 0.0)
        assertEquals(0.5, SleepPerformance.of(14700, 490)!!, 1e-12)
        assertNull(SleepPerformance.of(null, 490))
        assertNull(SleepPerformance.of(100, null))
        assertNull(SleepPerformance.of(100, 0))
        assertNull(SleepPerformance.of(0, 490))
        assertNull(SleepPerformance.of(-5, 490))
    }

    @Test fun strainTargets() {
        assertEquals(14.0..18.0, StrainTarget.forRecovery(67))
        assertEquals(10.0..14.0, StrainTarget.forRecovery(50))
        assertEquals(0.0..10.0, StrainTarget.forRecovery(33))
    }
}
