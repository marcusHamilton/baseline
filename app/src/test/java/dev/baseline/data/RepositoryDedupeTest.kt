package dev.baseline.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityStreamEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.BodyBatteryDayEntity
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.HrDayEntity
import dev.baseline.data.db.HrPacking
import dev.baseline.data.db.RawResponse
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.HrPoint
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Room re-runs a query on every write to its table; the Repository must not pass an unchanged result on. Each test
 * writes something unrelated, waits until the plain DAO flow (the "probe") has re-emitted, which proves the table
 * invalidated, and then expects the Repository flow not to have.
 */
@RunWith(RobolectricTestRunner::class)
class RepositoryDedupeTest {
    private val date = LocalDate.of(2026, 10, 23)
    private val other = LocalDate.of(2026, 10, 22)
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private lateinit var repo: Repository
    private val scopes = ScopeCleanup()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        file = File.createTempFile("repodedupe", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        repo = Repository(db, UserPrefs(store), ApiKeyVault(store, { it }, { it }, Clock.systemUTC()))
    }

    @After
    fun tearDown(): Unit = runBlocking {
        scopes.close()
        db.close()
        file.delete()
    }

    private class Collected<T>(val items: CopyOnWriteArrayList<T>) {
        val size get() = items.size
    }

    private fun <T> collect(flow: Flow<T>): Collected<T> {
        val items = CopyOnWriteArrayList<T>()
        scopes.storeScope.launch(Dispatchers.Default) { flow.collect { items += it } }
        return Collected(items)
    }

    private suspend fun await(p: () -> Boolean) = withTimeout(10_000) {
        while (!p()) delay(10)
    }.also { delay(100) } // let a (wrongly) emitted repeat arrive after the probe did

    private fun raw(endpoint: String, key: String, json: String) = RawResponse(endpoint, key, json.toByteArray(), Instant.EPOCH)

    private fun activity(id: Long) = ActivityEntity(
        id, "a$id", "running", SportFamily.RUN, LocalDateTime.of(date, java.time.LocalTime.of(7, 0)), date,
        date.atTime(14, 0).toInstant(ZoneOffset.UTC), 600.0, null, null, null, null, null, null, null, null,
        20.0, null, null, null, null, null, null, null,
    )

    @Test
    fun dayDoesNotReEmitForAnotherDaysWrite() = runBlocking {
        db.dayDao().upsert(DayEntity(date, steps = 1))
        val probe = collect(db.dayDao().observe(date))
        val seen = collect(repo.day(date))
        await { probe.size == 1 && seen.size == 1 }

        db.dayDao().upsert(DayEntity(other, steps = 9))
        await { probe.size == 2 }
        assertEquals(1, seen.size)

        db.dayDao().upsert(DayEntity(date, steps = 2))
        await { seen.size == 2 }
        assertEquals(2, seen.items.last()?.steps)
    }

    @Test
    fun rangeFlowIgnoresWritesOutsideItsRange() = runBlocking {
        db.dayDao().upsert(DayEntity(date))
        val probe = collect(db.dayDao().range(date, date))
        val seen = collect(repo.days(date, date))
        await { probe.size == 1 && seen.size == 1 }

        db.dayDao().upsert(DayEntity(other))
        await { probe.size == 2 }
        assertEquals(1, seen.size)
    }

    @Test
    fun hrvReadingsAreNotReParsedOrReEmittedForUnrelatedRawWrites() = runBlocking {
        val hrv = """{"hrvReadings":[{"hrvValue":55,"readingTimeGMT":"2026-10-23T06:00:00.0"}]}"""
        db.rawDao().upsert(raw("HRV_DAY", date.toString(), hrv))
        val probe = collect(db.rawDao().json("HRV_DAY", date.toString()))
        val seen = collect(repo.hrvReadings(date))
        await { probe.size == 1 && seen.size == 1 }
        assertEquals(55, seen.items.single()!!.single().second)

        db.rawDao().upsert(raw("SLEEP_DAY", other.toString(), "{}"))
        // The same bytes written again (a re-fetch of an unchanged night) is not a change either.
        db.rawDao().upsert(raw("HRV_DAY", date.toString(), hrv))
        await { probe.size >= 2 }
        assertEquals(1, seen.size)

        db.rawDao().upsert(raw("HRV_DAY", date.toString(), hrv.replace("55", "60")))
        await { seen.size == 2 }
        assertEquals(60, seen.items.last()!!.single().second)
    }

    @Test
    fun profileFlowsFollowTheProfileButNotUnrelatedRawWrites() = runBlocking {
        db.rawDao().upsert(raw("PROFILE", "-", """{"measurementSystem":"statute_us","firstDayOfWeek":{"dayName":"sunday"}}"""))
        val probe = collect(db.rawDao().profileJson())
        val imperial = collect(repo.imperial)
        val firstDay = collect(repo.firstDayOfWeek)
        await { probe.size == 1 && imperial.size == 1 && firstDay.size == 1 }
        assertEquals(true, imperial.items.single())
        assertEquals(DayOfWeek.SUNDAY, firstDay.items.single())

        db.rawDao().upsert(raw("STEPS", "2026-10-01..2026-10-07", "[]"))
        await { probe.size == 2 }
        assertEquals(1, imperial.size)
        assertEquals(1, firstDay.size)

        db.rawDao().upsert(raw("PROFILE", "-", """{"measurementSystem":"metric","firstDayOfWeek":"monday"}"""))
        await { imperial.size == 2 && firstDay.size == 2 }
        assertEquals(false, imperial.items.last())
        assertEquals(DayOfWeek.MONDAY, firstDay.items.last())
    }

    @Test
    fun distinctContentComparesBlobsByBytesNotIdentity() = runBlocking {
        val seen =
            flowOf(
                listOf("a".toByteArray()),
                listOf("a".toByteArray()),
                listOf("a".toByteArray(), "b".toByteArray()),
                listOf("a".toByteArray(), "b".toByteArray()),
                emptyList(),
            )
                .distinctContent()
                .toList()
        assertEquals(listOf(listOf("a"), listOf("a", "b"), emptyList()), seen.map { rows -> rows.map { String(it) } })
    }

    @Test
    fun heartRateAndBodyBatteryDoNotReEmitForTheSameBytesWrittenAgain() = runBlocking {
        val packed = HrPacking.pack(listOf(HrPoint(1_000, 60), HrPoint(1_120, 62)))
        db.hrDayDao().upsert(HrDayEntity(date, packed))
        db.bodyBatteryDayDao().upsert(BodyBatteryDayEntity(date, packed))
        val hrProbe = collect(db.hrDayDao().observe(date))
        val bbProbe = collect(db.bodyBatteryDayDao().flow(date))
        val hr = collect(repo.heartRate(date))
        val bb = collect(repo.bodyBatteryCurve(date))
        await { hrProbe.size == 1 && bbProbe.size == 1 && hr.size == 1 && bb.size == 1 }

        db.hrDayDao().upsert(HrDayEntity(date, packed.copyOf()))
        db.bodyBatteryDayDao().upsert(BodyBatteryDayEntity(date, packed.copyOf()))
        await { hrProbe.size == 2 && bbProbe.size == 2 }
        assertEquals(1, hr.size)
        assertEquals(1, bb.size)

        val longer = HrPacking.pack(listOf(HrPoint(1_000, 60), HrPoint(1_120, 62), HrPoint(1_240, 64)))
        db.hrDayDao().upsert(HrDayEntity(date, longer))
        db.bodyBatteryDayDao().upsert(BodyBatteryDayEntity(date, longer))
        await { hr.size == 2 && bb.size == 2 }
        assertEquals(3, hr.items.last().size)
        assertEquals(3, bb.items.last().size)
    }

    @Test
    fun activityStreamDoesNotReEmitForAnotherActivitysWrite() = runBlocking {
        db.activityDao().upsert(activity(1))
        db.activityDao().upsert(activity(2))
        db.activityStreamDao().upsert(ActivityStreamEntity(1, byteArrayOf(1, 2, 3)))
        val probe = collect(db.activityStreamDao().observe(1))
        val seen = collect(repo.activityStream(1))
        await { probe.size == 1 && seen.size == 1 }

        db.activityStreamDao().upsert(ActivityStreamEntity(2, byteArrayOf(9)))
        db.activityStreamDao().upsert(ActivityStreamEntity(1, byteArrayOf(1, 2, 3)))
        await { probe.size >= 2 }
        assertEquals(1, seen.size)

        db.activityStreamDao().upsert(ActivityStreamEntity(1, byteArrayOf(1, 2, 4)))
        await { seen.size == 2 }
    }

    @Test
    fun activitiesDoNotReEmitWhenOnlyAnActivityScoreOrSyncTaskIsWritten() = runBlocking {
        db.activityDao().upsert(activity(1))
        val probe = collect(db.activityDao().all())
        val seen = collect(repo.activities())
        await { probe.size == 1 && seen.size == 1 }

        db.activityDao().upsert(activity(1)) // an identical re-ingest
        await { probe.size == 2 }
        assertEquals(1, seen.size)

        db.activityDao().upsert(activity(2))
        await { seen.size == 2 }
    }
}
