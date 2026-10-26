package dev.baseline.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert
import dev.baseline.data.SportFamily
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

private const val BACKFILL_PENDING =
    "SELECT NOT EXISTS(SELECT 1 FROM sync_task) OR " +
        "EXISTS(SELECT 1 FROM sync_task WHERE status = 'PENDING' AND priority BETWEEN 1 AND 6)"

/**
 * A FAILED unreadable task covers `:date` (a per-day key, or a "from..to" range key containing it) and no DONE or EMPTY
 * task `d` of the same endpoint does: a retry or the incremental window may have fixed that day since. Tasks carry no
 * timestamp, so a range `d` only counts when it ends no earlier than `f` does: yesterday's DONE window must not hide today's
 * failure. A per-day `d` is that day's own row, so it always counts.
 */
private const val UNREADABLE_DAY =
    "SELECT EXISTS(SELECT 1 FROM sync_task f WHERE f.status = 'FAILED' AND f.lastError LIKE 'unreadable:%' AND " +
        "f.endpoint IN (:endpoints) AND " +
        "(f.`key` = :date OR (length(f.`key`) = 22 AND substr(f.`key`, 1, 10) <= :date AND substr(f.`key`, 13, 10) >= :date)) AND " +
        "NOT EXISTS(SELECT 1 FROM sync_task d WHERE d.endpoint = f.endpoint AND d.status IN ('DONE', 'EMPTY') AND " +
        "(d.`key` = :date OR (length(d.`key`) = 22 AND substr(d.`key`, 1, 10) <= :date AND substr(d.`key`, 13, 10) >= :date AND " +
        "substr(d.`key`, 13, 10) >= substr(f.`key`, -10)))))"

/** Endpoints whose responses make up a day's figures (Today). */
val DAY_ENDPOINTS =
    listOf(
        "DAILY_SUMMARIES",
        "HRV_RANGE",
        "SLEEP_SUMMARIES",
        "BODY_BATTERY",
        "STRESS",
        "STEPS",
        "SLEEP_DAY",
        "HEART_RATE_DAY",
        "HRV_DAY",
        "SPO2_DAY",
        "ACTIVITY_RANGE",
    )

/** The subset behind the Sleep screen. */
val SLEEP_ENDPOINTS = listOf("SLEEP_SUMMARIES", "SLEEP_DAY", "HRV_RANGE", "HRV_DAY", "HEART_RATE_DAY", "SPO2_DAY")

data class TaskCounts(val done: Int, val total: Int)

data class ActiveSetCount(val activityId: Long, val sets: Int)

/**
 * Unlike [BACKFILL_PENDING] this includes the per-activity FIT tasks: strain calibration is only final once the streams exist.
 * Any PENDING FIT task counts, whatever its priority (the activity screen queues a missing one at priority 0); other
 * incremental priority-0 tasks do not.
 */
private const val CALIBRATION_PENDING =
    "SELECT NOT EXISTS(SELECT 1 FROM sync_task) OR " +
        "EXISTS(SELECT 1 FROM sync_task WHERE status = 'PENDING' AND (priority BETWEEN 1 AND 7 OR endpoint = 'FIT'))"

@Dao
interface RawDao {
    @Upsert suspend fun upsert(r: RawResponse)

    @Query("SELECT * FROM raw_response WHERE endpoint = :endpoint AND `key` = :key")
    suspend fun get(endpoint: String, key: String): RawResponse?

    @Query("SELECT `key` FROM raw_response WHERE endpoint = :endpoint")
    suspend fun keys(endpoint: String): List<String>

    /** Zero or one row; the planner stores the profile under "-", the connection test under "", so the key isn't assumed. */
    @Query("SELECT json FROM raw_response WHERE endpoint = 'PROFILE' LIMIT 1")
    fun profileJson(): Flow<List<ByteArray>>

    /** Empty until the row arrives, then one element. */
    @Query("SELECT json FROM raw_response WHERE endpoint = :endpoint AND `key` = :key")
    fun json(endpoint: String, key: String): Flow<List<ByteArray>>
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tasks: List<SyncTaskEntity>)

    @Query("SELECT * FROM sync_task WHERE status = 'PENDING' ORDER BY priority ASC, `key` DESC LIMIT 1")
    suspend fun nextPending(): SyncTaskEntity?

    @Upsert suspend fun upsertAll(tasks: List<SyncTaskEntity>)

    @Update suspend fun update(t: SyncTaskEntity)

    @Query("DELETE FROM sync_task WHERE endpoint = :endpoint AND `key` = :key")
    suspend fun delete(endpoint: String, key: String)

    @Query("SELECT COUNT(*) FROM sync_task")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) > 0 FROM sync_task WHERE endpoint = :endpoint AND `key` = :key")
    suspend fun exists(endpoint: String, key: String): Boolean

    @Query("SELECT * FROM sync_task")
    suspend fun all(): List<SyncTaskEntity>

    /** Null while no such task exists. */
    @Query("SELECT status FROM sync_task WHERE endpoint = :endpoint AND `key` = :key")
    fun status(endpoint: String, key: String): Flow<TaskStatus?>

    @Query(
        "SELECT COALESCE(SUM(status IN ('DONE', 'EMPTY', 'FAILED')), 0) AS done, COUNT(*) AS total FROM sync_task",
    )
    fun counts(): Flow<TaskCounts>

    /**
     * First-run work is unfinished: nothing is planned yet (the worker plans on its first run), or a PENDING task has
     * priority 1..6 (not incremental 0, not per-activity FIT 7).
     */
    @Query(BACKFILL_PENDING)
    suspend fun backfillPending(): Boolean

    @Query(CALIBRATION_PENDING)
    suspend fun calibrationPending(): Boolean

    @Query(BACKFILL_PENDING)
    fun backfillPendingFlow(): Flow<Boolean>

    /** Nights without sleep used to fail as "missing calendarDate"; they are empty, not broken. Idempotent. */
    @Query("UPDATE sync_task SET status = 'EMPTY', lastError = NULL WHERE status = 'FAILED' AND endpoint = 'SLEEP_DAY' AND lastError = 'missing calendarDate'")
    suspend fun repairSleepPlaceholders()

    /**
     * A response for [date] (a per-day key, or a "from..to" range holding it) was kept raw but could not be parsed.
     * Plain HTTP failures are not counted: those say "retry", not "unreadable".
     */
    @Query(UNREADABLE_DAY)
    fun unreadableDay(date: String, endpoints: List<String>): Flow<Boolean>

    /**
     * Re-queues FAILED range tasks (a "from..to" key) of [endpoints] that overlap [from]..[to] and failed to parse: the new
     * incremental window covers their days again, so an unreadable one gets another chance instead of keeping its note
     * forever. HTTP failures stay failed (a retry would repeat them), and `attempts` stays so a split is not re-armed.
     */
    @Query(
        "UPDATE sync_task SET status = 'PENDING', lastError = NULL WHERE status = 'FAILED' AND lastError LIKE 'unreadable:%' AND " +
            "endpoint IN (:endpoints) AND length(`key`) = 22 AND substr(`key`, 1, 10) <= :to AND substr(`key`, 13, 10) >= :from",
    )
    suspend fun requeueFailedOverlapping(endpoints: List<String>, from: String, to: String)

    @Query("SELECT COUNT(*) FROM sync_task WHERE status = 'FAILED'")
    fun failedCount(): Flow<Int>

    /** The user's "Retry failed": every FAILED task goes back to PENDING. `attempts` stays so a split range is not split again. */
    @Query("UPDATE sync_task SET status = 'PENDING', lastError = NULL WHERE status = 'FAILED'")
    suspend fun requeueAllFailed()
}

@Dao
interface DayDao {
    @Upsert suspend fun upsert(d: DayEntity)

    @Query("SELECT * FROM day WHERE date BETWEEN :from AND :to ORDER BY date")
    fun range(from: LocalDate, to: LocalDate): Flow<List<DayEntity>>

    @Query("SELECT * FROM day WHERE date = :date")
    suspend fun get(date: LocalDate): DayEntity?

    @Query("SELECT * FROM day ORDER BY date")
    suspend fun all(): List<DayEntity>

    @Query("SELECT MIN(date) FROM day")
    fun firstDate(): Flow<LocalDate?>

    @Query("SELECT * FROM day WHERE date = :date")
    fun observe(date: LocalDate): Flow<DayEntity?>
}

@Dao
interface HrDayDao {
    @Upsert suspend fun upsert(h: HrDayEntity)

    @Query("SELECT * FROM hr_day WHERE date = :date")
    suspend fun get(date: LocalDate): HrDayEntity?

    @Query("SELECT * FROM hr_day WHERE date = :date")
    fun observe(date: LocalDate): Flow<HrDayEntity?>
}

@Dao
interface BodyBatteryDayDao {
    @Upsert suspend fun upsert(b: BodyBatteryDayEntity)

    @Query("SELECT * FROM body_battery_day WHERE date = :date")
    suspend fun get(date: LocalDate): BodyBatteryDayEntity?

    @Query("SELECT * FROM body_battery_day WHERE date = :date")
    fun flow(date: LocalDate): Flow<BodyBatteryDayEntity?>
}

// Upsert (not REPLACE): REPLACE would delete the row and cascade-delete its laps/lengths/sets.
@Dao
interface ActivityDao {
    @Upsert suspend fun upsert(a: ActivityEntity)

    @Query("SELECT * FROM activity WHERE id = :id")
    suspend fun get(id: Long): ActivityEntity?

    @Query("SELECT * FROM activity WHERE id = :id")
    fun observe(id: Long): Flow<ActivityEntity?>

    @Query("SELECT * FROM activity WHERE day = :date ORDER BY startGmt")
    suspend fun byDay(date: LocalDate): List<ActivityEntity>

    @Query("SELECT * FROM activity WHERE day = :date ORDER BY startGmt")
    fun observeByDay(date: LocalDate): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activity ORDER BY startGmt DESC")
    fun all(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activity WHERE day BETWEEN :from AND :to ORDER BY startGmt")
    fun between(from: LocalDate, to: LocalDate): Flow<List<ActivityEntity>>

    @Query("UPDATE activity SET hrCoverage = :coverage WHERE id = :id")
    suspend fun setHrCoverage(id: Long, coverage: Double)

    @Query("SELECT * FROM activity")
    suspend fun list(): List<ActivityEntity>

    @Query("DELETE FROM activity WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT MIN(day) FROM activity")
    fun firstDay(): Flow<LocalDate?>
}

@Dao
interface ActivityStreamDao {
    @Upsert suspend fun upsert(s: ActivityStreamEntity)

    @Query("SELECT * FROM activity_stream WHERE activityId = :activityId")
    suspend fun get(activityId: Long): ActivityStreamEntity?

    @Query("SELECT * FROM activity_stream WHERE activityId = :activityId")
    fun observe(activityId: Long): Flow<ActivityStreamEntity?>
}

@Dao
interface LapDao {
    @Insert suspend fun insertAll(laps: List<LapEntity>)

    @Query("DELETE FROM activity_lap WHERE activityId = :activityId")
    suspend fun deleteFor(activityId: Long)

    @Query("SELECT * FROM activity_lap WHERE activityId = :activityId ORDER BY lapIndex")
    suspend fun forActivity(activityId: Long): List<LapEntity>
}

@Dao
interface SwimLengthDao {
    @Insert suspend fun insertAll(lengths: List<SwimLengthEntity>)

    @Query("DELETE FROM swim_length WHERE activityId = :activityId")
    suspend fun deleteFor(activityId: Long)

    @Query("SELECT * FROM swim_length WHERE activityId = :activityId ORDER BY lengthIndex")
    suspend fun forActivity(activityId: Long): List<SwimLengthEntity>

    @Query("SELECT * FROM swim_length WHERE activityId = :activityId ORDER BY lengthIndex")
    fun observe(activityId: Long): Flow<List<SwimLengthEntity>>
}

@Dao
interface StrengthSetDao {
    @Insert suspend fun insertAll(sets: List<StrengthSetEntity>)

    @Query("DELETE FROM strength_set WHERE activityId = :activityId")
    suspend fun deleteFor(activityId: Long)

    @Query("SELECT * FROM strength_set WHERE activityId = :activityId ORDER BY setIndex")
    suspend fun forActivity(activityId: Long): List<StrengthSetEntity>

    @Query("SELECT * FROM strength_set WHERE activityId = :activityId ORDER BY setIndex")
    fun observe(activityId: Long): Flow<List<StrengthSetEntity>>

    @Query("SELECT activityId, COUNT(*) AS sets FROM strength_set WHERE setType = 'ACTIVE' GROUP BY activityId")
    fun activeCounts(): Flow<List<ActiveSetCount>>
}

@Dao
interface ScoreDao {
    @Upsert suspend fun upsert(s: ScoreEntity)

    @Query("SELECT * FROM score WHERE date = :date")
    suspend fun get(date: LocalDate): ScoreEntity?

    @Query("SELECT * FROM score WHERE date = :date")
    fun observe(date: LocalDate): Flow<ScoreEntity?>

    @Query("SELECT * FROM score WHERE date BETWEEN :from AND :to ORDER BY date")
    fun range(from: LocalDate, to: LocalDate): Flow<List<ScoreEntity>>
}

@Dao
interface ActivityScoreDao {
    @Upsert suspend fun upsert(s: ActivityScoreEntity)

    @Query("SELECT * FROM activity_score WHERE activityId = :activityId")
    suspend fun get(activityId: Long): ActivityScoreEntity?

    @Query("SELECT * FROM activity_score WHERE activityId = :activityId")
    fun observe(activityId: Long): Flow<ActivityScoreEntity?>

    @Query("DELETE FROM activity_score WHERE activityId = :activityId")
    suspend fun delete(activityId: Long)

    @Query("SELECT * FROM activity_score")
    fun all(): Flow<List<ActivityScoreEntity>>

    @Query("SELECT s.* FROM activity_score s JOIN activity a ON a.id = s.activityId WHERE a.day = :date")
    fun forDay(date: LocalDate): Flow<List<ActivityScoreEntity>>
}

@Dao
interface BestEffortDao {
    @Upsert suspend fun upsert(b: BestEffortEntity)

    @Query("SELECT * FROM best_effort WHERE sport = :sport")
    suspend fun forSport(sport: SportFamily): List<BestEffortEntity>

    @Query("SELECT * FROM best_effort")
    fun all(): Flow<List<BestEffortEntity>>

    @Query("DELETE FROM best_effort")
    suspend fun deleteAll()

    @Insert suspend fun insertAll(rows: List<BestEffortEntity>)
}
