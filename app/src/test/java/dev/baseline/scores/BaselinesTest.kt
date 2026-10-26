package dev.baseline.scores

import dev.baseline.data.db.DayEntity
import java.time.LocalDate
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselinesTest {
    private val d = LocalDate.of(2026, 10, 24)
    private fun night(ago: Long, hrv: Double? = 50.0, rhr: Int? = 50, resp: Double? = null) =
        DayEntity(date = d.minusDays(ago), hrvLastNight = hrv, restingHr = rhr, respirationAvg = resp)

    @Test fun baselineNeedsSevenNightsAndFloorsSd() {
        assertNull(Baselines.at(d, (1L..6).map { night(it) }))
        val b = Baselines.at(d, (1L..7).map { night(it) })!!
        assertEquals(7, b.nights)
        assertEquals(0.05, b.lnHrvSd, 0.0); assertEquals(1.5, b.rhrSd, 0.0)
        assertEquals(ln(50.0), b.lnHrvMean, 1e-12); assertEquals(50.0, b.rhrMean, 1e-12)
        assertNull(b.respMean); assertNull(b.respSd)
    }

    @Test fun baselineExcludesTheDayItselfAndOlderThan30() {
        val h = (1L..7).map { night(it) } + night(0, hrv = 500.0, rhr = 90) + night(31, hrv = 500.0, rhr = 90) +
            night(30) + night(2).copy(date = d.plusDays(1))
        val b = Baselines.at(d, h)!!
        assertEquals(8, b.nights) // days 1..7 + day 30; not day 0, 31 or tomorrow
        assertEquals(50.0, b.rhrMean, 1e-12)
    }

    @Test fun invalidDaysAreSkipped() {
        val h = (1L..7).map { night(it) } + night(8, hrv = null) + night(9, hrv = 0.0) + night(10, rhr = null) + night(11, rhr = 0)
        assertEquals(7, Baselines.at(d, h)!!.nights)
    }

    @Test fun sampleSdWithNMinusOne() {
        val b = Baselines.at(d, (1L..8).map { night(it, rhr = 40 + 2 * it.toInt()) })!! // 42..56 step 2
        val mean = 49.0
        val expected = Math.sqrt((1L..8).sumOf { val x = 40 + 2 * it - mean; x * x } / 7)
        assertEquals(expected, b.rhrSd, 1e-9)
    }

    @Test fun hrvStatsAreOnLogScale() {
        val hrvs = listOf(30.0, 40.0, 50.0, 60.0, 80.0, 100.0, 45.0)
        val b = Baselines.at(d, hrvs.mapIndexed { i, h -> night(i + 1L, hrv = h) })!!
        val ln = hrvs.map { ln(it) }; val m = ln.average()
        assertEquals(m, b.lnHrvMean, 1e-12)
        assertEquals(Math.sqrt(ln.sumOf { (it - m) * (it - m) } / 6), b.lnHrvSd, 1e-12)
    }

    @Test fun nonPositiveRespirationIsIgnored() {
        val h = (1L..7).map { night(it, resp = if (it == 7L) 0.0 else 14.0) }
        assertNull(Baselines.at(d, h)!!.respMean)
    }

    @Test fun respirationNeedsSevenAndFloorsSd() {
        val six = (1L..7).map { night(it, resp = if (it <= 6) 14.0 else null) }
        assertNull(Baselines.at(d, six)!!.respMean)
        val seven = Baselines.at(d, (1L..7).map { night(it, resp = 14.0) })!!
        assertEquals(14.0, seven.respMean!!, 1e-12); assertEquals(0.3, seven.respSd!!, 0.0)
        assertNotNull(seven)
    }

    @Test fun noNaNOnEmptyAndIdenticalInputs() {
        assertNull(Baselines.at(d, emptyList()))
        val b = Baselines.at(d, (1L..30).map { night(it, resp = 15.0) })!!
        assertTrue(listOf(b.lnHrvMean, b.lnHrvSd, b.rhrMean, b.rhrSd, b.respMean!!, b.respSd!!).all { it.isFinite() })
    }
}
