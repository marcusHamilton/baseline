package dev.baseline.sync

import androidx.room3.withWriteTransaction
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.PARSE_ERROR_PREFIX
import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.TaskCounts
import dev.baseline.data.db.TaskStatus
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminEndpoint.*
import dev.baseline.garmin.GarminResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

/** PARTIAL: the time budget ran out with tasks still PENDING; the work continues in a follow-up run. */
enum class SyncOutcome { COMPLETED, PARTIAL, RATE_LIMITED, KEY_INVALID, OFFLINE }

/** How long one background run may work before it hands over to a follow-up (WorkManager stops a run after ~10 minutes). */
val RUN_BUDGET: Duration = 8.minutes

/** [changedFrom] is the earliest day whose typed rows were written or deleted during the run, null if none. */
data class SyncRunResult(val outcome: SyncOutcome, val changedFrom: LocalDate?)

/**
 * [fetch] is `GarminClient::fetch` (its String is the endpoint key, not the API key).
 * Orchestration only: pacing, retries and task statuses. Per-endpoint writes live in [RawIngest].
 */
class SyncEngine(
    private val fetch: suspend (GarminEndpoint, String) -> GarminResult,
    private val db: AppDatabase,
    private val clock: Clock,
    private val ingest: RawIngest = RawIngest(db, clock = clock),
    private val pause: suspend (Long) -> Unit,
) {
    /** First run only: the plan is inserted when the task table is empty, so later runs keep their statuses. */
    suspend fun ensureBackfillPlanned(today: LocalDate) {
        if (db.taskDao().count() == 0) db.taskDao().insertIgnore(BackfillPlanner.plan(HISTORY_START, today))
    }

    /** Failed placeholder nights from before they were EMPTY. */
    suspend fun repairSleepPlaceholders() = db.taskDao().repairSleepPlaceholders()

    /** Spec §5.2: re-fetch the last 7 days of range data, today and yesterday per day, and the last week's activities. */
    suspend fun planIncremental(today: LocalDate) {
        val window = "${today.minusDays(6)}..$today"
        val ranges = listOf(DAILY_SUMMARIES, HRV_RANGE, SLEEP_SUMMARIES, BODY_BATTERY, STRESS, STEPS, ACTIVITY_RANGE)
        val perDay = listOf(SLEEP_DAY, HEART_RATE_DAY, HRV_DAY).flatMap { e -> listOf(today, today.minusDays(1)).map { e to "$it" } }
        // An unreadable window from an earlier day overlaps this one but has another key, so it would never be retried.
        // Once per day (while today's window is not planned yet): a persistent parse failure must not cost requests every run.
        if (!db.taskDao().exists(ranges.first().name, window)) {
            db.taskDao().requeueFailedOverlapping(ranges.map { it.name }, "${today.minusDays(6)}", "$today")
        }
        db.taskDao().upsertAll(
            (ranges.map { it to window } + perDay).map { (e, k) -> SyncTaskEntity(e.name, k, 0, TaskStatus.PENDING, 0, null) },
        )
    }

    /**
     * Works through PENDING tasks until none are left or something stops the run. [onProgress] fires after each task.
     * Once [budget] (injected clock) is used up, the run returns PARTIAL if tasks remain: WorkManager kills a long background run.
     */
    suspend fun runPending(budget: Duration = RUN_BUDGET, onProgress: (TaskCounts) -> Unit): SyncRunResult {
        val tasks = db.taskDao()
        val started = clock.instant()
        val limit = budget.toJavaDuration()
        var changed: LocalDate? = null
        var requests = 0
        var rateLimited = 0 // consecutive 429/5xx; the retry right after a back-off needs no extra spacer
        fun stop(o: SyncOutcome) = SyncRunResult(o, changed)

        // Ingests [body] for [t], then sets [status]. Returns OFFLINE if the write hit an IOException (task stays PENDING).
        suspend fun ingestTask(t: SyncTaskEntity, endpoint: GarminEndpoint, body: ByteArray, status: TaskStatus): SyncOutcome? {
            try {
                val r = ingest.apply(endpoint, t.key, body)
                changed = earliest(changed, r.changedFrom)
                tasks.update(t.copy(status = if (r.empty) TaskStatus.EMPTY else status, lastError = null))
            } catch (e: IOException) {
                return SyncOutcome.OFFLINE
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) { // includes SerializationException
                fail(t, PARSE_ERROR_PREFIX + (e.message?.take(200) ?: "invalid data")) // raw row stays; spec §10 "Malformed JSON"
            } catch (e: IllegalStateException) {
                fail(t, PARSE_ERROR_PREFIX + (e.message?.take(200) ?: "invalid data"))
            } // anything else (e.g. SQLite) propagates: the worker run fails and the next scheduled run resumes the PENDING tasks
            return null
        }
        while (true) {
            val t = tasks.nextPending() ?: return stop(SyncOutcome.COMPLETED)
            if (JavaDuration.between(started, clock.instant()) >= limit) return stop(SyncOutcome.PARTIAL)
            val endpoint = runCatching { GarminEndpoint.valueOf(t.endpoint) }.getOrNull()
            if (endpoint == null) {
                fail(t, "unknown endpoint")
                onProgress(tasks.counts().first())
                continue
            }
            // No spacer right after a back-off: the back-off itself is >= 60 s.
            if (requests++ > 0 && rateLimited == 0) pause(PAUSE_MS)
            val r = try {
                fetch(endpoint, t.key)
            } catch (_: IOException) {
                return stop(SyncOutcome.OFFLINE)
            }
            if (r !is GarminResult.RateLimited) rateLimited = 0
            when (r) {
                is GarminResult.RateLimited -> {
                    if (rateLimited == BACKOFF_MS.size) return stop(SyncOutcome.RATE_LIMITED)
                    // A back-off that would outlast the budget could get the run killed before afterSync: stop now, the task stays PENDING.
                    if (JavaDuration.between(started, clock.instant()).toMillis() + BACKOFF_MS[rateLimited] >=
                        limit.toMillis()
                    ) {
                        return stop(SyncOutcome.RATE_LIMITED)
                    }
                    pause(BACKOFF_MS[rateLimited++])
                    continue
                }

                GarminResult.KeyInvalid -> return stop(SyncOutcome.KEY_INVALID)

                // An empty activity window still means "nothing there", so local activities in it are deleted.
                GarminResult.Empty ->
                    if (endpoint == ACTIVITY_RANGE) {
                        ingestTask(t, endpoint, "[]".toByteArray(), TaskStatus.EMPTY)?.let { return stop(it) }
                    } else {
                        tasks.update(t.copy(status = TaskStatus.EMPTY))
                    }

                is GarminResult.BadRequest -> onBadRequest(t)

                is GarminResult.Failed -> fail(t, listOfNotNull(r.code, r.message).joinToString(" "))

                is GarminResult.Ok -> ingestTask(t, endpoint, r.body, TaskStatus.DONE)?.let { return stop(it) }
            }
            onProgress(tasks.counts().first())
        }
    }

    private suspend fun fail(t: SyncTaskEntity, error: String) = db.taskDao().update(t.copy(status = TaskStatus.FAILED, lastError = error))

    /** A too-wide range is replaced by its two halves once; anything else that gets a 400 is a bug and fails. */
    private suspend fun onBadRequest(t: SyncTaskEntity) {
        val ends = t.key.split("..").takeIf { it.size == 2 }?.map(LocalDate::parse)
        val days = ends?.let { ChronoUnit.DAYS.between(it[0], it[1]) + 1 } ?: 0
        if (ends == null || t.attempts >= 1 || days < 2) return fail(t, "400")
        val mid = ends[0].plusDays(days / 2 - 1)
        fun half(a: LocalDate, b: LocalDate) = t.copy(key = "$a..$b", status = TaskStatus.PENDING, attempts = 1, lastError = null)
        db.withWriteTransaction {
            db.taskDao().delete(t.endpoint, t.key)
            db.taskDao().insertIgnore(listOf(half(ends[0], mid), half(mid.plusDays(1), ends[1])))
        }
    }

    private companion object {
        const val PAUSE_MS = 1000L
        val BACKOFF_MS = listOf(60_000L, 120_000L, 240_000L)
    }
}
