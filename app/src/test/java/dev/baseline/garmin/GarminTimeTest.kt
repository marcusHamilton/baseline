package dev.baseline.garmin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class GarminTimeTest {
    @Test fun localEpochIsWallClock() {
        // sleepStartTimestampLocal from fixture sleep_day.json; GMT is 1792734761000
        assertEquals(LocalDateTime.of(2026, 10, 22, 22, 52, 41), GarminTime.localEpochMs(1792709561000))
        assertEquals(Instant.parse("2026-10-23T05:52:41Z"), GarminTime.gmtEpochMs(1792734761000))
    }

    @Test fun dstFallBackActivityStaysOnItsLocalDate() {
        assertEquals(LocalDate.of(2026, 11, 1), GarminTime.activityDay("2026-11-01 01:30:00"))
    }

    @Test fun parsesBothFormats() {
        val expected = LocalDateTime.of(2025, 8, 30, 10, 24, 33)
        assertEquals(expected, GarminTime.parseLocal("2025-08-30 10:24:33"))
        assertEquals(expected, GarminTime.parseLocal("2025-08-30T10:24:33"))
        assertEquals(expected, GarminTime.parseLocal("2025-08-30 10:24:33.0"))
        assertEquals(LocalDateTime.of(2026, 3, 26, 17, 23, 53), GarminTime.parseLocal("2026-03-26T17:23:53.0"))
        assertEquals(LocalDateTime.of(2026, 3, 26, 17, 23, 53, 120_000_000), GarminTime.parseLocal("2026-03-26T17:23:53.12"))
        assertEquals(LocalDateTime.of(2026, 3, 26, 17, 23, 53, 123_000_000), GarminTime.parseLocal("2026-03-26T17:23:53.123"))
    }

    @Test fun parseGmtReadsFieldsAsUtc() {
        assertEquals(Instant.parse("2025-08-30T17:24:33Z"), GarminTime.parseGmt("2025-08-30 17:24:33"))
        assertEquals(Instant.parse("2026-03-27T00:23:53Z"), GarminTime.parseGmt("2026-03-27T00:23:53.0"))
    }
}
