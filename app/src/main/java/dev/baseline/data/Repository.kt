package dev.baseline.data

import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.ActivityStreamEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.BestEffortEntity
import dev.baseline.data.db.DAY_ENDPOINTS
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.HrPacking
import dev.baseline.data.db.SLEEP_ENDPOINTS
import dev.baseline.data.db.ScoreEntity
import dev.baseline.data.db.StrengthSetEntity
import dev.baseline.data.db.SwimLengthEntity
import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.TaskCounts
import dev.baseline.data.db.TaskStatus
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminTime
import dev.baseline.garmin.KeyStatus
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.HrPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * The UI's only data source: Flows over Room, prefs and the vault. It never touches the network.
 *
 * Room re-runs a query on every write to its table, even when the rows it returns are unchanged (a backfill writes
 * ~1,200 responses), so every Flow here drops repeats before its collectors rebuild UI state. Entities with a
 * [ByteArray] are not data classes: those are compared by content, or mapped to value types first.
 */
class Repository(private val db: AppDatabase, val prefs: UserPrefs, private val vault: ApiKeyVault) {
    fun day(date: LocalDate): Flow<DayEntity?> = db.dayDao().observe(date).distinctUntilChanged()

    fun days(from: LocalDate, to: LocalDate): Flow<List<DayEntity>> = db.dayDao().range(from, to).distinctUntilChanged()

    fun activitiesOn(date: LocalDate): Flow<List<ActivityEntity>> = db.activityDao().observeByDay(date).distinctUntilChanged()

    fun activityScores(date: LocalDate): Flow<List<ActivityScoreEntity>> = db.activityScoreDao().forDay(date).distinctUntilChanged()

    fun score(date: LocalDate): Flow<ScoreEntity?> = db.scoreDao().observe(date).distinctUntilChanged()

    fun scores(from: LocalDate, to: LocalDate): Flow<List<ScoreEntity>> = db.scoreDao().range(from, to).distinctUntilChanged()

    fun activities(): Flow<List<ActivityEntity>> = db.activityDao().all().distinctUntilChanged()

    fun activity(id: Long): Flow<ActivityEntity?> = db.activityDao().observe(id).distinctUntilChanged()

    /** The newest activity by local start time, or null while there is none. */
    fun latestActivity(): Flow<ActivityEntity?> = db.activityDao().latest().distinctUntilChanged()

    fun activityScore(id: Long): Flow<ActivityScoreEntity?> = db.activityScoreDao().observe(id).distinctUntilChanged()

    fun activityScoresFor(ids: List<Long>): Flow<List<ActivityScoreEntity>> = db.activityScoreDao().forIds(ids).distinctUntilChanged()

    /** Null until the activity's FIT file is ingested. */
    fun activityStream(id: Long): Flow<ActivityStreamEntity?> =
        db.activityStreamDao().observe(id).distinctUntilChanged { a, b -> a?.activityId == b?.activityId && sameBytes(a?.packed, b?.packed) }

    fun swimLengths(id: Long): Flow<List<SwimLengthEntity>> = db.swimLengthDao().observe(id).distinctUntilChanged()

    fun strengthSets(id: Long): Flow<List<StrengthSetEntity>> = db.strengthSetDao().observe(id).distinctUntilChanged()

    fun bestEfforts(): Flow<List<BestEffortEntity>> = db.bestEffortDao().all().distinctUntilChanged()

    fun allActivityScores(): Flow<List<ActivityScoreEntity>> = db.activityScoreDao().all().distinctUntilChanged()

    /** Active (non-rest) set count per strength activity; absent until the FIT file is ingested. */
    fun activeSetCounts(): Flow<Map<Long, Int>> = db.strengthSetDao().activeCounts().map { rows ->
        rows.associate { it.activityId to it.sets }
    }.distinctUntilChanged()

    /** Like [activeSetCounts], for just [ids]. */
    fun activeSetCountsFor(ids: List<Long>): Flow<Map<Long, Int>> =
        db.strengthSetDao().activeCounts(ids).map { rows -> rows.associate { it.activityId to it.sets } }.distinctUntilChanged()

    fun activitiesBetween(from: LocalDate, to: LocalDate): Flow<List<ActivityEntity>> = db.activityDao().between(from, to).distinctUntilChanged()

    /** The day's all-day heart-rate samples in time order (about every 2 minutes); empty when none are stored. */
    fun heartRate(date: LocalDate): Flow<List<HrPoint>> = db
        .hrDayDao()
        .observe(date)
        .distinctUntilChanged { a, b -> sameBytes(a?.packed, b?.packed) }
        .map { row -> row?.let { HrPacking.unpack(it.packed) }.orEmpty().sortedBy { it.epochSec } }

    /** Overnight HRV readings (about every 5 minutes) of the night ending on [date]; null until the HRV_DAY row is stored. */
    fun hrvReadings(date: LocalDate): Flow<List<Pair<Instant, Int>>?> = db
        .rawDao()
        .json(GarminEndpoint.HRV_DAY.name, date.toString())
        .distinctContent()
        .map { rows -> rows.firstOrNull()?.let(::parseHrvReadings) }
        .distinctUntilChanged()

    /** Queues the activity's FIT download as a PENDING priority-0 task (the caller then asks the scheduler to sync). */
    suspend fun requestFit(id: Long) = db.taskDao().upsertAll(listOf(SyncTaskEntity(GarminEndpoint.FIT.name, id.toString(), 0, TaskStatus.PENDING, 0, null)))

    fun taskStatus(endpoint: GarminEndpoint, key: String): Flow<TaskStatus?> = db.taskDao().status(endpoint.name, key).distinctUntilChanged()

    /** The day's Body Battery samples (level 0..100) in time order; empty when Garmin sent none. */
    fun bodyBatteryCurve(date: LocalDate): Flow<List<Pair<Instant, Int>>> = db
        .bodyBatteryDayDao()
        .flow(date)
        .distinctUntilChanged { a, b -> sameBytes(a?.packed, b?.packed) }
        .map { row ->
            row
                ?.let { HrPacking.unpack(it.packed) }
                .orEmpty()
                .sortedBy { it.epochSec }
                .map { Instant.ofEpochSecond(it.epochSec) to it.bpm }
        }

    /** The stored Garmin profile, parsed once per change; null when absent or not a JSON object. Shared by [imperial] and [firstDayOfWeek]. */
    private val profile: Flow<JsonObject?> =
        db
            .rawDao()
            .profileJson()
            .distinctContent()
            .map { rows -> rows.firstOrNull()?.let { runCatching { Json.parseToJsonElement(String(it)).jsonObject }.getOrNull() } }
            .distinctUntilChanged()

    /** True when the Garmin profile's measurementSystem is a statute one ("statute_us"); metric when unknown. */
    val imperial: Flow<Boolean> =
        profile
            .map { p -> runCatching { p?.get("measurementSystem")?.jsonPrimitive?.contentOrNull }.getOrNull()?.startsWith("statute") == true }
            .distinctUntilChanged()

    /** The Garmin profile's first day of the week ("firstDayOfWeek": {"dayName": "sunday"}, or the bare name); Monday when unknown. */
    val firstDayOfWeek: Flow<DayOfWeek> =
        profile
            .map { p ->
                val name =
                    when (val el = p?.get("firstDayOfWeek")) {
                        is JsonObject -> el["dayName"]?.jsonPrimitive?.contentOrNull
                        is JsonPrimitive -> el.contentOrNull
                        else -> null
                    }
                DayOfWeek.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DayOfWeek.MONDAY
            }.distinctUntilChanged()

    /** True when a response holding [date] was stored raw but could not be parsed (spec §10 "Malformed JSON"). */
    fun dayUnreadable(date: LocalDate, sleepOnly: Boolean = false): Flow<Boolean> =
        db.taskDao().unreadableDay(date.toString(), if (sleepOnly) SLEEP_ENDPOINTS else DAY_ENDPOINTS).distinctUntilChanged()

    /** Earliest stored health day and activity day; null while there are none. */
    val historyStart: Flow<Pair<LocalDate?, LocalDate?>> =
        combine(db.dayDao().firstDate(), db.activityDao().firstDay()) { health, activities -> health to activities }.distinctUntilChanged()

    val keyStatus: Flow<KeyStatus> get() = vault.status
    val syncProgress: Flow<TaskCounts> get() = db.taskDao().counts().distinctUntilChanged()
}

private fun sameBytes(a: ByteArray?, b: ByteArray?): Boolean = if (a == null || b == null) a == b else a.contentEquals(b)

/** Drops a repeat of the same blobs, so the parse after it only runs when a stored response actually changed. */
internal fun Flow<List<ByteArray>>.distinctContent(): Flow<List<ByteArray>> =
    distinctUntilChanged { a, b -> a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) } }

private fun parseHrvReadings(json: ByteArray): List<Pair<Instant, Int>> = runCatching {
    val readings = Json.parseToJsonElement(String(json)).jsonObject["hrvReadings"] as? JsonArray ?: return emptyList()
    readings
        .mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val value = o["hrvValue"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val at = o["readingTimeGMT"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            GarminTime.parseGmt(at) to value
        }.sortedBy { it.first }
}.getOrDefault(emptyList())
