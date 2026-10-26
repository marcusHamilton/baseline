package dev.baseline.garmin

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** `*GMT` epochs are real instants; `*Local` epochs are wall-clock fields encoded as UTC. */
object GarminTime {
    fun gmtEpochMs(ms: Long): Instant = Instant.ofEpochMilli(ms)

    fun localEpochMs(ms: Long): LocalDateTime =
        LocalDateTime.ofEpochSecond(Math.floorDiv(ms, 1000L), Math.floorMod(ms, 1000L).toInt() * 1_000_000, ZoneOffset.UTC)

    /** Accepts "2025-08-30 10:24:33" and "2026-03-26T17:23:53.0" (optional fraction). */
    fun parseLocal(s: String): LocalDateTime = LocalDateTime.parse(s.replace(' ', 'T'))

    fun parseGmt(s: String): Instant = parseLocal(s).toInstant(ZoneOffset.UTC)

    fun activityDay(startTimeLocal: String): LocalDate = parseLocal(startTimeLocal).toLocalDate()
}
