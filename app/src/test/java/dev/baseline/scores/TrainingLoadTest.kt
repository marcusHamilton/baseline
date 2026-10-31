package dev.baseline.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
}
