package dev.baseline.sync

import androidx.room3.withWriteTransaction
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.BodyBatteryDayEntity
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.HrDayEntity
import dev.baseline.data.db.HrPacking
import dev.baseline.data.db.RawResponse
import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.TaskStatus
import dev.baseline.data.parse.ActivityParser
import dev.baseline.data.parse.DayParser
import dev.baseline.data.parse.arr
import dev.baseline.data.parse.str
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminEndpoint.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** [changedFrom]: earliest day whose typed rows were written or deleted, null if none. [empty]: Garmin's no-data placeholder, nothing written. */
data class IngestResult(val changedFrom: LocalDate?, val empty: Boolean = false)

internal fun earliest(a: LocalDate?, b: LocalDate?): LocalDate? = if (a == null) b else if (b == null) a else minOf(a, b)

/**
 * Raw Garmin response -> typed rows (spec §5). The raw row is stored first and kept even if parsing throws;
 * all typed writes of one response run in one transaction, so a failure leaves none of them behind.
 */
class RawIngest(
    private val db: AppDatabase,
    private val fitIngest: suspend (Long, ByteArray) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun apply(endpoint: GarminEndpoint, key: String, body: ByteArray): IngestResult {
        if (endpoint == FIT) {
            fitIngest(key.toLong(), body)
            return IngestResult(null)
        }
        db.rawDao().upsert(RawResponse(endpoint.name, key, body, Instant.now(clock)))
        if (endpoint in RAW_ONLY) return IngestResult(null)
        val json = Json.parseToJsonElement(String(body))
        val changed = db.withWriteTransaction { write(endpoint, key, json) }
        return IngestResult(changed, empty = endpoint == SLEEP_DAY && changed == null)
    }

    private suspend fun write(endpoint: GarminEndpoint, key: String, json: JsonElement): LocalDate? = when (endpoint) {
        DAILY_SUMMARIES -> mergeDays(json.jsonObject.items("data"), merge = DayParser::mergeDailySummary)
        HRV_RANGE -> mergeDays(json.jsonObject.items("hrvSummaries"), merge = DayParser::mergeHrv)
        // Real data has ~200 placeholder items with a null calendarDate.
        SLEEP_SUMMARIES -> mergeDays(json.jsonArray, skipUndated = true, rewake = true, merge = DayParser::mergeSleepSummary)
        BODY_BATTERY -> {
            val items = json.jsonArray
            val first = mergeDays(items, "date") { e, item -> DayParser.mergeBodyBattery(e, item, e?.sleepEnd) }
            for (item in items.map { it.jsonObject }) { // mergeDays already rejected non-objects and bad dates
                val curve = DayParser.parseBodyBatteryCurve(item)
                if (curve.isNotEmpty()) { // an all-null day stays without a row
                    db.bodyBatteryDayDao().upsert(BodyBatteryDayEntity(LocalDate.parse(item.str("date")), HrPacking.pack(curve)))
                }
            }
            first
        }
        STRESS -> mergeDays(json.jsonArray, merge = DayParser::mergeStress)
        STEPS -> mergeDays(json.jsonArray, merge = DayParser::mergeSteps)
        // A night with no sleep comes back as a placeholder object without calendarDate. Only that is skipped: a non-object or a bad date still fails.
        SLEEP_DAY -> if (json is JsonObject && json.str("calendarDate") == null) null
            else mergeDays(JsonArray(listOf(json)), rewake = true, merge = DayParser::mergeSleepDay)
        HRV_DAY -> (json.jsonObject["hrvSummary"] as? JsonObject)
            ?.let { mergeDays(JsonArray(listOf(it)), merge = DayParser::mergeHrv) }
        HEART_RATE_DAY -> {
            val (date, points) = DayParser.parseHeartRateDay(json.jsonObject)
            db.hrDayDao().upsert(HrDayEntity(date, HrPacking.pack(points)))
            date
        }
        ACTIVITY_PAGE -> {
            val items = json.jsonArray
            val n = key.toInt()
            if (items.size == PAGE_SIZE) {
                db.taskDao().insertIgnore(listOf(task(ACTIVITY_PAGE, "${n + 1}", 5)))
            }
            upsertActivities(items).first
        }
        ACTIVITY_RANGE -> {
            val (a, b) = key.split("..").map(LocalDate::parse)
            val items = json.jsonArray
            val (changed, seen) = upsertActivities(items)
            // The window's oldest day is excluded (Garmin may filter by GMT date, so it can be missing legitimately);
            // a full page may have more pages, so it deletes nothing. Tomorrow's window covers `a` as an interior day.
            val gone = if (items.size >= PAGE_SIZE) emptyList() else
                (1..ChronoUnit.DAYS.between(a, b)).flatMap { db.activityDao().byDay(a.plusDays(it)) }.filter { it.id !in seen }
            db.activityDao().delete(gone.map { it.id })
            earliest(changed, gone.minOfOrNull { it.day })
        }
        else -> null
    }

    private fun JsonObject.items(key: String) = arr(key) ?: throw IllegalArgumentException("missing $key")

    /**
     * Each item merges into the day row for its own date; the whole body is one transaction.
     * [rewake]: sleep data may arrive after Body Battery, so recompute bodyBatteryWake from the stored curve once sleepEnd is known.
     */
    private suspend fun mergeDays(
        items: List<JsonElement>,
        dateKey: String = "calendarDate",
        skipUndated: Boolean = false,
        rewake: Boolean = false,
        merge: (DayEntity?, JsonObject) -> DayEntity,
    ): LocalDate? {
        var first: LocalDate? = null
        for (el in items) {
            val item = el as? JsonObject ?: if (skipUndated) continue else throw IllegalArgumentException("item is not an object")
            val date = item.str(dateKey)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (date == null && skipUndated) continue
            val merged = merge(date?.let { db.dayDao().get(it) }, item) // a bad date makes merge throw
            db.dayDao().upsert(if (rewake) withWake(merged) else merged)
            first = earliest(first, merged.date)
        }
        return first
    }

    private suspend fun withWake(d: DayEntity): DayEntity {
        val sleepEnd = d.sleepEnd ?: return d
        val curve = db.bodyBatteryDayDao().get(d.date) ?: return d
        return d.copy(bodyBatteryWake = DayParser.wakeLevel(HrPacking.unpack(curve.packed), sleepEnd))
    }

    /** Upserts (keeping the FIT-derived fitPath/hrCoverage/poolLengthM) and queues a FIT task for each activity without a file. Returns earliest day and ids seen. */
    private suspend fun upsertActivities(items: List<JsonElement>): Pair<LocalDate?, Set<Long>> {
        val dao = db.activityDao()
        var first: LocalDate? = null
        val fit = mutableListOf<SyncTaskEntity>()
        val ids = mutableSetOf<Long>()
        for (el in items) {
            val a = ActivityParser.parseListItem(el.jsonObject)
            val old = dao.get(a.id)
            dao.upsert(a.copy(fitPath = old?.fitPath, hrCoverage = old?.hrCoverage, poolLengthM = a.poolLengthM ?: old?.poolLengthM))
            if (old?.fitPath == null) fit += task(FIT, "${a.id}", 7)
            first = earliest(first, earliest(a.day, old?.day))
            ids += a.id
        }
        db.taskDao().insertIgnore(fit)
        return first to ids
    }

    private fun task(e: GarminEndpoint, key: String, priority: Int) =
        SyncTaskEntity(e.name, key, priority, TaskStatus.PENDING, 0, null)

    private companion object {
        const val PAGE_SIZE = 50
        // Parsed later (profile/zones) or not yet used.
        val RAW_ONLY = setOf(PROFILE, HR_ZONES, SPO2_DAY, ACTIVITY_DETAIL)
    }
}
