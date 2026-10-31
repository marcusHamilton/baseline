package dev.baseline.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.roundToInt

class TrainingLoadTest {
    private val d0 = LocalDate.of(2026, 1, 1)

    private fun flat(n: Int, load: Double, start: LocalDate = d0) = (0 until n).associate { start.plusDays(it.toLong()) to load }

    private fun last(loads: Map<LocalDate, Double>, day: LocalDate, earliest: LocalDate? = d0, tau: Double? = null) =
        TrainingLoad.series(loads, day, day, earliest, tau).single()

    @Test fun constantLoadConverges() {
        val day = d0.plusDays(400)
        val t = last(flat(400, 100.0), day)
        assertEquals(100.0, t.fitness, 0.01)
        assertEquals(100.0, t.fatigue, 0.01)
        assertEquals(0, t.formPct)
        assertEquals(FormState.MAINTAINING, t.state)
    }

    @Test fun spikeDecaysByTheTimeConstants() {
        val s = d0.plusDays(200)
        val loads = flat(200, 0.0) + (s to 420.0)
        val one = last(loads, s.plusDays(1))
        assertEquals(10.0, one.fitness, 1e-9)
        assertEquals(60.0, one.fatigue, 1e-9)
        val two = last(loads, s.plusDays(2))
        assertEquals(10.0 * (1 - 1 / 42.0), two.fitness, 1e-9)
        assertEquals(60.0 * (1 - 1 / 7.0), two.fatigue, 1e-9)
    }

    @Test fun formUsesYesterday() {
        val day = d0.plusDays(100)
        val base = flat(100, 80.0)
        assertEquals(last(base + (day to 0.0), day), last(base + (day to 500.0), day))
    }

    @Test fun stateBoundaries() {
        assertEquals(FormState.OVERREACHING, TrainingLoad.stateOf(-31))
        assertEquals(FormState.PRODUCTIVE, TrainingLoad.stateOf(-30))
        assertEquals(FormState.PRODUCTIVE, TrainingLoad.stateOf(-11))
        assertEquals(FormState.MAINTAINING, TrainingLoad.stateOf(-10))
        assertEquals(FormState.MAINTAINING, TrainingLoad.stateOf(5))
        assertEquals(FormState.FRESH, TrainingLoad.stateOf(6))
        assertEquals(FormState.FRESH, TrainingLoad.stateOf(20))
        assertEquals(FormState.DETRAINING, TrainingLoad.stateOf(21))
    }

    @Test fun calibratingCountsDown() {
        val loads = flat(60, 100.0)
        val cal = last(loads, d0.plusDays(41))
        assertEquals(FormState.CALIBRATING, cal.state)
        assertEquals(1, cal.calibratingDaysLeft)
        assertNull(cal.formPct)
        val done = last(loads, d0.plusDays(42))
        assertNotEquals(FormState.CALIBRATING, done.state)
        assertNull(done.calibratingDaysLeft)
    }

    @Test fun noEarliestScoreIsCalibrating() {
        TrainingLoad.series(emptyMap(), d0, d0.plusDays(5), null, null).forEach {
            assertEquals(FormState.CALIBRATING, it.state)
            assertEquals(42, it.calibratingDaysLeft)
        }
    }

    @Test fun noRecentTrainingBelowOnePercentOfTau() {
        val loads = flat(200, 100.0) + flat(300, 0.0, d0.plusDays(200))
        val day = d0.plusDays(500)
        val t = last(loads, day, tau = 100.0)
        assertEquals(FormState.NO_RECENT_TRAINING, t.state)
        assertNull(t.formPct)
        assertEquals(FormState.DETRAINING, last(loads, day, tau = null).state)
    }

    @Test fun zeroFitnessWithoutTauIsNoRecentTraining() {
        val t = last(flat(60, 0.0), d0.plusDays(60), tau = null)
        assertEquals(FormState.NO_RECENT_TRAINING, t.state)
        assertNull(t.formPct)
    }

    @Test fun missingDaysCountAsZero() {
        val sparse = (0 until 100 step 2).associate { d0.plusDays(it.toLong()) to 90.0 }
        val dense = (0 until 100).associate { d0.plusDays(it.toLong()) to if (it % 2 == 0) 90.0 else 0.0 }
        assertEquals(
            TrainingLoad.series(dense, d0.plusDays(50), d0.plusDays(99), d0, 50.0),
            TrainingLoad.series(sparse, d0.plusDays(50), d0.plusDays(99), d0, 50.0),
        )
    }

    @Test fun badLoadsCountAsZero() {
        val base = flat(100, 70.0)
        val bad = base + mapOf(d0.plusDays(50) to Double.NaN, d0.plusDays(60) to -50.0, d0.plusDays(70) to Double.POSITIVE_INFINITY)
        val zero = base + mapOf(d0.plusDays(50) to 0.0, d0.plusDays(60) to 0.0, d0.plusDays(70) to 0.0)
        val got = TrainingLoad.series(bad, d0.plusDays(40), d0.plusDays(99), d0, 50.0)
        assertEquals(TrainingLoad.series(zero, d0.plusDays(40), d0.plusDays(99), d0, 50.0), got)
        got.forEach { assertTrue(it.fitness.isFinite() && it.fatigue.isFinite()) }
    }

    // Week budget. 2026-03-02 is a Monday; 2026-03-04 (a Wednesday) is the date unless a test says otherwise.
    private val mon = LocalDate.of(2026, 3, 2)
    private val wed = mon.plusDays(2)
    private val start = mon.minusDays(300)
    private val history = flat(300, 100.0, start)

    private fun week(vararg l: Double) = history + l.withIndex().associate { (i, v) -> mon.plusDays(i.toLong()) to v }

    private fun normal(loads: Map<LocalDate, Double>, weekStart: LocalDate = mon) =
        7 * TrainingLoad.series(loads, weekStart, weekStart, start, null).single().fitness

    private fun budget(
        loads: Map<LocalDate, Double>,
        date: LocalDate = wed,
        first: DayOfWeek = DayOfWeek.MONDAY,
        earliest: LocalDate? = start,
        tau: Double? = 100.0,
    ) = TrainingLoad.weekBudget(loads, date, first, earliest, tau)

    @Test fun budgetTakesNormalFromTheDayBeforeTheWeek() {
        val loads = week(200.0, 0.0, 100.0)
        val b = budget(loads)!!
        assertEquals(mon, b.weekStart)
        assertEquals((300 / normal(loads) * 100).roundToInt(), b.pct)
        assertEquals(4, b.daysLeft)
    }

    @Test fun sundayStartWeek() {
        val b = budget(week(200.0, 0.0, 100.0), first = DayOfWeek.SUNDAY)!!
        assertEquals(mon.minusDays(1), b.weekStart)
        assertEquals(3, b.daysLeft)
    }

    @Test fun todaysPartialLoadCounts() {
        val before = week(200.0, 0.0, 100.0)
        val after = week(200.0, 0.0, 150.0)
        assertEquals((350 / normal(after) * 100).roundToInt(), budget(after)!!.pct)
        assertTrue(budget(after)!!.pct > budget(before)!!.pct)
    }

    @Test fun hardDaysLeftIsCappedToDaysLeftAndZero() {
        val low = budget(week(100.0, 0.0, 0.0), tau = 10.0)!!
        assertEquals(low.daysLeft, low.hardDaysLeft)
        val n = normal(history)
        val full = budget(week(1.29 * n, 0.0, 0.0), tau = 1000.0)!!
        assertEquals(0, full.hardDaysLeft)
        assertEquals(false, full.overBand)
    }

    @Test fun hardDayIsAStrain14Day() {
        val n = normal(history)
        val b = budget(week(1.3 * n - 2.5 * 100.0 * ln(3.0), 0.0, 0.0))!!
        assertEquals(2, b.hardDaysLeft)
    }

    @Test fun overBand() {
        val b = budget(week(1.31 * normal(history), 0.0, 0.0))!!
        assertEquals(131, b.pct)
        assertTrue(b.overBand)
        assertEquals(0, b.hardDaysLeft)
    }

    @Test fun pastWeekLastDay() {
        val b = budget(week(100.0, 100.0, 100.0, 0.0, 0.0, 0.0, 0.0), date = mon.plusDays(6))!!
        assertEquals(0, b.daysLeft)
        assertEquals(0, b.hardDaysLeft)
    }

    @Test fun noCalibrationNoHardDays() {
        val b = budget(week(200.0, 0.0, 100.0), tau = null)!!
        assertNull(b.hardDaysLeft)
        assertTrue(b.pct > 0)
    }

    @Test fun noBudgetWhileCalibratingOrNoTraining() {
        assertNull(budget(week(200.0, 0.0, 100.0), earliest = wed.minusDays(10)))
        val rested = flat(200, 100.0, start) + flat(300, 0.0, start.plusDays(200))
        assertNull(budget(rested, date = start.plusDays(500)))
    }

    @Test fun zeroNormalGivesNoBudget() {
        val loads = flat(100, 0.0, mon.minusDays(100)) + mapOf(mon to 100.0, mon.plusDays(1) to 100.0)
        assertNull(budget(loads, earliest = mon.minusDays(100), tau = null))
    }

    @Test fun noBudgetWhenTheWeekStartedAfterALayoff() {
        // Fitness at the week's start is ~0.07 (under 1 % of tau) but two hard days since lift today's to a real state:
        // a normal week of ~0.5 load would read as tens of thousands of percent.
        val first = mon.minusDays(500)
        val loads = flat(200, 100.0, first) + flat(300, 0.0, first.plusDays(200)) + mapOf(mon to 200.0, mon.plusDays(1) to 200.0)
        assertNotEquals(FormState.NO_RECENT_TRAINING, last(loads, wed, first, 100.0).state)
        assertTrue(normal(loads) < 7 * 0.01 * 100.0)
        assertNull(budget(loads, earliest = first))
    }

    @Test fun anOverreachingWeekEasesAGreenTargetToTheAmberBand() {
        assertEquals(10.0..14.0, TrainingLoad.easedTarget(14.0..18.0, FormState.OVERREACHING))
        assertNull(TrainingLoad.easedTarget(10.0..14.0, FormState.OVERREACHING)) // already amber: nothing to ease
        assertNull(TrainingLoad.easedTarget(0.0..10.0, FormState.OVERREACHING))
        FormState.entries.filter { it != FormState.OVERREACHING }.forEach { assertNull(it.name, TrainingLoad.easedTarget(14.0..18.0, it)) }
        assertNull(TrainingLoad.easedTarget(14.0..18.0, null))
    }
}
