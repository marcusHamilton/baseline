package dev.baseline.data

import dev.baseline.data.SportFamily.OTHER
import dev.baseline.data.SportFamily.RIDE
import dev.baseline.data.SportFamily.RUN
import dev.baseline.data.SportFamily.STRENGTH
import dev.baseline.data.SportFamily.SWIM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportFamilyTest {
    @Test fun familiesCoverUserTypes() {
        mapOf(
            "running" to RUN, "trail_running" to RUN, "track_running" to RUN, "treadmill_running" to RUN,
            "road_biking" to RIDE, "virtual_ride" to RIDE, "indoor_cycling" to RIDE,
            "cycling" to RIDE, "mountain_biking" to RIDE, "gravel_cycling" to RIDE,
            "lap_swimming" to SWIM, "open_water_swimming" to SWIM,
            "strength_training" to STRENGTH, "pickleball" to OTHER,
            "walking" to OTHER, "never_seen_before" to OTHER,
        ).forEach { (k, f) -> assertEquals(k, f, SportFamily.of(k)) }
        assertFalse(RUN.countsForDistanceRecords("treadmill_running"))
        assertTrue(RIDE.countsForDistanceRecords("road_biking"))
    }

    @Test fun distanceRecordExclusions() {
        listOf("treadmill_running", "virtual_ride", "indoor_cycling", "open_water_swimming", "strength_training", "walking")
            .forEach { assertFalse(it, SportFamily.of(it).countsForDistanceRecords(it)) }
        listOf("running", "trail_running", "track_running", "road_biking", "cycling", "mountain_biking", "gravel_cycling", "lap_swimming")
            .forEach { assertTrue(it, SportFamily.of(it).countsForDistanceRecords(it)) }
    }
}
