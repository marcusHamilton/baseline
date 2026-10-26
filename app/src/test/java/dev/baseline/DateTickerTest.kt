package dev.baseline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class DateTickerTest {
    private var now = Instant.parse("2026-10-24T23:30:00Z")
    private var zoneId: ZoneId = ZoneOffset.UTC
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = zoneId

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = now
        }

    @Test
    fun refreshPicksUpMidnightAndZoneChanges() {
        val ticker = DateTicker(clock)
        assertEquals(LocalDate.of(2026, 10, 24), ticker.today.value)
        now = Instant.parse("2026-10-25T00:05:00Z")
        assertEquals(LocalDate.of(2026, 10, 24), ticker.today.value) // nothing changes until refreshed
        ticker.refresh()
        assertEquals(LocalDate.of(2026, 10, 25), ticker.today.value)
        zoneId = ZoneOffset.ofHours(-5)
        ticker.refresh()
        assertEquals(LocalDate.of(2026, 10, 24), ticker.today.value)
        assertEquals(ZoneOffset.ofHours(-5), ticker.zone.value)
    }

    @Test
    fun zoneChangeWithinTheSameDayStillEmits() {
        val ticker = DateTicker(clock)
        zoneId = ZoneOffset.ofHours(-1)
        ticker.refresh()
        assertEquals(LocalDate.of(2026, 10, 24), ticker.today.value)
        assertEquals(ZoneOffset.ofHours(-1), ticker.zone.value)
    }
}
