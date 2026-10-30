package dev.baseline.ui.sleep

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.baseline.data.Repository
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.HrDayEntity
import dev.baseline.data.db.HrPacking
import dev.baseline.data.db.RawResponse
import dev.baseline.data.db.TaskStatus
import dev.baseline.data.parse.DayParser
import dev.baseline.data.parse.fixture
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.HrPoint
import dev.baseline.sync.SyncScheduler
import dev.baseline.sync.closeWorkManagerDb
import dev.baseline.testutil.ScopeCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

private class CountingScheduler(wm: WorkManager) : SyncScheduler(wm, { false }) {
    val syncNows = AtomicInteger()

    override suspend fun syncNow() {
        syncNows.incrementAndGet()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SleepViewModelTest {
    private val date = LocalDate.of(2026, 10, 23)
    private val today = LocalDate.of(2026, 10, 24)
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private lateinit var repo: Repository
    private lateinit var scheduler: CountingScheduler

    private val scopes = ScopeCleanup()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = CountingScheduler(WorkManager.getInstance(context))
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context) { AppDatabase_Impl() }.allowMainThreadQueries().build()
        file = File.createTempFile("sleepvm", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        repo = Repository(db, UserPrefs(store), ApiKeyVault(store, { it }, { it }, Clock.systemUTC()))
    }

    @After
    fun tearDown() = runBlocking {
        scopes.close() // cancel VM and DataStore collectors before the database goes away
        db.close()
        closeWorkManagerDb(context)
        file.delete()
        Dispatchers.resetMain()
    }

    private fun vm(on: LocalDate = date) = scopes.track(SleepViewModel(repo, db.taskDao(), scheduler, on, today, ZoneOffset.ofHours(-7)))

    private suspend fun SleepViewModel.await(p: (SleepUiState) -> Boolean) = withTimeout(10_000) { state.first(p) }

    private fun seedFixtureNight() = runBlocking {
        db.dayDao().upsert(DayParser.mergeSleepDay(null, fixture("sleep_day.json").jsonObject))
    }

    private fun hrvRow(on: LocalDate = date) = runBlocking {
        val bytes = checkNotNull(javaClass.getResource("/fixtures/hrv_day.json")).readBytes()
        db.rawDao().upsert(RawResponse("HRV_DAY", on.toString(), bytes, Instant.EPOCH))
    }

    @Test
    fun needCardFromNextSleepNeed() = runBlocking {
        seedFixtureNight()
        val need = vm().await { it.needCard != null }.needCard!!
        assertEquals(Duration.ofMinutes(460), need.baseline)
        assertEquals(Duration.ZERO, need.adjustment)
        assertEquals(listOf("Sleep history ▼", "HRV ▲"), need.reasons)
        assertEquals(LocalTime.of(23, 40), need.bedtimeStart)
    }

    @Test
    fun bedAndWakeFollowTheZoneFlow() = runBlocking {
        seedFixtureNight()
        val zone = MutableStateFlow<ZoneId>(ZoneOffset.ofHours(-7))
        val vm = scopes.track(SleepViewModel(repo, db.taskDao(), scheduler, date, today, zone))
        val before = vm.await { it.bed != null }
        assertEquals(LocalTime.of(22, 52), before.bed)
        assertEquals(LocalTime.of(7, 41), before.wake)

        zone.value = ZoneOffset.ofHours(9) // travelling: the same night, in Tokyo time
        val after = vm.await { it.bed != LocalTime.of(22, 52) }
        assertEquals(LocalTime.of(14, 52), after.bed)
        assertEquals(LocalTime.of(23, 41), after.wake)
    }

    @Test
    fun heroFromFixtureNight() = runBlocking {
        seedFixtureNight()
        val s = vm().await { it.garminScore != null }
        assertEquals(87, s.garminScore)
        assertEquals(Duration.ofSeconds(31_740), s.asleep)
        assertEquals(Duration.ofMinutes(490), s.need)
        assertEquals(100, s.sleepPct) // 8h49m of 8h10m, capped
        assertEquals(LocalTime.of(22, 52), s.bed) // 05:52Z at UTC-7
        assertEquals(LocalTime.of(7, 41), s.wake)
        assertEquals(listOf("Deep", "REM", "Light", "Awake"), s.stages.map { it.label })
        assertEquals(listOf(12, 23, 65, 0), s.stages.map { it.pct })
    }

    @Test
    fun hrSeriesClippedToSleepWindow() = runBlocking {
        val start = Instant.parse("2026-10-23T06:00:00Z")
        val end = Instant.parse("2026-10-23T14:00:00Z")
        db.dayDao().upsert(DayEntity(date, sleepStart = start, sleepEnd = end, sleepSeconds = 28_800))
        val t = start.epochSecond
        // The previous evening's row holds the pre-bed and first samples, the wake-day's row the rest.
        db.hrDayDao().upsert(HrDayEntity(date.minusDays(1), HrPacking.pack(listOf(HrPoint(t - 600, 80), HrPoint(t, 60), HrPoint(t + 120, 55)))))
        db.hrDayDao().upsert(
            HrDayEntity(
                date,
                HrPacking.pack(
                    listOf(
                        HrPoint(end.epochSecond - 120, 48),
                        HrPoint(end.epochSecond, 50),
                        HrPoint(end.epochSecond + 120, 90),
                    ),
                ),
            ),
        )
        val s = vm().await { it.hrSeries.isNotEmpty() }
        assertEquals(listOf(60, 55, 48, 50), s.hrSeries.map { it.second })
        assertTrue(s.hrSeries.all { it.first in start..end })
        assertEquals("48", s.tiles.lowestHr)
    }

    @Test
    fun hrvSeriesComesFromTheRawRowAndLoadingEnds() = runBlocking {
        seedFixtureNight()
        val v = vm()
        assertTrue(v.await { it.needCard != null }.overnightLoading)
        hrvRow()
        val s = v.await { !it.overnightLoading }
        assertTrue(s.hrvSeries.size > 50)
        assertEquals(Instant.parse("2026-10-23T05:56:06Z") to 84, s.hrvSeries.first())
    }

    @Test
    fun sleepTilesShowDashWhenMissing() = runBlocking {
        db.dayDao().upsert(DayEntity(date))
        val s = vm().await { !it.loading }
        assertEquals(SleepTiles("—", "—", "—", "—"), s.tiles)
        assertEquals(Duration.ZERO, s.asleep)
        assertEquals(null, s.sleepPct)
        assertEquals(null, s.needCard)
        assertTrue(s.stages.all { it.seconds == null && it.pct == null })
    }

    @Test
    fun tilesFormatPresentValues() = runBlocking {
        db.dayDao().upsert(DayEntity(date, hrvLastNight = 58.0, bodyBatteryCharged = 62, spo2Avg = 96.0))
        val s = vm().await { !it.loading }
        assertEquals(SleepTiles("58", "—", "+62", "96"), s.tiles)
    }

    @Test
    fun lazyFetchQueuesHrvAndSpo2Once() = runBlocking {
        val v = vm()
        v.await { !it.loading }
        // The queueing runs in its own init coroutine, independent of the state flow: wait for its last step.
        withTimeout(10_000) { while (scheduler.syncNows.get() < 1) delay(10) }
        val tasks = db.taskDao().all().sortedBy { it.endpoint }
        assertEquals(listOf("HRV_DAY", "SPO2_DAY"), tasks.map { it.endpoint })
        assertTrue(tasks.all { it.key == "2026-10-23" && it.priority == 0 && it.status == TaskStatus.PENDING })
        assertEquals(1, scheduler.syncNows.get())
        // Row arrives, state recomposes: no second queueing.
        hrvRow()
        v.await { !it.overnightLoading }
        assertEquals(1, scheduler.syncNows.get())
    }

    @Test
    fun noFetchWhenTheHrvRowIsAlreadyStored() = runBlocking {
        hrvRow()
        vm().await { !it.loading }
        assertTrue(db.taskDao().all().isEmpty())
        assertEquals(0, scheduler.syncNows.get())
        assertFalse(vm().await { !it.loading }.overnightLoading)
    }
}
