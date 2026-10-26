package dev.baseline.data.parse

import dev.baseline.data.SportFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class ActivityParserTest {
    private fun all() = fixture("activities_all.json").jsonArray.map { ActivityParser.parseListItem(it.jsonObject) }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun activityListItem() {
        val a = all()
        assertEquals(COUNT_ALL, a.size)
        assertEquals(COUNT_RUN, a.count { it.sportFamily == SportFamily.RUN })
        assertEquals(COUNT_RIDE, a.count { it.sportFamily == SportFamily.RIDE })
        val s = a.first { it.id == 900000067L }
        assertEquals(SportFamily.STRENGTH, s.sportFamily)
        assertEquals(2.88, s.garminLoad!!, 0.01)
        assertEquals(LocalDate.of(2025, 8, 30), a.minOf { it.day })
    }

    @Test fun fiveKRecordActivity() {
        val a = all().first { it.id == 900000160L }
        assertEquals("running", a.typeKey)
        assertEquals(SportFamily.RUN, a.sportFamily)
        assertEquals(LocalDateTime.of(2025, 10, 9, 17, 29, 31), a.startTimeLocal)
        assertEquals(LocalDate.of(2025, 10, 9), a.day)
        assertEquals(Instant.parse("2025-10-10T00:29:31Z"), a.startGmt)
        val splits = Json.parseToJsonElement(a.fastestSplitsJson!!).jsonObject
        assertEquals(1476.757, splits["fastestSplit_5000"].toString().toDouble(), 0.001)
        assertEquals(setOf("fastestSplit_1000", "fastestSplit_1609", "fastestSplit_5000"), splits.keys)
        assertEquals(15.023, a.zone1Sec!!, 0.0001)
        assertEquals(865.737, a.zone5Sec!!, 0.0001)
        assertEquals(161.078, a.garminLoad!!, 0.001)
        assertEquals(5.0, a.elevationGainM!!, 0.0)
        assertEquals(411.0, a.avgPower!!, 0.0)
        assertEquals(413.0, a.normPower!!, 0.0)
        assertEquals(193.0, a.maxHr!!, 0.0)
    }

    @Test fun poolLengthOnlyForPoolSwims() {
        val pool = all().filter { it.poolLengthM != null }
        assertTrue(pool.isNotEmpty())
        assertTrue(pool.all { it.sportFamily == SportFamily.SWIM && it.typeKey == "lap_swimming" })
        assertTrue(pool.all { it.poolLengthM == 25.0 }) // raw poolLength is 2500.0 (cm)
    }

    @Test fun missingOptionalFieldsAreNullNotNaN() {
        val a =
            ActivityParser.parseListItem(
                obj(
                    """{"activityId":1,"activityType":{"typeKey":"yoga"},
            "startTimeGMT":"2026-01-02 03:04:05","startTimeLocal":"2026-01-01 20:04:05","duration":60.0,"averageHR":null}""",
                ),
            )
        assertEquals(SportFamily.OTHER, a.sportFamily)
        assertEquals(LocalDate.of(2026, 1, 1), a.day)
        assertNull(a.avgHr)
        assertNull(a.zone1Sec)
        assertNull(a.fastestSplitsJson)
        assertNull(a.poolLengthM)
        assertNull(a.name)
        assertNull(a.distanceM)
        assertNull(a.garminLoad)
    }

    @Test fun requiredFieldsMissingThrow() {
        fun item(vararg drop: String): String {
            val f =
                mapOf(
                    "activityId" to "1",
                    "activityType" to """{"typeKey":"yoga"}""",
                    "startTimeGMT" to "\"2026-01-02 03:04:05\"",
                    "startTimeLocal" to "\"2026-01-01 20:04:05\"",
                    "duration" to "60.0",
                ) - drop.toSet()
            return f.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
        }
        ActivityParser.parseListItem(obj(item()))
        listOf("activityId", "activityType", "startTimeGMT", "startTimeLocal", "duration").forEach { missing ->
            assertThrows(missing, IllegalArgumentException::class.java) { ActivityParser.parseListItem(obj(item(missing))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityParser.parseListItem(obj(item("startTimeGMT").dropLast(1) + ""","startTimeGMT":"bogus"}"""))
        }
    }

    private companion object {
        const val COUNT_ALL = 175
        const val COUNT_RUN = 90
        const val COUNT_RIDE = 36
    }
}
