package dev.baseline.data

import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.ActivityStreamEntity
import dev.baseline.data.db.BestEffortEntity
import dev.baseline.data.db.StrengthSetEntity
import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.SwimLengthEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.DAY_ENDPOINTS
import dev.baseline.data.db.SLEEP_ENDPOINTS
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.HrPacking
import dev.baseline.data.db.ScoreEntity
import dev.baseline.data.db.TaskStatus
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminTime
import dev.baseline.scores.HrPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.DayOfWeek
import dev.baseline.data.db.TaskCounts
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.KeyStatus
import dev.baseline.prefs.UserPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

/** The UI's only data source: Flows over Room, prefs and the vault. It never touches the network. */
class Repository(private val db: AppDatabase, val prefs: UserPrefs, private val vault: ApiKeyVault) {
    fun day(date: LocalDate): Flow<DayEntity?> = db.dayDao().observe(date)
    fun days(from: LocalDate, to: LocalDate): Flow<List<DayEntity>> = db.dayDao().range(from, to)
    fun activitiesOn(date: LocalDate): Flow<List<ActivityEntity>> = db.activityDao().observeByDay(date)
    fun activityScores(date: LocalDate): Flow<List<ActivityScoreEntity>> = db.activityScoreDao().forDay(date)
    fun score(date: LocalDate): Flow<ScoreEntity?> = db.scoreDao().observe(date)
    fun scores(from: LocalDate, to: LocalDate): Flow<List<ScoreEntity>> = db.scoreDao().range(from, to)
    fun activities(): Flow<List<ActivityEntity>> = db.activityDao().all()
    fun activity(id: Long): Flow<ActivityEntity?> = db.activityDao().observe(id)
    fun activityScore(id: Long): Flow<ActivityScoreEntity?> = db.activityScoreDao().observe(id)

    /** Null until the activity's FIT file is ingested. */
    fun activityStream(id: Long): Flow<ActivityStreamEntity?> = db.activityStreamDao().observe(id)
    fun swimLengths(id: Long): Flow<List<SwimLengthEntity>> = db.swimLengthDao().observe(id)
    fun strengthSets(id: Long): Flow<List<StrengthSetEntity>> = db.strengthSetDao().observe(id)
    fun bestEfforts(): Flow<List<BestEffortEntity>> = db.bestEffortDao().all()
    fun allActivityScores(): Flow<List<ActivityScoreEntity>> = db.activityScoreDao().all()

    /** Active (non-rest) set count per strength activity; absent until the FIT file is ingested. */
    fun activeSetCounts(): Flow<Map<Long, Int>> = db.strengthSetDao().activeCounts().map { rows -> rows.associate { it.activityId to it.sets } }
    fun activitiesBetween(from: LocalDate, to: LocalDate): Flow<List<ActivityEntity>> = db.activityDao().between(from, to)

    /** The day's all-day heart-rate samples in time order (about every 2 minutes); empty when none are stored. */
    fun heartRate(date: LocalDate): Flow<List<HrPoint>> = db.hrDayDao().observe(date).map { row ->
        row?.let { HrPacking.unpack(it.packed) }.orEmpty().sortedBy { it.epochSec }
    }

    /** Overnight HRV readings (about every 5 minutes) of the night ending on [date]; null until the HRV_DAY row is stored. */
    fun hrvReadings(date: LocalDate): Flow<List<Pair<Instant, Int>>?> =
        db.rawDao().json(GarminEndpoint.HRV_DAY.name, date.toString()).map { rows -> rows.firstOrNull()?.let(::parseHrvReadings) }

    /** Queues the activity's FIT download as a PENDING priority-0 task (the caller then asks the scheduler to sync). */
    suspend fun requestFit(id: Long) =
        db.taskDao().upsertAll(listOf(SyncTaskEntity(GarminEndpoint.FIT.name, id.toString(), 0, TaskStatus.PENDING, 0, null)))

    fun taskStatus(endpoint: GarminEndpoint, key: String): Flow<TaskStatus?> = db.taskDao().status(endpoint.name, key)

    /** The day's Body Battery samples (level 0..100) in time order; empty when Garmin sent none. */
    fun bodyBatteryCurve(date: LocalDate): Flow<List<Pair<Instant, Int>>> = db.bodyBatteryDayDao().flow(date).map { row ->
        row?.let { HrPacking.unpack(it.packed) }.orEmpty().sortedBy { it.epochSec }.map { Instant.ofEpochSecond(it.epochSec) to it.bpm }
    }

    /** True when the Garmin profile's measurementSystem is a statute one ("statute_us"); metric when unknown. */
    val imperial: Flow<Boolean> = db.rawDao().profileJson().map { rows ->
        val system = rows.firstOrNull()?.let {
            runCatching { Json.parseToJsonElement(String(it)).jsonObject["measurementSystem"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
        system?.startsWith("statute") == true
    }.distinctUntilChanged()

    /** The Garmin profile's first day of the week ("firstDayOfWeek": {"dayName": "sunday"}, or the bare name); Monday when unknown. */
    val firstDayOfWeek: Flow<DayOfWeek> = db.rawDao().profileJson().map { rows ->
        val el = rows.firstOrNull()?.let { runCatching { Json.parseToJsonElement(String(it)).jsonObject["firstDayOfWeek"] }.getOrNull() }
        val name = when (el) {
            is JsonObject -> el["dayName"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> el.contentOrNull
            else -> null
        }
        DayOfWeek.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DayOfWeek.MONDAY
    }.distinctUntilChanged()

    /** True when a response holding [date] was stored raw but could not be parsed (spec §10 "Malformed JSON"). */
    fun dayUnreadable(date: LocalDate, sleepOnly: Boolean = false): Flow<Boolean> =
        db.taskDao().unreadableDay(date.toString(), if (sleepOnly) SLEEP_ENDPOINTS else DAY_ENDPOINTS)

    /** Earliest stored health day and activity day; null while there are none. */
    val historyStart: Flow<Pair<LocalDate?, LocalDate?>> =
        combine(db.dayDao().firstDate(), db.activityDao().firstDay()) { health, activities -> health to activities }

    val keyStatus: Flow<KeyStatus> get() = vault.status
    val syncProgress: Flow<TaskCounts> get() = db.taskDao().counts()
}

private fun parseHrvReadings(json: ByteArray): List<Pair<Instant, Int>> = runCatching {
    val readings = Json.parseToJsonElement(String(json)).jsonObject["hrvReadings"] as? JsonArray ?: return emptyList()
    readings.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val value = o["hrvValue"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val at = o["readingTimeGMT"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        GarminTime.parseGmt(at) to value
    }.sortedBy { it.first }
}.getOrDefault(emptyList())
