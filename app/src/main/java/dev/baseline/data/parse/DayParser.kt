package dev.baseline.data.parse

import dev.baseline.data.db.DayEntity
import dev.baseline.scores.HrPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.abs

/**
 * Raw Garmin JSON -> [DayEntity]. Each merge returns an updated copy; missing fields stay null.
 *
 * Every field has one primary source that overwrites (when it has a value) and optional secondary
 * sources that only fill nulls, so the result does not depend on the order responses arrive in.
 * A day's date is always the record's own calendarDate (`date` for body battery), never derived from timestamps.
 */
object DayParser {
    fun mergeDailySummary(e: DayEntity?, item: JsonObject): DayEntity {
        val d = base(e, item)
        return d.copy(
            restingHr = item.obj("heartRate")?.int("restingValue") ?: d.restingHr,
            stressAvg = noData(item.obj("stress")?.int("avgLevel")) ?: d.stressAvg,
            bodyBatteryCharged = item.obj("bodyBattery")?.int("chargedValue") ?: d.bodyBatteryCharged,
        )
    }

    fun mergeSleepDay(e: DayEntity?, json: JsonObject): DayEntity {
        val d = base(e, json)
        val next = json.obj("nextSleepNeed")
        return d.copy(
            sleepSeconds = json.int("sleepTimeSeconds") ?: d.sleepSeconds,
            deepSeconds = json.int("deepSleepSeconds") ?: d.deepSeconds,
            lightSeconds = json.int("lightSleepSeconds") ?: d.lightSeconds,
            remSeconds = json.int("remSleepSeconds") ?: d.remSeconds,
            awakeSeconds = json.int("awakeSleepSeconds") ?: d.awakeSeconds,
            sleepStart = json.lng("sleepStartTimestampGMT")?.let(Instant::ofEpochMilli) ?: d.sleepStart,
            sleepEnd = json.lng("sleepEndTimestampGMT")?.let(Instant::ofEpochMilli) ?: d.sleepEnd,
            sleepNeedMinutes = json.obj("sleepNeed")?.int("actual") ?: d.sleepNeedMinutes,
            sleepScore = json.obj("sleepScores")?.obj("overall")?.int("value") ?: d.sleepScore,
            respirationAvg = json.dbl("averageRespirationValue") ?: d.respirationAvg,
            spo2Avg = json.dbl("averageSpO2Value") ?: d.spo2Avg,
            nextSleepNeedBaselineMin = next?.int("baseline") ?: d.nextSleepNeedBaselineMin,
            nextSleepNeedActualMin = next?.int("actual") ?: d.nextSleepNeedActualMin,
            nextSleepNeedReasons = next?.let(::reasons) ?: d.nextSleepNeedReasons,
            recommendedBedtimeMin = next?.int("recommendedBedtimeStartMins") ?: d.recommendedBedtimeMin,
        )
    }

    /** Secondary source for the sleep fields it carries (no score, respiration or SpO2). Items with no calendarDate are rejected. */
    fun mergeSleepSummary(e: DayEntity?, item: JsonObject): DayEntity {
        val d = base(e, item)
        return d.copy(
            sleepSeconds = d.sleepSeconds ?: item.int("sleepTimeSeconds"),
            deepSeconds = d.deepSeconds ?: item.int("deepSleepSeconds"),
            lightSeconds = d.lightSeconds ?: item.int("lightSleepSeconds"),
            remSeconds = d.remSeconds ?: item.int("remSleepSeconds"),
            awakeSeconds = d.awakeSeconds ?: item.int("awakeSleepSeconds"),
            sleepStart = d.sleepStart ?: item.lng("sleepStartTimestampGMT")?.let(Instant::ofEpochMilli),
            sleepEnd = d.sleepEnd ?: item.lng("sleepEndTimestampGMT")?.let(Instant::ofEpochMilli),
            sleepNeedMinutes = d.sleepNeedMinutes ?: item.obj("sleepNeed")?.int("actual"),
        )
    }

    /** [summary] is a `hrvSummary` object: `hrv_day.hrvSummary` or one element of `hrvSummaries`. */
    fun mergeHrv(e: DayEntity?, summary: JsonObject): DayEntity {
        val d = base(e, summary)
        val baseline = summary.obj("baseline")
        return d.copy(
            hrvLastNight = summary.dbl("lastNightAvg") ?: d.hrvLastNight,
            hrvWeeklyAvg = summary.dbl("weeklyAvg") ?: d.hrvWeeklyAvg,
            hrvBalancedLow = baseline?.dbl("balancedLow") ?: d.hrvBalancedLow,
            hrvBalancedUpper = baseline?.dbl("balancedUpper") ?: d.hrvBalancedUpper,
        )
    }

    /** bodyBatteryWake is the curve sample nearest [sleepEndGmt] within ±15 min; null if none or no sleepEnd. */
    fun mergeBodyBattery(e: DayEntity?, item: JsonObject, sleepEndGmt: Instant?): DayEntity {
        val d = base(e, item, "date")
        val wake = sleepEndGmt?.let { wakeLevel(parseBodyBatteryCurve(item), it) }
        return d.copy(bodyBatteryCharged = d.bodyBatteryCharged ?: item.int("charged"), bodyBatteryWake = wake)
    }

    /** Level of the [curve] sample nearest [sleepEnd], only within ±15 min; null otherwise. */
    fun wakeLevel(curve: List<HrPoint>, sleepEnd: Instant): Int? = curve
        .minByOrNull { abs(it.epochSec - sleepEnd.epochSecond) }
        ?.takeIf { abs(it.epochSec - sleepEnd.epochSecond) <= WAKE_WINDOW_SEC }
        ?.bpm

    /**
     * The all-day curve as [HrPoint]s (`bpm` = level 0..100), epochSec = GMT ms / 1000. Null levels are skipped,
     * duplicate timestamps keep the last value, result sorted by time. Columns are found via the descriptor list.
     */
    fun parseBodyBatteryCurve(item: JsonObject): List<HrPoint> {
        val cols =
            item
                .arr("bodyBatteryValueDescriptorDTOList")
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { c -> c.str("bodyBatteryValueDescriptorKey")?.let { k -> c.int("bodyBatteryValueDescriptorIndex")?.let { k to it } } }
                .toMap()
        val tsCol = cols["timestamp"] ?: return emptyList()
        val levelCol = cols["bodyBatteryLevel"] ?: return emptyList()
        val byTime = sortedMapOf<Long, Int>()
        for (row in item.arr("bodyBatteryValuesArray").orEmpty()) {
            val r = row as? JsonArray ?: continue
            val ms = (r.getOrNull(tsCol) as? JsonPrimitive)?.numberOrNull() ?: continue
            val level = (r.getOrNull(levelCol) as? JsonPrimitive)?.numberOrNull() ?: continue
            byTime[ms.toLong() / 1000] = level.toInt()
        }
        return byTime.map { (t, l) -> HrPoint(t, l) }
    }

    private const val WAKE_WINDOW_SEC = 15 * 60L

    fun mergeStress(e: DayEntity?, item: JsonObject): DayEntity {
        val d = base(e, item)
        return d.copy(stressAvg = d.stressAvg ?: noData(item.obj("values")?.int("overallStressLevel")))
    }

    fun mergeSteps(e: DayEntity?, item: JsonObject): DayEntity {
        val d = base(e, item)
        return d.copy(steps = item.int("totalSteps") ?: d.steps, stepGoal = item.int("stepGoal") ?: d.stepGoal)
    }

    /** `heartRateValues` is `[[epochMs, bpm|null], ...]`; null samples are dropped. */
    fun parseHeartRateDay(json: JsonObject): Pair<LocalDate, List<HrPoint>> {
        val points =
            json.arr("heartRateValues").orEmpty().mapNotNull { row ->
                val r = row as? JsonArray ?: return@mapNotNull null
                val ms = (r.getOrNull(0) as? JsonPrimitive)?.numberOrNull() ?: return@mapNotNull null
                val bpm = (r.getOrNull(1) as? JsonPrimitive)?.numberOrNull() ?: return@mapNotNull null
                HrPoint(ms.toLong() / 1000, bpm.toInt())
            }
        return date(json, "calendarDate") to points
    }

    /** Garmin reports -1 for "no stress data" on days without any. */
    private fun noData(level: Int?) = level?.takeIf { it >= 0 }

    private fun date(o: JsonObject, key: String): LocalDate {
        val s = o.str(key) ?: throw IllegalArgumentException("missing $key")
        return try {
            LocalDate.parse(s)
        } catch (ex: DateTimeParseException) {
            throw IllegalArgumentException("bad $key: $s", ex)
        }
    }

    private fun base(e: DayEntity?, o: JsonObject, key: String = "calendarDate"): DayEntity {
        val date = date(o, key)
        require(e == null || e.date == date) { "merging $date into ${e?.date}" }
        return e ?: DayEntity(date)
    }

    private val ADJUSTMENTS = listOf("trainingFeedback", "sleepHistoryAdjustment", "hrvAdjustment", "napAdjustment")

    private fun reasons(next: JsonObject): String? = ADJUSTMENTS
        .mapNotNull { k -> next.str(k)?.takeIf { it != "NO_CHANGE" }?.let { "$k=$it" } }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(",")
}
