package dev.baseline.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.baseline.BaselineApp
import dev.baseline.data.db.TaskCounts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import kotlin.time.Duration as KDuration
import kotlin.time.toKotlinDuration
import java.time.Instant
import java.text.NumberFormat
import java.time.LocalDate

/** What a run needs. [afterSync] gets the earliest changed day (Task 14 recomputes scores from it); [reschedule] re-applies the interval. */
class SyncWorkerDeps(
    val engine: SyncEngine,
    val clock: Clock,
    val afterSync: suspend (LocalDate?) -> Unit,
    /** Called with the finish time when a run COMPLETES (Today's "Synced …"). */
    val markSynced: suspend (Instant) -> Unit,
    val reschedule: suspend () -> Unit,
    /** Queues the follow-up run after a PARTIAL one; the argument is whether the finished run was user-initiated. */
    val continueSoon: suspend (Boolean) -> Unit,
)

class SyncWorker(
    ctx: Context,
    params: WorkerParameters,
    private val deps: SyncWorkerDeps,
) : CoroutineWorker(ctx, params) {
    /** WorkManager's reflective constructor; tests use the other one with a WorkerFactory. */
    constructor(ctx: Context, params: WorkerParameters) :
        this(ctx, params, (ctx.applicationContext as BaselineApp).container.syncWorkerDeps)

    override suspend fun doWork(): Result {
        val queuedAt = deps.clock.instant()
        val outcome = syncMutex.withLock {
            // Time spent waiting for another run counts against WorkManager's limit too.
            val waited = Duration.between(queuedAt, deps.clock.instant()).toKotlinDuration()
            val today = LocalDate.now(deps.clock)
            deps.engine.repairSleepPlaceholders()
            deps.engine.ensureBackfillPlanned(today)
            deps.engine.planIncremental(today)
            // onProgress is not suspending, so progress goes through a conflated channel; the delay throttles setForeground.
            val progress = Channel<TaskCounts>(Channel.CONFLATED)
            val run = coroutineScope {
                val notifier = launch {
                    for (c in progress) {
                        // One refusal (Android 12+ background start) ends the notifications; the sync itself carries on.
                        if (c.total - c.done > FOREGROUND_THRESHOLD && !showProgress(c)) break
                        delay(NOTIFY_EVERY_MS)
                    }
                }
                try {
                    deps.engine.runPending((RUN_BUDGET - waited).coerceAtLeast(KDuration.ZERO)) { progress.trySend(it) }
                } finally {
                    notifier.cancel()
                }
            }
            deps.afterSync(run.changedFrom)
            run.outcome
        }
        return when (outcome) {
            SyncOutcome.COMPLETED, SyncOutcome.KEY_INVALID -> {
                if (outcome == SyncOutcome.COMPLETED) markSynced()
                deps.reschedule() // backfill may just have finished: UNMETERED -> CONNECTED
                Result.success()
            }
            SyncOutcome.PARTIAL -> {
                markSynced()
                deps.continueSoon(SyncScheduler.USER in tags)
                Result.success()
            }
            SyncOutcome.RATE_LIMITED, SyncOutcome.OFFLINE -> Result.retry()
        }
    }

    /** The "Synced" stamp is cosmetic: a failed write is logged and must not fail a run that did complete. */
    private suspend fun markSynced() {
        try {
            deps.markSynced(Instant.now(deps.clock))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not record the sync time: ${e.javaClass.name}: ${e.message}")
        }
    }

    /** Expedited work on API < 31 asks for this before doWork starts. */
    override suspend fun getForegroundInfo() = foregroundInfo("Syncing…", null)

    private suspend fun showProgress(c: TaskCounts): Boolean {
        val n = NumberFormat.getIntegerInstance()
        return try {
            setForeground(foregroundInfo("Syncing history ${n.format(c.done)} / ${n.format(c.total)}", c))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun foregroundInfo(text: String, c: TaskCounts?): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(c?.total ?: 0, c?.done ?: 0, c == null)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 1
        private const val FOREGROUND_THRESHOLD = 20
        private const val NOTIFY_EVERY_MS = 1000L

        /** Process-wide: sync-now and periodic runs must never drive the engine (and Garmin) at once. */
        private val syncMutex = Mutex()

        /** Called once from Application.onCreate; the channel must exist before the first foreground notification. */
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Sync", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
