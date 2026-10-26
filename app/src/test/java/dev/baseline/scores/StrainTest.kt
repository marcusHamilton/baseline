package dev.baseline.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrainTest {
    @Test fun calibrationMapsP90To17() {
        val cal = Strain.calibrate((1..100).map { it.toDouble() }, 1)
        assertEquals(17.0, Strain.of(90.0, cal), 0.0)
        assertEquals(0.0, Strain.of(0.0, cal), 0.0)
        assertEquals(21.0, Strain.of(1e6, cal), 0.0)
        assertEquals(1, cal.version)
    }

    @Test fun p90UsesNearestRank() { // n=10 -> index ceil(9)-1 = 8
        val cal = Strain.calibrate((1..10).map { it.toDouble() }.reversed(), 2)
        assertEquals(9.0 / Math.log(21.0 / 4.0), cal.tau, 1e-9)
    }

    @Test fun roundsToTenthAndClamps() {
        val cal = StrainCalibration(10.0, 1)
        assertEquals(10.6, Strain.of(7.0, cal), 0.0) // 21 * (1 - e^-0.7) = 10.57
        assertEquals(0.0, Strain.of(-5.0, cal), 0.0)
    }

    @Test fun noNaNOnEmptyAndIdenticalInputs() {
        val empty = Strain.calibrate(emptyList(), 1)
        val zeros = Strain.calibrate(List(5) { 0.0 }, 1)
        val same = Strain.calibrate(List(5) { 50.0 }, 1)
        assertEquals(1.0, empty.tau, 0.0)
        assertEquals(1.0, zeros.tau, 0.0)
        listOf(empty, zeros, same).forEach { c ->
            assertTrue(c.tau.isFinite())
            listOf(0.0, 50.0, 1e9).forEach { assertTrue(Strain.of(it, c).isFinite()) }
        }
    }
}
