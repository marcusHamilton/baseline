package dev.baseline.data.db

import dev.baseline.data.SportFamily
import dev.baseline.scores.HrPoint
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class RawAndTaskDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun upsertRawTwiceKeepsOneRow() = runBlocking {
        val dao = db.rawDao()
        dao.upsert(RawResponse("sleep", "2026-10-23", "a".toByteArray(), Instant.ofEpochSecond(1)))
        dao.upsert(RawResponse("sleep", "2026-10-23", "b".toByteArray(), Instant.ofEpochSecond(2)))
        assertEquals(listOf("2026-10-23"), dao.keys("sleep"))
        val row = dao.get("sleep", "2026-10-23")
        assertNotNull(row)
        assertEquals("b", String(row!!.json))
        assertEquals(Instant.ofEpochSecond(2), row.fetchedAt)
        assertNull(dao.get("sleep", "missing"))
    }

    @Test
    fun unreadableDayCoversKeysAndRangesButNotHttpFailures() = runBlocking {
        val dao = db.taskDao()

        fun failed(endpoint: String, key: String, error: String) = SyncTaskEntity(endpoint, key, 1, TaskStatus.FAILED, 1, error)
        dao.upsertAll(
            listOf(
                failed("SLEEP_DAY", "2026-10-05", PARSE_ERROR_PREFIX + "bad"),
                failed("DAILY_SUMMARIES", "2026-10-10..2026-10-20", PARSE_ERROR_PREFIX + "bad"),
                failed("STEPS", "2026-10-21..2026-10-27", "500 Server Error"),
                failed("FIT", "2026-10-25", PARSE_ERROR_PREFIX + "bad"),
            ),
        )

        fun unreadable(d: String) = runBlocking { dao.unreadableDay(d, DAY_ENDPOINTS).first() }
        assertEquals(true, unreadable("2026-10-05"))
        assertEquals(true, unreadable("2026-10-10"))
        assertEquals(true, unreadable("2026-10-20"))
        assertEquals(false, unreadable("2026-10-21")) // inside a range that only failed over HTTP
        assertEquals(false, unreadable("2026-10-06"))
        assertEquals(false, unreadable("2026-10-25")) // FIT is an activity file, not a day
    }

    @Test
    fun unreadableDayClearsOnceASuccessfulTaskOfTheSameEndpointCoversIt() = runBlocking {
        val dao = db.taskDao()

        fun task(endpoint: String, key: String, status: TaskStatus, error: String? = null) = SyncTaskEntity(endpoint, key, 1, status, 1, error)
        dao.upsertAll(
            listOf(
                task("STEPS", "2026-10-01..2026-10-07", TaskStatus.FAILED, PARSE_ERROR_PREFIX + "bad"),
                task("STEPS", "2026-10-05..2026-10-11", TaskStatus.DONE), // the incremental window re-fetched Oct 5..7
                task("STRESS", "2026-10-01..2026-10-07", TaskStatus.FAILED, PARSE_ERROR_PREFIX + "bad"),
                task("STRESS", "2026-10-03", TaskStatus.DONE),
                task("SLEEP_DAY", "2026-10-20", TaskStatus.FAILED, PARSE_ERROR_PREFIX + "bad"),
                task("HRV_DAY", "2026-10-20", TaskStatus.DONE), // another endpoint: does not vouch for SLEEP_DAY
            ),
        )

        fun unreadable(d: String, vararg endpoints: String) = runBlocking { dao.unreadableDay(d, endpoints.toList()).first() }
        assertEquals(true, unreadable("2026-10-02", "STEPS")) // no successful task covers it
        assertEquals(false, unreadable("2026-10-06", "STEPS")) // re-fetched by a DONE range
        assertEquals(true, unreadable("2026-10-06", "STRESS")) // its per-day DONE is another day
        assertEquals(false, unreadable("2026-10-03", "STRESS"))
        assertEquals(true, unreadable("2026-10-20", "SLEEP_DAY", "HRV_DAY")) // a DONE of another endpoint does not vouch
    }

    @Test
    fun anOlderSuccessfulWindowDoesNotHideANewerFailure() = runBlocking {
        val dao = db.taskDao()

        fun task(key: String, status: TaskStatus, error: String? = null) = SyncTaskEntity("STEPS", key, 1, status, 1, error)
        dao.upsertAll(
            listOf(
                task("2026-10-19..2026-10-25", TaskStatus.FAILED, PARSE_ERROR_PREFIX + "bad"), // today's window
                task("2026-10-18..2026-10-24", TaskStatus.DONE), // yesterday's: ends before the failed one
                task("2026-10-12..2026-10-18", TaskStatus.FAILED, PARSE_ERROR_PREFIX + "bad"),
                task("2026-10-13..2026-10-19", TaskStatus.DONE), // ends later: does fix Oct 13..18
            ),
        )
        assertEquals(true, dao.unreadableDay("2026-10-20", listOf("STEPS")).first())
        assertEquals(false, dao.unreadableDay("2026-10-15", listOf("STEPS")).first())
    }

    @Test
    fun unreadableDayCanBeLimitedToSleepEndpoints() = runBlocking {
        val dao = db.taskDao()
        dao.upsertAll(
            listOf(
                SyncTaskEntity("STEPS", "2026-10-01..2026-10-07", 1, TaskStatus.FAILED, 1, PARSE_ERROR_PREFIX + "bad"),
                SyncTaskEntity("SLEEP_DAY", "2026-10-09", 1, TaskStatus.FAILED, 1, PARSE_ERROR_PREFIX + "bad"),
            ),
        )
        assertEquals(true, dao.unreadableDay("2026-10-03", DAY_ENDPOINTS).first())
        assertEquals(false, dao.unreadableDay("2026-10-03", SLEEP_ENDPOINTS).first()) // steps do not feed the Sleep screen
        assertEquals(true, dao.unreadableDay("2026-10-09", SLEEP_ENDPOINTS).first())
    }

    @Test
    fun insertIgnoreDoesNotResetDoneTask() = runBlocking {
        val dao = db.taskDao()
        val pending = SyncTaskEntity("sleep", "2026-10-23", 1, TaskStatus.PENDING, 0, null)
        dao.insertIgnore(listOf(pending))
        dao.update(pending.copy(status = TaskStatus.DONE, attempts = 1))
        dao.insertIgnore(listOf(pending))
        assertNull(dao.nextPending())
        assertEquals(TaskCounts(done = 1, total = 1), dao.counts().first())
    }

    @Test
    fun nextPendingOrdersByPriorityThenNewestKey() = runBlocking {
        val dao = db.taskDao()
        dao.insertIgnore(
            listOf(
                SyncTaskEntity("sleep", "2026-10-22", 1, TaskStatus.PENDING, 0, null),
                SyncTaskEntity("sleep", "2026-10-23", 1, TaskStatus.PENDING, 0, null),
                SyncTaskEntity("sleep", "2026-10-24", 2, TaskStatus.PENDING, 0, null),
            ),
        )
        assertEquals("2026-10-23", dao.nextPending()!!.key)
    }

    @Test
    fun backfillPendingIsTrueForAnEmptyTableOrPendingPriorityOneToSix() = runBlocking {
        val dao = db.taskDao()

        fun t(key: String, priority: Int, status: TaskStatus) = SyncTaskEntity("STEPS", key, priority, status, 0, null)
        assertEquals(true, dao.backfillPending()) // nothing planned yet
        dao.insertIgnore(listOf(t("a", 0, TaskStatus.PENDING), t("b", 7, TaskStatus.PENDING), t("c", 6, TaskStatus.DONE)))
        assertEquals(false, dao.backfillPending()) // incremental, FIT and finished work don't count
        assertEquals(false, dao.backfillPendingFlow().first())
        dao.insertIgnore(listOf(t("d", 6, TaskStatus.PENDING)))
        assertEquals(true, dao.backfillPending())
        assertEquals(true, dao.backfillPendingFlow().first())
    }

    @Test
    fun hrPackingRoundTrips() {
        val start = 1_761_264_000L
        val samples = List(720) { i -> if (i == 100) null else HrPoint(start + i * 120L, 50 + i % 100) }
        val back = HrPacking.unpack(HrPacking.pack(samples))
        assertEquals(719, back.size)
        assertEquals(samples.filterNotNull(), back)
        assertEquals(emptyList<HrPoint>(), HrPacking.unpack(HrPacking.pack(emptyList())))
    }

    @Test
    fun deletingActivityCascadesToChildRows() = runBlocking {
        val day = LocalDate.of(2026, 10, 23)
        val start = LocalDateTime.of(2026, 10, 23, 7, 0)
        db.activityDao().upsert(
            ActivityEntity(
                7,
                "Run",
                "running",
                SportFamily.RUN,
                start,
                day,
                Instant.ofEpochSecond(1_761_202_800),
                3600.0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            ),
        )
        db.activityStreamDao().upsert(ActivityStreamEntity(7, byteArrayOf(1)))
        db.activityScoreDao().upsert(ActivityScoreEntity(7, 50.0, LoadSource.HR, 8.0))
        db.lapDao().insertAll(
            listOf(
                LapEntity(
                    activityId = 7,
                    lapIndex = 0,
                    startOffsetSec = null,
                    durationSec = 60.0,
                    distanceM = null,
                    avgHr = null,
                    maxHr = null,
                    avgSpeed = null,
                    avgPower = null,
                ),
            ),
        )
        assertEquals(1, db.lapDao().forActivity(7).size)

        // Re-sync upserts the same id: it must update in place, not delete-and-insert (which would cascade).
        db.activityDao().upsert(
            db
                .activityDao()
                .byDay(day)
                .single()
                .copy(name = "Renamed"),
        )
        assertEquals(
            "Renamed",
            db
                .activityDao()
                .byDay(day)
                .single()
                .name,
        )
        assertEquals(1, db.lapDao().forActivity(7).size)
        assertNotNull(db.activityStreamDao().get(7))
        assertNotNull(db.activityScoreDao().get(7))

        db.activityDao().delete(listOf(7))

        assertNull(db.activityStreamDao().get(7))
        assertNull(db.activityScoreDao().get(7))
        assertEquals(0, db.lapDao().forActivity(7).size)
    }

    @Test
    fun bodyBatteryDayUpsertReplacesAndFlowEmits() = runBlocking {
        val day = LocalDate.of(2026, 10, 23)
        val dao = db.bodyBatteryDayDao()
        assertNull(dao.get(day))
        assertNull(dao.flow(day).first())
        dao.upsert(BodyBatteryDayEntity(day, HrPacking.pack(listOf(HrPoint(1_792_738_800, 22)))))
        dao.upsert(BodyBatteryDayEntity(day, HrPacking.pack(listOf(HrPoint(1_792_738_800, 22), HrPoint(1_792_738_980, 23)))))
        assertEquals(2, HrPacking.unpack(dao.get(day)!!.packed).size)
        assertEquals(23, HrPacking.unpack(dao.flow(day).first()!!.packed).last().bpm)
    }

    @Test
    fun calibrationPendingCountsAnyPendingFitTaskButNotOtherIncrementalOnes() = runBlocking {
        val dao = db.taskDao()
        dao.insertIgnore(listOf(SyncTaskEntity("SLEEP_DAY", "2026-10-23", 3, TaskStatus.DONE, 1, null)))
        assertEquals(false, dao.calibrationPending())
        // The incremental refresh of today (priority 0) is not first-run work.
        dao.upsertAll(listOf(SyncTaskEntity("DAILY_SUMMARIES", "2026-10-24", 0, TaskStatus.PENDING, 0, null)))
        assertEquals(false, dao.calibrationPending())
        // A FIT download queued by the activity screen (priority 0) is: the stream is not there yet.
        dao.upsertAll(listOf(SyncTaskEntity("FIT", "42", 0, TaskStatus.PENDING, 0, null)))
        assertEquals(true, dao.calibrationPending())
        dao.update(SyncTaskEntity("FIT", "42", 0, TaskStatus.DONE, 1, null))
        assertEquals(false, dao.calibrationPending())
        // The backfill's own FIT tasks (priority 7) still count.
        dao.upsertAll(listOf(SyncTaskEntity("FIT", "43", 7, TaskStatus.PENDING, 0, null)))
        assertEquals(true, dao.calibrationPending())
    }

    @Test
    fun retryFailedRequeuesOnlyFailedAndKeepsAttempts() = runBlocking {
        val dao = db.taskDao()
        dao.upsertAll(
            listOf(
                SyncTaskEntity("SLEEP_DAY", "2026-10-01", 1, TaskStatus.FAILED, 2, "HTTP 500"),
                SyncTaskEntity("SLEEP_DAY", "2026-10-02", 1, TaskStatus.DONE, 1, null),
                SyncTaskEntity("SLEEP_DAY", "2026-10-03", 1, TaskStatus.EMPTY, 0, null),
                SyncTaskEntity("SLEEP_DAY", "2026-10-04", 1, TaskStatus.PENDING, 0, null),
                SyncTaskEntity("SLEEP_DAY", "2026-10-05", 1, TaskStatus.FAILED, 1, "bad"),
            ),
        )
        assertEquals(2, dao.failedCount().first())
        dao.requeueAllFailed()
        assertEquals(0, dao.failedCount().first())
        val rows = dao.all().associateBy { it.key }
        assertEquals(TaskStatus.PENDING, rows.getValue("2026-10-01").status)
        assertEquals(2, rows.getValue("2026-10-01").attempts)
        assertNull(rows.getValue("2026-10-01").lastError)
        assertEquals(1, rows.getValue("2026-10-05").attempts)
        assertEquals(TaskStatus.DONE, rows.getValue("2026-10-02").status)
        assertEquals(TaskStatus.EMPTY, rows.getValue("2026-10-03").status)
    }
}
