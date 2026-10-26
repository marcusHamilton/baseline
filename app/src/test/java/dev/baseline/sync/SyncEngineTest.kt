package dev.baseline.sync

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.HrPacking
import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.PARSE_ERROR_PREFIX
import dev.baseline.data.db.TaskStatus
import dev.baseline.data.parse.fixture
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminEndpoint.*
import dev.baseline.garmin.GarminResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-24T08:00:00Z"), ZoneOffset.UTC)
    private val calls = mutableListOf<Pair<GarminEndpoint, String>>()
    private val pauses = mutableListOf<Long>()
    private val oct23 = LocalDate.of(2026, 10, 23)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(ApplicationProvider.getApplicationContext()) { AppDatabase_Impl() }
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun ok(s: String) = GarminResult.Ok(s.toByteArray(), "application/json")
    private fun okFixture(name: String) = ok(fixture(name).toString())
    private fun task(e: GarminEndpoint, key: String, priority: Int, attempts: Int = 0, status: TaskStatus = TaskStatus.PENDING) =
        SyncTaskEntity(e.name, key, priority, status, attempts, null)

    private fun engine(clk: Clock = clock, f: suspend (GarminEndpoint, String) -> GarminResult) =
        SyncEngine({ e, k -> calls += e to k; f(e, k) }, db, clk) { pauses += it }

    /** A clock the test moves by hand. */
    private class StepClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant() = now
    }

    private suspend fun tasks() = db.taskDao().all()
    private suspend fun task(e: GarminEndpoint, key: String) = tasks().single { it.endpoint == e.name && it.key == key }

    private fun activity(id: Long, day: String) =
        """{"activityId":$id,"activityName":"a$id","activityType":{"typeKey":"running"},""" +
            """"startTimeGMT":"$day 14:00:00","startTimeLocal":"$day 07:00:00","duration":100.0}"""

    @Test
    fun sleepDayPlaceholderIsEmptyNotFailed() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(SLEEP_DAY, "2026-10-05", 6)))
        val r = engine { _, _ -> ok("""{"id":null,"sleepTimeSeconds":null}""") }.runPending {}
        assertEquals(SyncOutcome.COMPLETED, r.outcome)
        assertNull(r.changedFrom)
        val t = task(SLEEP_DAY, "2026-10-05")
        assertEquals(TaskStatus.EMPTY, t.status)
        assertNull(t.lastError)
        assertEquals(listOf("2026-10-05"), db.rawDao().keys("SLEEP_DAY"))
        assertTrue(db.dayDao().range(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).first().isEmpty())
    }

    @Test
    fun runPendingStopsAtBudgetWithPartial() = runBlocking {
        db.taskDao().insertIgnore((1..5).map { task(ACTIVITY_DETAIL, "$it", it) })
        val clk = StepClock(clock.instant())
        val r = engine(clk) { _, _ -> clk.now = clk.now.plusSeconds(60); ok("{}") }.runPending(3.minutes) {}
        assertEquals(SyncOutcome.PARTIAL, r.outcome)
        assertEquals(3, tasks().count { it.status == TaskStatus.DONE })
        assertEquals(2, tasks().count { it.status == TaskStatus.PENDING })
    }

    @Test
    fun sleepDayNonObjectOrBadDateStillFails() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(SLEEP_DAY, "2026-10-05", 6), task(SLEEP_DAY, "2026-10-06", 6)))
        engine { _, k -> ok(if (k == "2026-10-05") "[]" else """{"calendarDate":"garbage"}""") }.runPending {}
        assertEquals(TaskStatus.FAILED, task(SLEEP_DAY, "2026-10-05").status)
        assertEquals(TaskStatus.FAILED, task(SLEEP_DAY, "2026-10-06").status)
    }

    @Test
    fun backoffThatWouldOutlastTheBudgetStopsTheRun() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(STEPS, "2026-10-01..2026-10-07", 4)))
        val clk = StepClock(clock.instant())
        val r = engine(clk) { _, _ -> clk.now = clk.now.plusSeconds(420); GarminResult.RateLimited(429) }.runPending {}
        assertEquals(SyncOutcome.RATE_LIMITED, r.outcome) // 7 min used, a 60 s back-off would reach the 8-minute budget
        assertTrue(pauses.none { it >= 60_000 })
        assertEquals(TaskStatus.PENDING, tasks().single().status)
    }

    @Test
    fun budgetUsedUpWithNothingLeftIsCompleted() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(ACTIVITY_DETAIL, "1", 1)))
        val clk = StepClock(clock.instant())
        val r = engine(clk) { _, _ -> clk.now = clk.now.plusSeconds(600); ok("{}") }.runPending(3.minutes) {}
        assertEquals(SyncOutcome.COMPLETED, r.outcome)
    }

    @Test
    fun repairSleepPlaceholdersMarksThemEmpty() = runBlocking {
        fun failed(e: GarminEndpoint, key: String, err: String) = SyncTaskEntity(e.name, key, 6, TaskStatus.FAILED, 0, err)
        db.taskDao().insertIgnore(listOf(
            failed(SLEEP_DAY, "2026-10-05", "missing calendarDate"),
            failed(SLEEP_DAY, "2026-10-06", "400"),
            failed(HRV_DAY, "2026-10-05", "missing calendarDate"),
            task(SLEEP_DAY, "2026-10-07", 6, status = TaskStatus.DONE),
        ))
        db.taskDao().repairSleepPlaceholders()
        assertEquals(TaskStatus.EMPTY, task(SLEEP_DAY, "2026-10-05").status)
        assertEquals(TaskStatus.FAILED, task(SLEEP_DAY, "2026-10-06").status)
        assertEquals(TaskStatus.FAILED, task(HRV_DAY, "2026-10-05").status)
        assertEquals(TaskStatus.DONE, task(SLEEP_DAY, "2026-10-07").status)
        assertNull(task(SLEEP_DAY, "2026-10-05").lastError)
        assertEquals("400", task(SLEEP_DAY, "2026-10-06").lastError)
    }

    @Test
    fun backoffSequenceThenStop() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(STRESS, "2026-10-01..2026-10-07", 4)))
        val r = engine { _, _ -> GarminResult.RateLimited(429) }.runPending {}
        assertEquals(SyncOutcome.RATE_LIMITED, r.outcome)
        assertEquals(listOf(60_000L, 120_000L, 240_000L), pauses)
        assertEquals(4, calls.size)
        assertEquals(TaskStatus.PENDING, tasks().single().status)
    }

    @Test
    fun keyExpiresMidBackfillResumesWithoutRefetch() = runBlocking {
        db.taskDao().insertIgnore((1..5).map { task(ACTIVITY_DETAIL, "$it", it) })
        val r = engine { _, k -> if (k == "3") GarminResult.KeyInvalid else ok("{}") }.runPending {}
        assertEquals(SyncOutcome.KEY_INVALID, r.outcome)
        assertEquals(2, tasks().count { it.status == TaskStatus.DONE })
        assertEquals(3, tasks().count { it.status == TaskStatus.PENDING })

        calls.clear()
        var last = db.taskDao().counts().first()
        val r2 = engine { _, _ -> ok("{}") }.runPending { last = it }
        assertEquals(SyncOutcome.COMPLETED, r2.outcome)
        assertEquals(listOf("3", "4", "5"), calls.map { it.second })
        assertEquals(5, last.done)
        assertEquals(5, last.total)
    }

    @Test
    fun rerunningDoneTaskDoesNotDuplicate() = runBlocking {
        val body = """{"data":[{"calendarDate":"2026-10-23","heartRate":{"restingValue":49}}]}""".toByteArray()
        val ingest = RawIngest(db)
        repeat(2) { ingest.apply(DAILY_SUMMARIES, "2026-10-23..2026-10-23", body) }
        assertEquals(1, db.rawDao().keys("DAILY_SUMMARIES").size)
        assertEquals(1, db.dayDao().range(oct23, oct23).first().size)
    }

    @Test
    fun fullActivityPageQueuesNextPageAndFits() = runBlocking {
        val page = fixture("activities_all.json").jsonArray.take(50)
        RawIngest(db).apply(ACTIVITY_PAGE, "0", page.toString().toByteArray())

        val next = task(ACTIVITY_PAGE, "1")
        assertEquals(TaskStatus.PENDING, next.status)
        assertEquals(5, next.priority)
        val fits = tasks().filter { it.endpoint == "FIT" }
        assertEquals(50, fits.size)
        assertTrue(fits.all { it.priority == 7 && it.status == TaskStatus.PENDING })
        assertEquals(50, db.activityDao().all().first().size)
    }

    @Test
    fun shortActivityPageQueuesNoNextPage() = runBlocking {
        val page = fixture("activities_all.json").jsonArray.take(49)
        RawIngest(db).apply(ACTIVITY_PAGE, "0", page.toString().toByteArray())
        assertTrue(tasks().none { it.endpoint == "ACTIVITY_PAGE" })
    }

    @Test
    fun badRequestSplitsRangeOnce() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(STRESS, "2026-10-01..2026-10-07", 4)))
        // 400 on the original, then stop on the first half so the split is observable.
        val r = engine { _, k -> if (k == "2026-10-01..2026-10-07") GarminResult.BadRequest("too wide") else GarminResult.KeyInvalid }
            .runPending {}
        assertEquals(SyncOutcome.KEY_INVALID, r.outcome)
        val halves = tasks().sortedBy { it.key }
        assertEquals(listOf("2026-10-01..2026-10-03", "2026-10-04..2026-10-07"), halves.map { it.key })
        assertTrue(halves.all { it.priority == 4 && it.attempts == 1 && it.status == TaskStatus.PENDING })

        // A 400 on a half is final.
        engine { _, _ -> GarminResult.BadRequest("still bad") }.runPending {}
        assertEquals(2, tasks().size)
        assertTrue(tasks().all { it.status == TaskStatus.FAILED && it.lastError == "400" })
    }

    @Test
    fun badRequestOnSingleDayOrNonRangeFails() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(STRESS, "2026-10-01..2026-10-01", 4), task(SLEEP_DAY, "2026-10-01", 6)))
        engine { _, _ -> GarminResult.BadRequest("x") }.runPending {}
        assertTrue(tasks().all { it.status == TaskStatus.FAILED && it.lastError == "400" })
        assertEquals(2, tasks().size)
    }

    @Test
    fun emptyFailedAndOfflineStatuses() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(ACTIVITY_DETAIL, "1", 1), task(ACTIVITY_DETAIL, "2", 2), task(ACTIVITY_DETAIL, "3", 3)))
        val r = engine { _, k ->
            when (k) {
                "1" -> GarminResult.Empty
                "2" -> GarminResult.Failed(403, "Forbidden")
                else -> throw IOException("no network")
            }
        }.runPending {}
        assertEquals(SyncOutcome.OFFLINE, r.outcome)
        assertEquals(TaskStatus.EMPTY, task(ACTIVITY_DETAIL, "1").status)
        assertEquals(TaskStatus.FAILED, task(ACTIVITY_DETAIL, "2").status)
        assertEquals("403 Forbidden", task(ACTIVITY_DETAIL, "2").lastError)
        assertEquals(TaskStatus.PENDING, task(ACTIVITY_DETAIL, "3").status)
        assertEquals(listOf(1000L, 1000L), pauses)
    }

    @Test
    fun malformedJsonMarksFailedKeepsRaw() = runBlocking {
        db.taskDao().insertIgnore(
            listOf(task(DAILY_SUMMARIES, "2026-10-01..2026-10-23", 2), task(STEPS, "2026-10-17..2026-10-23", 4)),
        )
        val r = engine { e, _ ->
            if (e == DAILY_SUMMARIES) ok("{not json") else ok("""[{"calendarDate":"2026-10-23","totalSteps":9000,"stepGoal":8000}]""")
        }.runPending {}
        assertEquals(SyncOutcome.COMPLETED, r.outcome)
        assertNotNull(db.rawDao().get("DAILY_SUMMARIES", "2026-10-01..2026-10-23"))
        val failed = task(DAILY_SUMMARIES, "2026-10-01..2026-10-23")
        assertEquals(TaskStatus.FAILED, failed.status)
        assertTrue(failed.lastError!!.startsWith(PARSE_ERROR_PREFIX)) // lets the day screens say "Couldn't read this data"
        assertEquals(TaskStatus.DONE, task(STEPS, "2026-10-17..2026-10-23").status)
        assertEquals(9000, db.dayDao().get(oct23)!!.steps)
    }

    @Test
    fun parseFailureMidResponseRollsBackTypedWrites() = runBlocking {
        val body = """{"data":[{"calendarDate":"2026-10-22"},{"nope":1}]}""".toByteArray()
        runCatching { RawIngest(db).apply(DAILY_SUMMARIES, "k", body) }.let { assertTrue(it.isFailure) }
        assertNotNull(db.rawDao().get("DAILY_SUMMARIES", "k"))
        assertNull(db.dayDao().get(LocalDate.of(2026, 10, 22)))
    }

    @Test
    fun activityRangeDeletesMissingLocalActivities() = runBlocking {
        val parse = { id: Long, day: String ->
            dev.baseline.data.parse.ActivityParser.parseListItem(
                kotlinx.serialization.json.Json.parseToJsonElement(activity(id, day)) as kotlinx.serialization.json.JsonObject,
            )
        }
        listOf(1L to "2026-10-20", 2L to "2026-10-21", 3L to "2026-10-22", 4L to "2026-10-10", 5L to "2026-10-18").forEach { (id, d) ->
            db.activityDao().upsert(parse(id, d))
        }
        val body = "[${activity(1, "2026-10-20")},${activity(3, "2026-10-22")}]".toByteArray()
        val res = RawIngest(db).apply(ACTIVITY_RANGE, "2026-10-18..2026-10-24", body)

        // 5 is on the window's oldest day (10-18) and absent from the response: it must survive.
        assertEquals(setOf(1L, 3L, 4L, 5L), db.activityDao().all().first().map { it.id }.toSet())
        assertEquals(LocalDate.of(2026, 10, 20), res.changedFrom)
        // A deletion alone also counts as a change, on the deleted activity's day.
        val res2 = RawIngest(db).apply(ACTIVITY_RANGE, "2026-10-18..2026-10-24", "[${activity(3, "2026-10-22")}]".toByteArray())
        assertEquals(LocalDate.of(2026, 10, 20), res2.changedFrom)
        assertEquals(setOf(3L, 4L, 5L), db.activityDao().all().first().map { it.id }.toSet())
    }

    @Test
    fun fullActivityRangePageDeletesNothing() = runBlocking {
        val local = dev.baseline.data.parse.ActivityParser.parseListItem(
            kotlinx.serialization.json.Json.parseToJsonElement(activity(900, "2026-10-22")) as kotlinx.serialization.json.JsonObject,
        )
        db.activityDao().upsert(local)
        val page = fixture("activities_all.json").jsonArray.take(50)
        RawIngest(db).apply(ACTIVITY_RANGE, "2026-10-01..2026-10-24", page.toString().toByteArray())
        assertNotNull(db.activityDao().get(900))
    }

    @Test
    fun emptyActivityRangeDeletesLocalActivities() = runBlocking {
        val parse = { id: Long, day: String ->
            dev.baseline.data.parse.ActivityParser.parseListItem(
                kotlinx.serialization.json.Json.parseToJsonElement(activity(id, day)) as kotlinx.serialization.json.JsonObject,
            )
        }
        db.activityDao().upsert(parse(1, "2026-10-21"))
        db.activityDao().upsert(parse(2, "2026-10-23"))
        db.taskDao().insertIgnore(listOf(task(ACTIVITY_RANGE, "2026-10-18..2026-10-24", 0)))
        val r = engine { _, _ -> GarminResult.Empty }.runPending {}
        assertEquals(TaskStatus.EMPTY, tasks().single().status)
        assertTrue(db.activityDao().all().first().isEmpty())
        assertEquals(LocalDate.of(2026, 10, 21), r.changedFrom)
    }

    @Test
    fun ingestIoErrorGoesOfflineAndKeepsTaskPending() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(FIT, "7", 7), task(ACTIVITY_DETAIL, "1", 8)))
        val ingest = RawIngest(db, { _, _ -> throw IOException("disk") })
        val r = SyncEngine({ _, _ -> ok("x") }, db, clock, ingest) { pauses += it }.runPending {}
        assertEquals(SyncOutcome.OFFLINE, r.outcome)
        assertTrue(tasks().all { it.status == TaskStatus.PENDING })
    }

    @Test
    fun unknownEndpointFailsTaskAndContinues() = runBlocking {
        db.taskDao().insertIgnore(listOf(SyncTaskEntity("BOGUS", "x", 0, TaskStatus.PENDING, 0, null), task(ACTIVITY_DETAIL, "1", 1)))
        val r = engine { _, _ -> ok("{}") }.runPending {}
        assertEquals(SyncOutcome.COMPLETED, r.outcome)
        assertEquals("unknown endpoint", tasks().single { it.endpoint == "BOGUS" }.lastError)
        assertEquals(TaskStatus.DONE, task(ACTIVITY_DETAIL, "1").status)
        assertEquals(1, calls.size)
    }

    @Test
    fun rateLimitCounterResetsAfterSuccess() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(ACTIVITY_DETAIL, "1", 1), task(ACTIVITY_DETAIL, "2", 2)))
        val script = ArrayDeque(listOf<GarminResult>(GarminResult.RateLimited(429), ok("{}"), GarminResult.RateLimited(429), ok("{}")))
        engine { _, _ -> script.removeFirst() }.runPending {}
        assertEquals(listOf(60_000L, 1000L, 60_000L), pauses)
        assertTrue(tasks().all { it.status == TaskStatus.DONE })
    }

    @Test
    fun existingFitPathSurvivesActivityReupsert() = runBlocking {
        RawIngest(db).apply(ACTIVITY_PAGE, "0", "[${activity(1, "2026-10-20")}]".toByteArray())
        val withFit = db.activityDao().get(1)!!.copy(fitPath = "fit/1.fit", hrCoverage = 0.9)
        db.activityDao().upsert(withFit)
        db.taskDao().delete("FIT", "1")
        RawIngest(db).apply(ACTIVITY_PAGE, "0", "[${activity(1, "2026-10-20")}]".toByteArray())
        assertEquals("fit/1.fit", db.activityDao().get(1)!!.fitPath)
        assertTrue(tasks().none { it.endpoint == "FIT" })
    }

    @Test
    fun listResyncKeepsFitDerivedPoolLength() = runBlocking {
        RawIngest(db).apply(ACTIVITY_PAGE, "0", "[${activity(1, "2026-10-20")}]".toByteArray())
        db.activityDao().upsert(db.activityDao().get(1)!!.copy(poolLengthM = 25.0))
        RawIngest(db).apply(ACTIVITY_PAGE, "0", "[${activity(1, "2026-10-20")}]".toByteArray())
        assertEquals(25.0, db.activityDao().get(1)!!.poolLengthM!!, 0.0)
    }

    @Test
    fun ingestStoresCurve() = runBlocking {
        val item = fixture("bodybattery_chunks_day.json").jsonObject
        val other = JsonObject(item + ("date" to JsonPrimitive("2026-10-22")))
        // sleepEnd already known from the sleep endpoints: the wake level comes from the curve.
        db.dayDao().upsert(DayEntity(oct23, sleepEnd = Instant.ofEpochMilli(1792766501000)))
        db.taskDao().insertIgnore(listOf(task(BODY_BATTERY, "2026-10-22..2026-10-23", 3)))
        engine { _, _ -> ok(JsonArray(listOf(other, item)).toString()) }.runPending {}

        assertEquals(TaskStatus.DONE, tasks().single().status)
        for (d in listOf(oct23, oct23.minusDays(1))) {
            val curve = HrPacking.unpack(db.bodyBatteryDayDao().get(d)!!.packed)
            assertTrue(curve.size in 288..576)
            assertEquals(1792738800L, curve.first().epochSec)
        }
        assertEquals(91, db.dayDao().get(oct23)!!.bodyBatteryWake)
        assertNull(db.dayDao().get(oct23.minusDays(1))!!.bodyBatteryWake) // no sleepEnd -> null
    }

    private val bbItem get() = fixture("bodybattery_chunks_day.json").toString()

    @Test
    fun wakeIsRecomputedWhenSleepSummaryArrivesAfterBodyBattery() = runBlocking {
        RawIngest(db).apply(BODY_BATTERY, "2026-10-23..2026-10-23", "[$bbItem]".toByteArray())
        assertNull(db.dayDao().get(oct23)!!.bodyBatteryWake)
        RawIngest(db).apply(
            SLEEP_SUMMARIES, "2026-10-23..2026-10-23",
            """[{"calendarDate":"2026-10-23","sleepEndTimestampGMT":1792766501000},{"calendarDate":"2026-10-20","sleepEndTimestampGMT":1792766501000}]""".toByteArray(),
        )
        assertEquals(91, db.dayDao().get(oct23)!!.bodyBatteryWake)
        assertNull(db.dayDao().get(LocalDate.of(2026, 10, 20))!!.bodyBatteryWake) // no curve stored for that day
    }

    @Test
    fun wakeIsRecomputedWhenSleepDayArrivesAfterBodyBattery() = runBlocking {
        RawIngest(db).apply(BODY_BATTERY, "2026-10-23..2026-10-23", "[$bbItem]".toByteArray())
        RawIngest(db).apply(SLEEP_DAY, "2026-10-23", fixture("sleep_day.json").toString().toByteArray())
        assertEquals(91, db.dayDao().get(oct23)!!.bodyBatteryWake)
    }

    @Test
    fun allNullBodyBatteryDayStoresNoCurve() = runBlocking {
        val item = """{"date":"2026-10-23","charged":5,"bodyBatteryValueDescriptorDTOList":[
            {"bodyBatteryValueDescriptorIndex":0,"bodyBatteryValueDescriptorKey":"timestamp"},
            {"bodyBatteryValueDescriptorIndex":1,"bodyBatteryValueDescriptorKey":"bodyBatteryLevel"}],
            "bodyBatteryValuesArray":[[1,null],[2,null]]}"""
        RawIngest(db).apply(BODY_BATTERY, "2026-10-23..2026-10-23", "[$item]".toByteArray())
        assertNull(db.bodyBatteryDayDao().get(oct23))
        assertEquals(5, db.dayDao().get(oct23)!!.bodyBatteryCharged)
    }

    @Test
    fun incrementalRefetchesTodayAndYesterday() = runBlocking {
        val today = LocalDate.of(2026, 10, 24)
        db.taskDao().insertIgnore(listOf(task(SLEEP_DAY, "2026-10-23", 6, attempts = 3, status = TaskStatus.DONE)))
        engine { _, _ -> GarminResult.Empty }.planIncremental(today)

        val pending = tasks()
        assertTrue(pending.all { it.priority == 0 && it.status == TaskStatus.PENDING && it.attempts == 0 })
        val keys = pending.map { it.endpoint to it.key }.toSet()
        assertTrue(keys.containsAll(listOf("SLEEP_DAY" to "2026-10-24", "SLEEP_DAY" to "2026-10-23")))
        assertTrue(keys.containsAll(listOf("HEART_RATE_DAY" to "2026-10-24", "HRV_DAY" to "2026-10-23")))
        assertTrue(keys.containsAll(listOf(STEPS, STRESS, DAILY_SUMMARIES, HRV_RANGE, SLEEP_SUMMARIES, BODY_BATTERY, ACTIVITY_RANGE).map { it.name to "2026-10-18..2026-10-24" }))
        assertEquals(13, pending.size)
    }

    @Test
    fun incrementalRequeuesAnOverlappingFailedWindowFromYesterday() = runBlocking {
        val failed = task(STEPS, "2026-10-17..2026-10-23", 0, attempts = 3, status = TaskStatus.FAILED)
            .copy(lastError = "unreadable: bad json")
        val older = task(STEPS, "2026-09-01..2026-09-07", 0, attempts = 3, status = TaskStatus.FAILED)
            .copy(lastError = "unreadable: bad json")
        db.taskDao().insertIgnore(listOf(failed, older))
        engine { _, _ -> GarminResult.Empty }.planIncremental(LocalDate.of(2026, 10, 24))

        val requeued = tasks().first { it.key == "2026-10-17..2026-10-23" }
        assertEquals(TaskStatus.PENDING, requeued.status)
        assertEquals(3, requeued.attempts) // not reset: attempts >= 1 marks a window that was already split
        assertNull(requeued.lastError)
        assertEquals(TaskStatus.FAILED, tasks().first { it.key == "2026-09-01..2026-09-07" }.status)
    }

    @Test
    fun incrementalKeepsHttpFailedOverlappingWindowsFailed() = runBlocking {
        db.taskDao().insertIgnore(listOf(
            task(STEPS, "2026-10-17..2026-10-23", 0, attempts = 0, status = TaskStatus.FAILED).copy(lastError = "403 Forbidden"),
        ))
        engine { _, _ -> GarminResult.Empty }.planIncremental(LocalDate.of(2026, 10, 24))

        val t = tasks().first { it.key == "2026-10-17..2026-10-23" }
        assertEquals(TaskStatus.FAILED, t.status)
        assertEquals("403 Forbidden", t.lastError)
    }

    @Test
    fun unreadableWindowIsRequeuedOncePerDay() = runBlocking {
        val failed = task(STEPS, "2026-10-17..2026-10-23", 0, attempts = 0, status = TaskStatus.FAILED)
            .copy(lastError = "unreadable: bad json")
        db.taskDao().insertIgnore(listOf(failed))
        val e = engine { _, _ -> GarminResult.Empty }
        e.planIncremental(LocalDate.of(2026, 10, 24))
        assertEquals(TaskStatus.PENDING, tasks().first { it.key == "2026-10-17..2026-10-23" }.status)

        db.taskDao().update(failed)
        e.planIncremental(LocalDate.of(2026, 10, 24)) // same day, second run
        assertEquals(TaskStatus.FAILED, tasks().first { it.key == "2026-10-17..2026-10-23" }.status)

        e.planIncremental(LocalDate.of(2026, 10, 25)) // new day: one more chance
        assertEquals(TaskStatus.PENDING, tasks().first { it.key == "2026-10-17..2026-10-23" }.status)
    }

    @Test
    fun ensureBackfillPlannedOnlyOnEmptyTable() = runBlocking {
        val e = engine { _, _ -> GarminResult.Empty }
        e.ensureBackfillPlanned(oct23)
        val n = tasks().size
        assertTrue(n > 100)
        db.taskDao().update(task(PROFILE, "-").copy(status = TaskStatus.DONE))
        e.ensureBackfillPlanned(oct23.plusDays(1))
        assertEquals(n, tasks().size)
        assertEquals(TaskStatus.DONE, task(PROFILE, "-").status)
    }

    @Test
    fun sleepSummaryPlaceholdersSkipped() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(SLEEP_SUMMARIES, "2026-10-01..2026-10-23", 3)))
        val body = """[{"id":1,"calendarDate":null,"sleepTimeSeconds":null},{"id":2},
            {"calendarDate":"2026-10-22","sleepTimeSeconds":28000,"sleepEndTimestampGMT":1792700000000},
            {"calendarDate":"2026-10-23","sleepTimeSeconds":30000}]"""
        engine { _, _ -> ok(body) }.runPending {}
        assertEquals(TaskStatus.DONE, tasks().single().status)
        val days = db.dayDao().range(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).first()
        assertEquals(listOf(22, 23), days.map { it.date.dayOfMonth })
        assertEquals(28000, days[0].sleepSeconds)
    }

    @Test
    fun changedFromIsEarliestTouchedDay() = runBlocking {
        db.taskDao().insertIgnore(listOf(task(STEPS, "2026-10-17..2026-10-23", 4), task(STRESS, "2026-10-17..2026-10-23", 4)))
        val r = engine { e, _ ->
            if (e == STEPS) ok("""[{"calendarDate":"2026-10-21","totalSteps":1},{"calendarDate":"2026-10-19","totalSteps":2}]""")
            else ok("""[{"calendarDate":"2026-10-20","values":{"overallStressLevel":30}}]""")
        }.runPending {}
        assertEquals(LocalDate.of(2026, 10, 19), r.changedFrom)

        db.taskDao().insertIgnore(listOf(task(HRV_RANGE, "2026-10-17..2026-10-23", 2)))
        assertNull(engine { _, _ -> GarminResult.Empty }.runPending {}.changedFrom)
    }

    @Test
    fun ingestFromFixturesPopulatesDays() = runBlocking {
        val bodies = mapOf(
            PROFILE to "profile.json", HR_ZONES to "hr_zones.json", DAILY_SUMMARIES to "dailysummaries_history.json",
            HRV_RANGE to "hrv_history.json", SLEEP_SUMMARIES to "sleep_summaries_history.json",
            BODY_BATTERY to "bodybattery_history.json", STRESS to "stress_history.json", STEPS to "steps_history.json",
            SLEEP_DAY to "sleep_day.json", HEART_RATE_DAY to "heartrate_day.json", ACTIVITY_PAGE to "activities_all.json",
        )
        // Whole-history bodies are served for the short window: every item carries its own calendarDate.
        db.taskDao().insertIgnore(BackfillPlanner.plan(LocalDate.of(2026, 10, 17), oct23))
        val e = engine { endpoint, key ->
            if (endpoint == ACTIVITY_PAGE && key != "0") GarminResult.Empty
            else bodies[endpoint]?.let(::okFixture) ?: GarminResult.Empty
        }
        val r = e.runPending {}

        assertEquals(SyncOutcome.COMPLETED, r.outcome)
        val d = db.dayDao().get(oct23)!!
        assertEquals(49, d.restingHr)
        assertEquals(96.0, d.hrvLastNight!!, 0.0)
        assertEquals(31740, d.sleepSeconds)
        assertEquals(719, HrPacking.unpack(db.hrDayDao().get(oct23)!!.packed).size)
        assertEquals(175, db.activityDao().all().first().size)
        assertEquals(175, tasks().count { it.endpoint == "FIT" })
        assertTrue(tasks().none { it.status == TaskStatus.FAILED })
        assertNotNull(r.changedFrom)
    }
}
