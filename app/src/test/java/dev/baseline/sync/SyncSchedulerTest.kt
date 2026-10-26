package dev.baseline.sync

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.TaskStatus
import dev.baseline.prefs.SyncInterval
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {
    private lateinit var wm: WorkManager
    private lateinit var db: AppDatabase

    /** Seeds one task per (priority, status); the scheduler asks the real query whether first-run work is pending. */
    private fun seed(vararg tasks: Pair<Int, TaskStatus>) = runBlocking {
        db.taskDao().insertIgnore(
            tasks.mapIndexed { i, (priority, status) -> SyncTaskEntity("STEPS", "k$i", priority, status, 0, null) },
        )
    }

    private fun seedBackfillPending() = seed(4 to TaskStatus.PENDING)

    private fun seedBackfillDone() = seed(1 to TaskStatus.DONE, 6 to TaskStatus.DONE, 0 to TaskStatus.PENDING, 7 to TaskStatus.PENDING)

    @After
    fun tearDown() {
        db.close()
        closeWorkManagerDb(ApplicationProvider.getApplicationContext())
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        wm = WorkManager.getInstance(ApplicationProvider.getApplicationContext())
        db =
            Room
                .inMemoryDatabaseBuilder<AppDatabase>(ApplicationProvider.getApplicationContext()) { AppDatabase_Impl() }
                .allowMainThreadQueries()
                .build()
    }

    private fun scheduler() = SyncScheduler(wm) { db.taskDao().backfillPending() }

    private fun infos(name: String) = wm.getWorkInfosForUniqueWork(name).get()

    private fun network(name: String) = infos(name).single().constraints.requiredNetworkType

    @Test
    fun hourlySchedulesPeriodicOneHour() = runBlocking {
        seedBackfillDone()
        scheduler().apply(SyncInterval.HOURLY)
        val w = infos("sync-periodic").single()
        assertEquals(TimeUnit.HOURS.toMillis(1), w.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, w.constraints.requiredNetworkType)

        scheduler().apply(SyncInterval.THREE_HOURS) // UPDATE replaces the spec in place
        assertEquals(TimeUnit.HOURS.toMillis(3), infos("sync-periodic").single().periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun manualCancelsPeriodic() = runBlocking {
        scheduler().apply(SyncInterval.HOURLY)
        val before = infos("sync-periodic").single()
        assertTrue(before.state == WorkInfo.State.ENQUEUED)

        scheduler().apply(SyncInterval.MANUAL)
        val after = infos("sync-periodic").single()
        assertEquals(before.id, after.id)
        assertEquals(WorkInfo.State.CANCELLED, after.state)
    }

    @Test
    fun syncNowUsesConnectedNotUnmetered() = runBlocking {
        seedBackfillPending() // an explicit Sync now must not wait for Wi-Fi
        scheduler().syncNow()
        assertEquals(NetworkType.CONNECTED, network("sync-now"))
    }

    @Test
    fun repeatedSyncNowLeavesOneActiveRequest() = runBlocking {
        scheduler().syncNow()
        scheduler().syncNow()
        assertEquals(listOf(WorkInfo.State.ENQUEUED), infos("sync-now").filter { !it.state.isFinished }.map { it.state })
    }

    @Test
    fun syncSoonKeepsTheRequestAlreadyEnqueued() = runBlocking {
        scheduler().syncSoon()
        val first = infos("sync-now").single()
        scheduler().syncSoon()
        val second = infos("sync-now").single()
        assertEquals(WorkInfo.State.ENQUEUED, second.state)
        assertEquals(first.id, second.id)
    }

    @Test
    fun syncNowReplacesWaitingSyncSoon() = runBlocking {
        seedBackfillPending()
        scheduler().syncSoon()
        val soon = infos("sync-now").single()
        assertEquals(NetworkType.UNMETERED, soon.constraints.requiredNetworkType)

        scheduler().syncNow()
        val active = infos("sync-now").filter { !it.state.isFinished }
        assertEquals(1, active.size)
        assertEquals(NetworkType.CONNECTED, active.single().constraints.requiredNetworkType)
        assertNotEquals(soon.id, active.single().id)
    }

    @Test
    fun backfillPendingUsesUnmetered() = runBlocking {
        seedBackfillPending()
        scheduler().apply(SyncInterval.HOURLY)
        assertEquals(NetworkType.UNMETERED, network("sync-periodic"))
    }

    @Test
    fun applyOnEmptyTableUsesUnmetered() = runBlocking {
        // First run: the worker hasn't planned the backfill yet, so "nothing pending" would be a false all-clear.
        scheduler().apply(SyncInterval.HOURLY)
        assertEquals(NetworkType.UNMETERED, network("sync-periodic"))
    }

    @Test
    fun applyAfterBackfillUsesConnected() = runBlocking {
        seedBackfillDone() // incremental (0) and FIT (7) tasks still PENDING don't count
        scheduler().apply(SyncInterval.HOURLY)
        assertEquals(NetworkType.CONNECTED, network("sync-periodic"))
    }

    @Test
    fun syncSoonUsesUnmeteredDuringBackfill() = runBlocking {
        seedBackfillPending()
        scheduler().syncSoon()
        assertEquals(NetworkType.UNMETERED, network("sync-now"))
    }

    @Test
    fun syncSoonUsesUnmeteredBeforeAnythingIsPlanned() = runBlocking {
        scheduler().syncSoon()
        assertEquals(NetworkType.UNMETERED, network("sync-now"))
    }

    @Test
    fun syncSoonUsesConnectedAfterBackfill() = runBlocking {
        seedBackfillDone()
        scheduler().syncSoon()
        assertEquals(NetworkType.CONNECTED, network("sync-now"))
    }

    @Test
    fun continueSoonUsesBackfillConstraint() = runBlocking {
        seedBackfillPending()
        scheduler().continueSoon()
        assertEquals(NetworkType.UNMETERED, network("sync-continue"))
    }

    @Test
    fun continueSoonUsesConnectedAfterBackfill() = runBlocking {
        seedBackfillDone()
        scheduler().continueSoon()
        assertEquals(NetworkType.CONNECTED, network("sync-continue"))
    }

    @Test
    fun continueChainSurvivesWhenRunningWorkIsSyncContinue() = runBlocking {
        // KEEP would drop the follow-up that a running "sync-continue" asks for; APPEND_OR_REPLACE queues it behind.
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        useBlockingWorker(started, release)
        seedBackfillPending()
        scheduler().continueSoon()
        val first = infos("sync-continue").single().id
        WorkManagerTestInitHelper.getTestDriver(ApplicationProvider.getApplicationContext())!!.setAllConstraintsMet(first)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.RUNNING, infos("sync-continue").single().state)

        scheduler().continueSoon() // what the running work itself does when it is PARTIAL
        val all = infos("sync-continue")
        release.countDown()
        awaitFinished("sync-continue", first)
        assertEquals(2, all.size)
        assertEquals(WorkInfo.State.RUNNING, all.single { it.id == first }.state)
        assertEquals(WorkInfo.State.BLOCKED, all.single { it.id != first }.state)
    }

    @Test
    fun continuationOfAUserSyncIsConnectedAndTagged() = runBlocking {
        seedBackfillPending()
        scheduler().continueSoon(user = true)
        val w = infos("sync-continue-user").single()
        assertEquals(NetworkType.CONNECTED, w.constraints.requiredNetworkType)
        assertTrue("user" in w.tags)
    }

    @Test
    fun continuationOfAnAutomaticRunDuringBackfillIsUnmeteredAndUntagged() = runBlocking {
        seedBackfillPending()
        scheduler().continueSoon(user = false)
        val w = infos("sync-continue").single()
        assertEquals(NetworkType.UNMETERED, w.constraints.requiredNetworkType)
        assertTrue("user" !in w.tags)
    }

    @Test
    fun userContinuationDoesNotQueueBehindAWifiContinuation() = runBlocking {
        seedBackfillPending()
        scheduler().continueSoon(user = false) // waits for Wi-Fi
        scheduler().continueSoon(user = true)
        val user = infos("sync-continue-user").single()
        assertEquals(WorkInfo.State.ENQUEUED, user.state)
        assertEquals(NetworkType.CONNECTED, user.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, infos("sync-continue").single().state)
    }

    @Test
    fun syncNowCancelsWaitingWifiContinuation() = runBlocking {
        seedBackfillPending()
        scheduler().continueSoon(user = false)
        assertEquals(WorkInfo.State.ENQUEUED, infos("sync-continue").single().state)
        scheduler().syncNow()
        assertEquals(WorkInfo.State.CANCELLED, infos("sync-continue").single().state)
    }

    @Test
    fun syncNowLeavesRunningContinuation() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        useBlockingWorker(started, release)
        seedBackfillPending()
        scheduler().continueSoon(user = false)
        val id = infos("sync-continue").single().id
        WorkManagerTestInitHelper.getTestDriver(ApplicationProvider.getApplicationContext())!!.setAllConstraintsMet(id)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        scheduler().syncNow()
        val after = infos("sync-continue").single()
        release.countDown()
        awaitFinished("sync-continue", id)
        assertEquals(WorkInfo.State.RUNNING, after.state)
    }

    @Test
    fun workInfosIncludesTheUserChainAndFeedsTheStatusLine() = runBlocking {
        seedBackfillPending()
        scheduler().continueSoon(user = false)
        val key =
            dev.baseline.garmin.KeyStatus
                .Ok(java.time.LocalDate.of(2027, 1, 1), 90)
        val counts =
            dev.baseline.data.db
                .TaskCounts(1, 10)
        val waiting = scheduler().workInfos.first { it.size >= 1 }
        assertEquals(
            "Waiting for Wi-Fi",
            dev.baseline.ui.today
                .syncStatus(waiting, counts, true, key),
        )

        scheduler().continueSoon(user = true)
        val all = kotlinx.coroutines.withTimeout(5_000) { scheduler().workInfos.first { it.size >= 2 } }
        val user = all.single { "user" in it.tags }
        assertEquals(infos("sync-continue-user").single().id, user.id)
        assertEquals(infos("sync-continue").single().id, all.single { "user" !in it.tags }.id)
        // The user's any-network request is queued, so the line must not claim to be waiting for Wi-Fi.
        assertNull(
            dev.baseline.ui.today
                .syncStatus(all, counts, true, key),
        )
    }

    @Test
    fun syncNowIsTaggedUserButSyncSoonIsNot() = runBlocking {
        scheduler().syncSoon()
        assertTrue("user" !in infos("sync-now").single().tags)
        scheduler().syncNow() // replaces the waiting syncSoon
        assertTrue("user" in infos("sync-now").single { !it.state.isFinished }.tags)
    }

    /** A real thread pool plus a worker that blocks lets the test driver reach RUNNING. */
    private fun useBlockingWorker(started: CountDownLatch, release: CountDownLatch) {
        val ctx: android.content.Context = ApplicationProvider.getApplicationContext()
        closeWorkManagerDb(ctx)
        val config =
            Configuration
                .Builder()
                .setExecutor(Executors.newFixedThreadPool(2))
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(appContext: android.content.Context, workerClassName: String, workerParameters: WorkerParameters) =
                            object : Worker(appContext, workerParameters) {
                                override fun doWork(): Result {
                                    started.countDown()
                                    release.await(10, TimeUnit.SECONDS)
                                    return Result.success()
                                }
                            }
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
        wm = WorkManager.getInstance(ctx)
    }

    /** Lets the worker finish before tearDown closes the database under it. */
    private fun awaitFinished(name: String, id: java.util.UUID) {
        // Only the item under test: a chained child waits for constraints the test never meets.
        val deadline = System.currentTimeMillis() + 5_000
        while (!infos(name).single { it.id == id }.state.isFinished && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun syncNowKeepsRunningSync() = runBlocking {
        // A real thread pool plus a worker that blocks lets the test driver reach RUNNING.
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ctx: android.content.Context = ApplicationProvider.getApplicationContext()
        closeWorkManagerDb(ctx)
        val config =
            Configuration
                .Builder()
                .setExecutor(Executors.newFixedThreadPool(2))
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(appContext: android.content.Context, workerClassName: String, workerParameters: WorkerParameters) =
                            object : Worker(appContext, workerParameters) {
                                override fun doWork(): Result {
                                    started.countDown()
                                    release.await(10, TimeUnit.SECONDS)
                                    return Result.success()
                                }
                            }
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
        wm = WorkManager.getInstance(ctx)

        seedBackfillPending()
        scheduler().syncSoon()
        val id = infos("sync-now").single().id
        WorkManagerTestInitHelper.getTestDriver(ctx)!!.setAllConstraintsMet(id)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.RUNNING, infos("sync-now").single().state)

        scheduler().syncNow()
        val after = infos("sync-now").single()
        release.countDown()
        // Let the worker finish before tearDown closes the database under it.
        val deadline = System.currentTimeMillis() + 5_000
        while (!infos("sync-now").single().state.isFinished && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(id, after.id)
        assertEquals(WorkInfo.State.RUNNING, after.state)
    }
}
