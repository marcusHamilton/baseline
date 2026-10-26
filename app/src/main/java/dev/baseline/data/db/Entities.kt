package dev.baseline.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.baseline.data.SportFamily
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

enum class TaskStatus { PENDING, DONE, EMPTY, FAILED }

/** Starts [SyncTaskEntity.lastError] when the response arrived but could not be read; the raw row is kept. */
const val PARSE_ERROR_PREFIX = "unreadable: "

enum class LoadSource { HR, GARMIN_SCALED }

@Entity(tableName = "raw_response", primaryKeys = ["endpoint", "key"])
class RawResponse(
    val endpoint: String,
    val key: String,
    val json: ByteArray,
    val fetchedAt: Instant,
)

@Entity(tableName = "sync_task", primaryKeys = ["endpoint", "key"])
data class SyncTaskEntity(
    val endpoint: String,
    val key: String,
    val priority: Int,
    val status: TaskStatus,
    val attempts: Int,
    val lastError: String?,
)

/** Everything except [date] is nullable: Garmin omits fields freely and nothing may be faked. */
@Entity(tableName = "day")
data class DayEntity(
    @PrimaryKey val date: LocalDate,
    val restingHr: Int? = null,
    val respirationAvg: Double? = null,
    val stressAvg: Int? = null,
    val steps: Int? = null,
    val stepGoal: Int? = null,
    val bodyBatteryWake: Int? = null,
    val bodyBatteryCharged: Int? = null,
    val sleepSeconds: Int? = null,
    val deepSeconds: Int? = null,
    val lightSeconds: Int? = null,
    val remSeconds: Int? = null,
    val awakeSeconds: Int? = null,
    val sleepScore: Int? = null,
    /** That night's sleepNeed.actual. */
    val sleepNeedMinutes: Int? = null,
    val sleepStart: Instant? = null,
    val sleepEnd: Instant? = null,
    val spo2Avg: Double? = null,
    val hrvLastNight: Double? = null,
    val hrvWeeklyAvg: Double? = null,
    val hrvBalancedLow: Double? = null,
    val hrvBalancedUpper: Double? = null,
    // From nextSleepNeed.
    val nextSleepNeedBaselineMin: Int? = null,
    val nextSleepNeedActualMin: Int? = null,
    /** Comma-joined key=VALUE for the adjustment fields whose value is not NO_CHANGE. */
    val nextSleepNeedReasons: String? = null,
    val recommendedBedtimeMin: Int? = null,
)

/** See [HrPacking] for the blob layout. */
@Entity(tableName = "hr_day")
class HrDayEntity(@PrimaryKey val date: LocalDate, val packed: ByteArray)

/** All-day Body Battery curve, packed like [HrDayEntity] with [dev.baseline.scores.HrPoint.bpm] holding the level (0..100). */
@Entity(tableName = "body_battery_day")
class BodyBatteryDayEntity(@PrimaryKey val date: LocalDate, val packed: ByteArray)

@Entity(tableName = "activity", indices = [Index("day")])
data class ActivityEntity(
    @PrimaryKey val id: Long,
    val name: String?,
    val typeKey: String,
    val sportFamily: SportFamily,
    val startTimeLocal: LocalDateTime,
    /** Date part of [startTimeLocal]. */
    val day: LocalDate,
    val startGmt: Instant,
    val durationSec: Double,
    val distanceM: Double?,
    val avgHr: Double?,
    val maxHr: Double?,
    val zone1Sec: Double?,
    val zone2Sec: Double?,
    val zone3Sec: Double?,
    val zone4Sec: Double?,
    val zone5Sec: Double?,
    val garminLoad: Double?,
    val elevationGainM: Double?,
    val avgPower: Double?,
    val normPower: Double?,
    val fastestSplitsJson: String?,
    val fitPath: String?,
    val hrCoverage: Double?,
    val poolLengthM: Double?,
)

/** Per-second series, see [dev.baseline.fit.ActivityStreams.pack]. */
@Entity(tableName = "activity_stream", foreignKeys = [ForeignKey(ActivityEntity::class, ["id"], ["activityId"], onDelete = ForeignKey.CASCADE)])
class ActivityStreamEntity(@PrimaryKey val activityId: Long, val packed: ByteArray)

@Entity(
    tableName = "activity_lap",
    foreignKeys = [ForeignKey(ActivityEntity::class, ["id"], ["activityId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("activityId")],
)
data class LapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val lapIndex: Int,
    val startOffsetSec: Double?,
    val durationSec: Double?,
    val distanceM: Double?,
    val avgHr: Double?,
    val maxHr: Double?,
    val avgSpeed: Double?,
    val avgPower: Double?,
)

@Entity(
    tableName = "swim_length",
    foreignKeys = [ForeignKey(ActivityEntity::class, ["id"], ["activityId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("activityId")],
)
data class SwimLengthEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val lengthIndex: Int,
    /** Seconds from activity start. */
    val startOffsetSec: Double?,
    /** False for rest lengths (no swimming). */
    val active: Boolean,
    val durationSec: Double?,
    val strokeType: String?,
    val strokeCount: Int?,
)

@Entity(
    tableName = "strength_set",
    foreignKeys = [ForeignKey(ActivityEntity::class, ["id"], ["activityId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("activityId")],
)
data class StrengthSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val setIndex: Int,
    /** Seconds from activity start. */
    val startOffsetSec: Double?,
    val exerciseName: String?,
    /** FIT set_type name: ACTIVE or REST. */
    val setType: String?,
    /** FIT exercise_category of the first category entry. */
    val category: Int?,
    val reps: Int?,
    val weightKg: Double?,
    val durationSec: Double?,
)

@Entity(tableName = "score")
data class ScoreEntity(
    @PrimaryKey val date: LocalDate,
    val recovery: Int?,
    val recoveryPartial: Boolean,
    val sleepPerformance: Double?,
    val dayLoad: Double,
    val strain: Double,
    val strainTargetLow: Double?,
    val strainTargetHigh: Double?,
    val calibrationVersion: Int,
)

@Entity(tableName = "activity_score", foreignKeys = [ForeignKey(ActivityEntity::class, ["id"], ["activityId"], onDelete = ForeignKey.CASCADE)])
data class ActivityScoreEntity(
    @PrimaryKey val activityId: Long,
    val load: Double,
    val loadSource: LoadSource,
    val strain: Double,
)

/**
 * [effort] is e.g. "5000m" or "power_1200s". [valueSec] is seconds for the distance efforts, but WATTS (higher is better)
 * for the "power_*" ones.
 */
@Entity(tableName = "best_effort", primaryKeys = ["sport", "effort"])
data class BestEffortEntity(
    val sport: SportFamily,
    val effort: String,
    val valueSec: Double,
    val activityId: Long,
    val achievedOn: LocalDate,
)
