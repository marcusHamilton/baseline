package dev.baseline.sync

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.TaskStatus
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminResult
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
    private lateinit var db: AppDatabase
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.parse("2026-10-24T08:00:00Z"), ZoneOffset.UTC)
    private val afterSyncCalls = mutableListOf<LocalDate?>()
    private var reschedules = 0
    private var continues = 0
    private val events = mutableListOf<String>()
    private val synced = mutableListOf<Instant>()
    private var failMarkSynced = false

    @Before
    fun setUp() {
        db = inMemoryDatabase(context)
        // A non-empty task table keeps ensureBackfillPlanned from queueing the ~1,000-task plan.
        runBlocking {
            db.taskDao().insertIgnore(
                listOf(SyncTaskEntity(GarminEndpoint.STEPS.name, "2026-10-17..2026-10-23", 4, TaskStatus.PENDING, 0, null)),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun run(
        updater: ForegroundUpdater? = null,
        clk: Clock = clock,
        tags: List<String> = emptyList(),
        fetch: suspend (GarminEndpoint, String) -> GarminResult,
    ): Result = runBlocking {
        val engine = SyncEngine(fetch, db, clk) {}
        val deps =
            SyncWorkerDeps(
                engine,
                clk,
                {
                    afterSyncCalls += it
                    events += "afterSync:$it"
                },
                {
                    if (failMarkSynced) throw IllegalStateException("disk full")
                    synced += it
                    events += "markSynced"
                },
                {
                    reschedules++
                    events += "reschedule"
                },
                {
                    continues++
                    events += "continueSoon:$it"
                },
            )
        val factory =
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    SyncWorker(appContext, workerParameters, deps)
            }
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(factory)
            .setTags(tags)
            .apply { if (updater != null) setForegroundUpdater(updater) }
            .build()
            .doWork()
    }

    private val steps =
        GarminResult.Ok(
            """[{"calendarDate":"2026-10-21","totalSteps":1},{"calendarDate":"2026-10-19","totalSteps":2}]""".toByteArray(),
            null,
        )

    @Test
    fun completedIsSuccessAndAfterSyncGetsChangedFrom() {
        val r = run { e, _ -> if (e == GarminEndpoint.STEPS) steps else GarminResult.Empty }
        assertEquals(Result.success(), r)
        assertEquals(listOf<LocalDate?>(LocalDate.of(2026, 10, 19)), afterSyncCalls)
        assertEquals(1, reschedules)
        assertEquals(0, continues)
        assertEquals(listOf(clock.instant()), synced)
    }

    /** A clock the test moves by hand. */
    private class StepClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant() = now
    }

    // Every per-day endpoint gets a body all three parsers accept.
    private val day =
        GarminResult.Ok(
            """{"calendarDate":"2026-10-24","sleepTimeSeconds":28000,"heartRateValues":[],"hrvSummary":{"calendarDate":"2026-10-24"}}""".toByteArray(),
            null,
        )

    /** Each request costs 9 minutes, so the 8-minute budget ends the run after one task. */
    private fun runPartial(tags: List<String> = emptyList()): Result {
        val clk = StepClock(clock.instant())
        return run(clk = clk, tags = tags) { e, _ ->
            clk.now = clk.now.plusSeconds(540)
            if (e == GarminEndpoint.SLEEP_DAY || e == GarminEndpoint.HEART_RATE_DAY || e == GarminEndpoint.HRV_DAY) day else GarminResult.Empty
        }
    }

    @Test
    fun partialRunScoresAndContinues() {
        assertEquals(Result.success(), runPartial())
        assertEquals(listOf("afterSync:2026-10-24", "markSynced", "continueSoon:false"), events)
        assertEquals(0, reschedules)
    }

    @Test
    fun partialRunOfAUserSyncContinuesAsUser() {
        assertEquals(Result.success(), runPartial(tags = listOf("user")))
        assertEquals(listOf("afterSync:2026-10-24", "markSynced", "continueSoon:true"), events)
    }

    @Test
    fun completedRunEndsTheChain() {
        run { _, _ -> GarminResult.Empty }
        assertEquals(listOf("afterSync:null", "markSynced", "reschedule"), events)
    }

    @Test
    fun timeWaitingForTheLockIsSpentFromTheBudget() {
        // The first clock read (before the lock) is 9 minutes older than every later one, as if another run held the lock that long.
        val t0 = clock.instant()
        val clk =
            object : Clock() {
                var reads = 0

                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId?): Clock = this

                override fun instant(): Instant = if (reads++ == 0) t0 else t0.plusSeconds(540)
            }
        var fetches = 0
        val r =
            run(clk = clk) { _, _ ->
                fetches++
                GarminResult.Empty
            }
        assertEquals(Result.success(), r)
        assertEquals(0, fetches) // a zero budget stops before the first request
        assertEquals(1, continues)
    }

    @Test
    fun runRepairsFailedSleepPlaceholders() {
        runBlocking {
            db.taskDao().insertIgnore(listOf(SyncTaskEntity("SLEEP_DAY", "2026-10-05", 6, TaskStatus.FAILED, 0, "missing calendarDate")))
        }
        run { _, _ -> GarminResult.Empty }
        assertEquals(TaskStatus.EMPTY, runBlocking { db.taskDao().all() }.single { it.endpoint == "SLEEP_DAY" && it.key == "2026-10-05" }.status)
    }

    /** Seeds [n] more PENDING tasks so more than 20 remain after the first one runs. */
    private fun seed(n: Int) = runBlocking {
        db.taskDao().insertIgnore(
            (1..n).map {
                SyncTaskEntity(
                    GarminEndpoint.STEPS.name,
                    "2026-01-%02d..2026-01-%02d".format(it % 28 + 1, it % 28 + 1) + "-$it",
                    4,
                    TaskStatus.PENDING,
                    0,
                    null,
                )
            },
        )
    }

    private class FakeUpdater(val refuse: Boolean) : ForegroundUpdater {
        val infos = mutableListOf<ForegroundInfo>()

        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void?> {
            infos += foregroundInfo
            return CallbackToFutureAdapter.getFuture { c ->
                if (refuse) c.setException(IllegalStateException("not allowed")) else c.set(null)
                "fake"
            }
        }
    }

    @Test
    fun aRefusedForegroundIsNotRetried() {
        seed(30)
        val updater = FakeUpdater(refuse = true)
        val r =
            run(updater) { _, _ ->
                yield()
                GarminResult.Empty
            }
        assertEquals(Result.success(), r)
        assertEquals(1, updater.infos.size)
    }

    @Test
    fun longBackfillGoesForegroundAsDataSync() {
        seed(30)
        val updater = FakeUpdater(refuse = false)
        run(updater) { _, _ ->
            yield()
            GarminResult.Empty
        }
        val info = updater.infos.first()
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertTrue(
            info.notification.extras
                .getCharSequence("android.text")
                .toString()
                .startsWith("Syncing history "),
        )
    }

    @Test
    fun shortRunStaysInTheBackground() {
        val updater = FakeUpdater(refuse = false)
        run(updater) { _, _ ->
            yield()
            GarminResult.Empty
        } // 1 seeded + 13 incremental, never above 20 remaining
        assertEquals(0, updater.infos.size)
    }

    @Test
    fun nothingChangedPassesNullToAfterSync() {
        assertEquals(Result.success(), run { _, _ -> GarminResult.Empty })
        assertEquals(1, afterSyncCalls.size)
        assertNull(afterSyncCalls.single())
    }

    @Test
    fun rateLimitedRetries() {
        assertEquals(Result.retry(), run { _, _ -> GarminResult.RateLimited(429) })
        assertEquals(0, reschedules)
        assertTrue("a retry is not a completed sync", synced.isEmpty())
    }

    @Test
    fun aFailingMarkSyncedDoesNotFailACompletedRun() {
        failMarkSynced = true
        assertEquals(Result.success(), run { _, _ -> GarminResult.Empty })
        assertEquals(1, reschedules)
    }

    @Test
    fun offlineRetries() {
        assertEquals(Result.retry(), run { _, _ -> throw java.io.IOException("offline") })
    }

    @Test
    fun keyInvalidIsSuccessSoItDoesNotRetryForever() {
        assertEquals(Result.success(), run { _, _ -> GarminResult.KeyInvalid })
        assertTrue("an invalid key did not sync anything", synced.isEmpty())
    }
}
