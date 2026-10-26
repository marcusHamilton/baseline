package dev.baseline.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class FormattersTest {
    private val start = LocalDateTime.of(2026, 10, 24, 7, 5)
    private val metric = Formatters(imperial = false)
    private val imperial = Formatters(imperial = true)

    @Test
    fun metaJoinsStartDistanceAndDuration() {
        assertEquals("7:05 AM · 10.21 km · 52:14", metric.activityMeta(start, 10_210.0, 3134.0))
        assertEquals("7:05 AM · 6.34 mi · 52:14", imperial.activityMeta(start, 10_210.0, 3134.0))
        assertEquals("5:30 PM · 1:05:30", metric.activityMeta(LocalDateTime.of(2026, 10, 24, 17, 30), null, 3930.0))
    }

    @Test
    fun missingPartsLoseTheirSeparator() {
        assertEquals("7:05 AM · 52:14", metric.activityMeta(start, null, 3134.0))
        assertEquals("7:05 AM · 52:14", metric.activityMeta(start, 0.0, 3134.0))
        assertEquals("7:05 AM · 10.21 km", metric.activityMeta(start, 10_210.0, 0.0))
        assertEquals("7:05 AM", metric.activityMeta(start, Double.NaN, null))
    }

    @Test
    fun syncedShowsTheTimeTodayAndTheDateOtherwise() {
        val zone = java.time.ZoneId.of("America/Phoenix")
        val today = java.time.LocalDate.of(2026, 10, 24)
        assertEquals("Synced 7:41 AM", Formatters.synced(java.time.Instant.parse("2026-10-24T14:41:00Z"), today, zone))
        assertEquals("Synced Oct 22, 7:41 AM", Formatters.synced(java.time.Instant.parse("2026-10-22T14:41:00Z"), today, zone))
        // 23:30 local on the 23rd is yesterday even though it is already the 24th in UTC.
        assertEquals("Synced Oct 23, 11:30 PM", Formatters.synced(java.time.Instant.parse("2026-10-24T06:30:00Z"), today, zone))
    }

    @Test
    fun lastSyncAndAgo() {
        val zone = java.time.ZoneId.of("America/Phoenix")
        val today = java.time.LocalDate.of(2026, 10, 24)
        assertEquals("Today, 7:41 AM", Formatters.lastSync(java.time.Instant.parse("2026-10-24T14:41:00Z"), today, zone))
        assertEquals("Oct 22, 7:41 AM", Formatters.lastSync(java.time.Instant.parse("2026-10-22T14:41:00Z"), today, zone))
        val now = java.time.Instant.parse("2026-10-24T12:00:00Z")
        assertEquals("just now", Formatters.ago(now.minusSeconds(30), now))
        assertEquals("just now", Formatters.ago(now.plusSeconds(90), now))
        assertEquals("5 min ago", Formatters.ago(now.minusSeconds(5 * 60), now))
        assertEquals("3 h ago", Formatters.ago(now.minusSeconds(3 * 3600 + 59), now))
        assertEquals("1 day ago", Formatters.ago(now.minusSeconds(30 * 3600), now))
        assertEquals("2 days ago", Formatters.ago(now.minusSeconds(49 * 3600), now))
    }

    @Test
    fun hoursMinutes() {
        assertEquals("7h 42m", Formatters.hoursMinutes(7 * 3600.0 + 42 * 60))
        assertEquals("42m", Formatters.hoursMinutes(2520.0))
        assertNull(Formatters.hoursMinutes(null))
        assertNull(Formatters.hoursMinutes(Double.NaN))
    }

    @Test
    fun activityDetailValuesInBothUnitSystems() {
        val m = Formatters(false)
        val i = Formatters(true)
        assertEquals("10.21" to "6.34", m.distanceValue(10_210.0) to i.distanceValue(10_210.0))
        assertEquals(307.0, m.secondsPerUnit(10_210.0, 3134.0)!!, 0.5)
        assertEquals("5:07", m.pace(m.secondsPerUnit(10_210.0, 3134.0)))
        assertEquals("27.1" to "16.8", m.speedValue(7.53) to i.speedValue(7.53))
        assertEquals("86" to "282", m.elevation(86.0) to i.elevation(86.0))
        assertEquals("+12" to "−4", m.elevationDelta(12.2) to m.elevationDelta(-4.1))
        assertEquals("2:03" to "1:52", m.swimPace(123.0) to i.swimPace(123.0))
        assertEquals("2,000" to "2,187", m.swimDistance(2000.0) to i.swimDistance(2000.0))
        assertEquals("100" to "52.5", m.weight(100.0) to m.weight(52.5))
        assertEquals("2,420" to "5,335", m.volume(2420.0) to i.volume(2420.0))
        listOf(m.distanceValue(null), m.pace(null), m.speedValue(Double.NaN), m.elevation(null), m.elevationDelta(null), m.swimPace(0.0), m.swimDistance(0.0))
            .forEach { assertNull(it) }
    }
}
