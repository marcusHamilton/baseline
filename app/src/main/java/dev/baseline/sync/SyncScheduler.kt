package dev.baseline.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.baseline.prefs.SyncInterval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * [backfillPending] is `TaskDao.backfillPending()`: no plan yet, or first-run tasks (priority 1..6) still PENDING.
 * Open so tests can count calls.
 */
open class SyncScheduler(private val workManager: WorkManager, private val backfillPending: suspend () -> Boolean) {
    /** Unmetered only while the first big download is unfinished; afterwards the small hourly top-ups may use mobile data. */
    open suspend fun apply(interval: SyncInterval) {
        if (interval == SyncInterval.MANUAL) {
            workManager.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(if (interval == SyncInterval.HOURLY) 1 else 3, TimeUnit.HOURS)
            .setConstraints(network(networkForBackfill()))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * The user's explicit "Sync now" (pull-to-refresh, Settings): runs on any connection. It takes over a request that is
     * still waiting (e.g. a [syncSoon] held for Wi-Fi), but never interrupts a running one: that run covers the pull.
     * A [continueSoon] chain waiting for Wi-Fi is cancelled (the user's own chain, [CONTINUE_USER], covers that work),
     * unless one of its links is running: that one is left to finish, serialised by the worker's mutex.
     */
    open suspend fun syncNow() {
        if (workManager.getWorkInfosForUniqueWorkFlow(CONTINUE).first().none { it.state == WorkInfo.State.RUNNING }) {
            workManager.cancelUniqueWork(CONTINUE)
        }
        val waiting = workManager.getWorkInfosForUniqueWorkFlow(SYNC_NOW).first()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        enqueueNow(NetworkType.CONNECTED, if (waiting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, user = true)
    }

    /** Today's automatic first sync: respects the spec §5.1 rule of no first-run backfill over mobile data. */
    open suspend fun syncSoon() = enqueueNow(networkForBackfill(), ExistingWorkPolicy.KEEP)

    /**
     * The next slice of a backfill that ran out of time. APPEND_OR_REPLACE, not KEEP: a follow-up asked for by the
     * running "sync-continue" must queue behind it rather than be dropped. Runs are serialised by the worker's mutex.
     * [user]: the run being continued was an explicit "Sync now", so the rest of the backfill runs on any connection
     * (spec §5.1). It has its own chain, [CONTINUE_USER], so it never queues behind an automatic link that waits for Wi-Fi.
     */
    open suspend fun continueSoon(user: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network(if (user) NetworkType.CONNECTED else networkForBackfill()))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .apply { if (user) addTag(USER) }
            .build()
        workManager.enqueueUniqueWork(if (user) CONTINUE_USER else CONTINUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Work for all unique names; sync-now runs carry the [SYNC_NOW] tag. */
    val workInfos: Flow<List<WorkInfo>> = combine(
        listOf(SYNC_NOW, PERIODIC, CONTINUE, CONTINUE_USER).map { workManager.getWorkInfosForUniqueWorkFlow(it) },
    ) { it.toList().flatten() }

    private suspend fun networkForBackfill() = if (backfillPending()) NetworkType.UNMETERED else NetworkType.CONNECTED

    private fun enqueueNow(type: NetworkType, policy: ExistingWorkPolicy, user: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network(type))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(SYNC_NOW)
            .apply { if (user) addTag(USER) }
            .build()
        workManager.enqueueUniqueWork(SYNC_NOW, policy, request)
    }

    private fun network(type: NetworkType) = Constraints.Builder().setRequiredNetworkType(type).build()

    companion object {
        const val PERIODIC = "sync-periodic"
        const val SYNC_NOW = "sync-now"
        const val CONTINUE = "sync-continue"
        const val CONTINUE_USER = "sync-continue-user"
        /** Marks work started by the user's "Sync now" and its continuations. */
        const val USER = "user"
        private const val BACKOFF_SECONDS = 60L
    }
}
