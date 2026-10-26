package dev.baseline.data.parse

import dev.baseline.data.db.DayEntity
import dev.baseline.scores.HrPoint
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DayParserTest {
    private val oct23 = LocalDate.of(2026, 10, 23)
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun sleepJson() = fixture("sleep_day.json").jsonObject
    private fun dailyItem(date: String) = fixture("dailysummaries_history.json").jsonObject["data"]!!.jsonArray
        .map { it.jsonObject }.first { it["calendarDate"]!!.jsonPrimitive.content == date }

    @Test fun sleepDay() {
        val d = DayParser.mergeSleepDay(null, sleepJson())
        assertEquals(oct23, d.date); assertEquals(31740, d.sleepSeconds)
        assertEquals(3780, d.deepSeconds); assertEquals(20640, d.lightSeconds); assertEquals(7320, d.remSeconds); assertEquals(0, d.awakeSeconds)
        assertEquals(87, d.sleepScore); assertEquals(490, d.sleepNeedMinutes); assertEquals(12.0, d.respirationAvg!!, 0.0)
        assertEquals(97.0, d.spo2Avg!!, 0.0); assertEquals(Instant.ofEpochMilli(1792766501000), d.sleepEnd)
        assertEquals(Instant.ofEpochMilli(1792734761000), d.sleepStart)
    }

    @Test fun hrvDay() {
        val d = DayParser.mergeHrv(null, fixture("hrv_day.json").jsonObject["hrvSummary"]!!.jsonObject)
        assertEquals(oct23, d.date)
        assertEquals(96.0, d.hrvLastNight!!, 0.0); assertEquals(83.0, d.hrvWeeklyAvg!!, 0.0)
        assertEquals(89.0, d.hrvBalancedLow!!, 0.0); assertEquals(102.0, d.hrvBalancedUpper!!, 0.0)
    }

    @Test fun hrvHistoryUsesSameShape() {
        val s = fixture("hrv_history.json").jsonObject["hrvSummaries"]!!.jsonArray.map { it.jsonObject }
            .first { it["calendarDate"]!!.jsonPrimitive.content == "2026-10-23" }
        assertEquals(96.0, DayParser.mergeHrv(null, s).hrvLastNight!!, 0.0)
    }

    @Test fun dailySummary() {
        val d = DayParser.mergeDailySummary(null, dailyItem("2026-10-23"))
        assertEquals(oct23, d.date); assertEquals(49, d.restingHr); assertEquals(32, d.stressAvg)
        assertEquals(75, d.bodyBatteryCharged)
        assertNull(d.respirationAvg) // the daily-summary respiration is not stored
    }

    @Test fun heartRateDaySkipsNulls() {
        val (day, pts) = DayParser.parseHeartRateDay(fixture("heartrate_day.json").jsonObject)
        assertEquals(oct23, day); assertEquals(719, pts.size); assertEquals(120, pts[1].epochSec - pts[0].epochSec)
        assertEquals(1792738800L, pts[0].epochSec) // epoch ms -> seconds
    }

    @Test fun nextSleepNeed() {
        val d = DayParser.mergeSleepDay(null, sleepJson())
        assertEquals(460, d.nextSleepNeedBaselineMin); assertEquals(460, d.nextSleepNeedActualMin); assertEquals(1420, d.recommendedBedtimeMin)
        assertEquals("sleepHistoryAdjustment=DECREASING,hrvAdjustment=INCREASING", d.nextSleepNeedReasons)
    }

    @Test fun nextSleepNeedReasonsNullWhenNothingAdjusts() {
        val o = obj("""{"calendarDate":"2026-10-23","nextSleepNeed":{"baseline":460,"actual":460,"trainingFeedback":"NO_CHANGE",
            "sleepHistoryAdjustment":"NO_CHANGE","hrvAdjustment":"NO_CHANGE","napAdjustment":"NO_CHANGE"}}""")
        assertNull(DayParser.mergeSleepDay(null, o).nextSleepNeedReasons)
    }

    @Test fun nextSleepNeedReasonsFollowFixedOrder() {
        val o = obj("""{"calendarDate":"2026-10-23","nextSleepNeed":{"napAdjustment":"DECREASING","hrvAdjustment":"INCREASING",
            "trainingFeedback":"INCREASING","sleepHistoryAdjustment":"NO_CHANGE"}}""")
        assertEquals("trainingFeedback=INCREASING,hrvAdjustment=INCREASING,napAdjustment=DECREASING",
            DayParser.mergeSleepDay(null, o).nextSleepNeedReasons)
    }

    @Test fun sleepFiledUnderCalendarDate() {
        val s = sleepJson()
        val d = DayParser.mergeSleepDay(null, s)
        // The sleep starts 2026-10-22 on the wall clock (local epoch encodes wall time as UTC) ...
        val localStart = Instant.ofEpochMilli(s["sleepStartTimestampLocal"]!!.jsonPrimitive.content.toLong()).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        assertEquals(LocalDate.of(2026, 10, 22), localStart)
        // ... and the GMT start is on the 23rd too, yet the row is the record's calendarDate.
        assertEquals(oct23, d.date)
    }

    @Test fun mergeKeepsExistingFields() {
        val sleep = DayParser.mergeSleepDay(null, sleepJson())
        val both = DayParser.mergeDailySummary(sleep, dailyItem("2026-10-23"))
        assertNotNull(both.sleepSeconds); assertNotNull(both.restingHr); assertEquals(87, both.sleepScore)
        // and the other way round
        val rev = DayParser.mergeSleepDay(DayParser.mergeDailySummary(null, dailyItem("2026-10-23")), sleepJson())
        assertEquals(both, rev)
    }

    @Test fun missingFieldsStayNull() {
        val d = DayParser.mergeDailySummary(null, obj("""{"calendarDate":"2026-10-23","heartRate":{"restingValue":null},"stress":{}}"""))
        assertEquals(DayEntity(oct23), d)
    }

    @Test fun dailySummaryOverwritesSecondaryStressAndBodyBattery() {
        val fromStress = DayParser.mergeStress(null, obj("""{"calendarDate":"2026-10-23","values":{"overallStressLevel":99}}"""))
        assertEquals(99, fromStress.stressAvg)
        val d = DayParser.mergeDailySummary(fromStress, dailyItem("2026-10-23"))
        assertEquals(32, d.stressAvg)
    }

    @Test fun stressMinusOneMeansNoData() {
        // Garmin sends avgLevel -1 for days without stress data (103 days in the fixture); it is not a reading.
        val item = fixture("dailysummaries_history.json").jsonObject["data"]!!.jsonArray.map { it.jsonObject }
            .first { it["calendarDate"]!!.jsonPrimitive.content == "2025-09-12" }
        assertNull(DayParser.mergeDailySummary(null, item).stressAvg)
        assertNull(DayParser.mergeStress(null, obj("""{"calendarDate":"2025-09-12","values":{"overallStressLevel":-1}}""")).stressAvg)
        assertEquals(32, DayParser.mergeStress(DayParser.mergeDailySummary(null, item), obj("""{"calendarDate":"2025-09-12","values":{"overallStressLevel":32}}""")).stressAvg)
    }

    @Test fun secondaryStressDoesNotOverwritePrimary() {
        val primary = DayParser.mergeDailySummary(null, dailyItem("2026-10-23"))
        assertEquals(32, DayParser.mergeStress(primary, obj("""{"calendarDate":"2026-10-23","values":{"overallStressLevel":99}}""")).stressAvg)
    }

    @Test fun sleepSummaryOnlyFillsNulls() {
        val summary = obj("""{"calendarDate":"2026-10-23","sleepTimeSeconds":1,"deepSleepSeconds":2,"lightSleepSeconds":3,"remSleepSeconds":4,
            "awakeSleepSeconds":5,"sleepStartTimestampGMT":1000,"sleepEndTimestampGMT":2000,"sleepNeed":{"actual":444}}""")
        val filled = DayParser.mergeSleepSummary(null, summary)
        assertEquals(1, filled.sleepSeconds); assertEquals(444, filled.sleepNeedMinutes); assertEquals(Instant.ofEpochMilli(2000), filled.sleepEnd)
        assertNull(filled.sleepScore) // summaries carry no score
        // sleep day wins whichever order they arrive
        val day = DayParser.mergeSleepDay(filled, sleepJson())
        assertEquals(31740, day.sleepSeconds); assertEquals(490, day.sleepNeedMinutes)
        val kept = DayParser.mergeSleepSummary(DayParser.mergeSleepDay(null, sleepJson()), summary)
        assertEquals(31740, kept.sleepSeconds); assertEquals(490, kept.sleepNeedMinutes)
        assertEquals(Instant.ofEpochMilli(1792766501000), kept.sleepEnd)
    }

    @Test fun sleepSummaryHistoryParses() {
        val items = fixture("sleep_summaries_history.json").jsonArray.map { it.jsonObject }
        val d = DayParser.mergeSleepSummary(null, items.first { it["calendarDate"]?.jsonPrimitive?.contentOrNull == "2026-10-23" })
        assertEquals(31740, d.sleepSeconds); assertEquals(490, d.sleepNeedMinutes)
    }

    @Test fun sleepSummaryWithoutCalendarDateIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { DayParser.mergeSleepSummary(null, obj("""{"calendarDate":null,"sleepNeed":{"calendarDate":"2026-09-26"}}""")) }
    }

    @Test fun stepsFromHistory() {
        val item = fixture("steps_history.json").jsonArray.map { it.jsonObject }.first { it["calendarDate"]!!.jsonPrimitive.content == "2026-10-18" }
        val d = DayParser.mergeSteps(null, item)
        assertEquals(LocalDate.of(2026, 10, 18), d.date); assertEquals(2421, d.steps); assertEquals(5390, d.stepGoal)
    }

    @Test fun bodyBatteryWakeIsSampleNearestSleepEndSkippingNullLevels() {
        val bb = obj("""{"date":"2026-10-23","charged":70,"bodyBatteryValueDescriptorDTOList":[
            {"bodyBatteryValueDescriptorIndex":0,"bodyBatteryValueDescriptorKey":"timestamp"},
            {"bodyBatteryValueDescriptorIndex":1,"bodyBatteryValueDescriptorKey":"bodyBatteryLevel"}],
            "bodyBatteryValuesArray":[[1000,10],[2000,20],[2900,null],[3100,40],[9000,90]]}""")
        // nearest by time is 2900, but its level is null -> next nearest non-null (3100 -> 40)
        val d = DayParser.mergeBodyBattery(null, bb, Instant.ofEpochMilli(3000))
        assertEquals(40, d.bodyBatteryWake); assertEquals(70, d.bodyBatteryCharged); assertEquals(oct23, d.date)
        assertNull(DayParser.mergeBodyBattery(null, bb, null).bodyBatteryWake)
    }

    @Test fun bodyBatteryUsesDescriptorIndexes() {
        val bb = obj("""{"date":"2026-10-23","bodyBatteryValueDescriptorDTOList":[
            {"bodyBatteryValueDescriptorIndex":0,"bodyBatteryValueDescriptorKey":"bodyBatteryLevel"},
            {"bodyBatteryValueDescriptorIndex":1,"bodyBatteryValueDescriptorKey":"timestamp"}],
            "bodyBatteryValuesArray":[[55,3000]]}""")
        assertEquals(55, DayParser.mergeBodyBattery(null, bb, Instant.ofEpochMilli(3000)).bodyBatteryWake)
    }

    @Test fun bodyBatteryChargedIsSecondary() {
        val primary = DayParser.mergeDailySummary(null, dailyItem("2026-10-23"))
        val bb = obj("""{"date":"2026-10-23","charged":1}""")
        assertEquals(75, DayParser.mergeBodyBattery(primary, bb, null).bodyBatteryCharged)
        assertEquals(1, DayParser.mergeBodyBattery(null, bb, null).bodyBatteryCharged)
        assertEquals(75, DayParser.mergeDailySummary(DayParser.mergeBodyBattery(null, bb, null), dailyItem("2026-10-23")).bodyBatteryCharged)
    }

    @Test fun bodyBatteryHistoryFixtureParses() {
        val items = fixture("bodybattery_history.json").jsonArray.map { it.jsonObject }
        val item = items.first { it["date"]!!.jsonPrimitive.content == "2026-10-23" }
        val d = DayParser.mergeBodyBattery(null, item, Instant.ofEpochMilli(1792766501000))
        assertNotNull(d.bodyBatteryWake)
        assertFalse(d.bodyBatteryWake!! < 0)
    }

    private val chunksDay get() = fixture("bodybattery_chunks_day.json").jsonObject
    private val sleepEnd23 = Instant.ofEpochMilli(1792766501000) // sleep_day.json sleepEndTimestampGMT

    @Test fun curveFromFixtureIsDedupedAndSorted() {
        val curve = DayParser.parseBodyBatteryCurve(chunksDay)
        assertTrue(curve.size in 288..576)
        assertTrue(curve.zipWithNext().all { (a, b) -> a.epochSec < b.epochSec })
        assertTrue(curve.all { it.bpm in 0..100 })
        assertEquals(1792738800L, curve.first().epochSec) // epoch ms / 1000
    }

    @Test fun curveSkipsNullsAndKeepsLastDuplicate() {
        val bb = obj("""{"date":"2026-10-23","bodyBatteryValueDescriptorDTOList":[
            {"bodyBatteryValueDescriptorIndex":0,"bodyBatteryValueDescriptorKey":"timestamp"},
            {"bodyBatteryValueDescriptorIndex":1,"bodyBatteryValueDescriptorKey":"bodyBatteryLevel"}],
            "bodyBatteryValuesArray":[[3000,30],[1000,10],[3000,31],[2000,null],[3000,null]]}""")
        assertEquals(listOf(HrPoint(1, 10), HrPoint(3, 31)), DayParser.parseBodyBatteryCurve(bb))
        assertEquals(emptyList<HrPoint>(), DayParser.parseBodyBatteryCurve(obj("""{"date":"2026-10-23"}""")))
    }

    @Test fun wakeUsesNearestWithin15Min() {
        assertEquals(91, DayParser.mergeBodyBattery(null, chunksDay, sleepEnd23).bodyBatteryWake)
        val bb = obj("""{"date":"2026-10-23","bodyBatteryValueDescriptorDTOList":[
            {"bodyBatteryValueDescriptorIndex":0,"bodyBatteryValueDescriptorKey":"timestamp"},
            {"bodyBatteryValueDescriptorIndex":1,"bodyBatteryValueDescriptorKey":"bodyBatteryLevel"}],
            "bodyBatteryValuesArray":[[0,10],[2000000,20]]}""")
        // 900 s = exactly 15 min from the sample at 0 -> still in; the sample at 2000 s is 1100 s away.
        assertEquals(10, DayParser.mergeBodyBattery(null, bb, Instant.ofEpochSecond(900)).bodyBatteryWake)
    }

    @Test fun wakeNullWhenNoSampleNearby() {
        val bb = obj("""{"date":"2026-10-23","bodyBatteryValueDescriptorDTOList":[
            {"bodyBatteryValueDescriptorIndex":0,"bodyBatteryValueDescriptorKey":"timestamp"},
            {"bodyBatteryValueDescriptorIndex":1,"bodyBatteryValueDescriptorKey":"bodyBatteryLevel"}],
            "bodyBatteryValuesArray":[[0,10]]}""")
        assertNull(DayParser.mergeBodyBattery(null, bb, Instant.ofEpochSecond(901)).bodyBatteryWake)
        // Neither an out-of-range curve nor a missing sleepEnd keeps a stale wake value.
        val stale = DayEntity(oct23, bodyBatteryWake = 50)
        assertNull(DayParser.mergeBodyBattery(stale, bb, Instant.ofEpochSecond(901)).bodyBatteryWake)
        assertNull(DayParser.mergeBodyBattery(stale, bb, null).bodyBatteryWake)
    }
}
