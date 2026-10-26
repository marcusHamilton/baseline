package dev.baseline.ui.calendar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.Repository
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.RawResponse
import dev.baseline.data.db.ScoreEntity
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.RecoveryZone
import dev.baseline.testutil.ScopeCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CalendarViewModelTest {
    private val today = LocalDate.of(2026, 10, 24)
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private lateinit var vm: CalendarViewModel
    private lateinit var store: DataStore<Preferences>

    private val scopes = ScopeCleanup()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context) { AppDatabase_Impl() }.allowMainThreadQueries().build()
        file = File.createTempFile("calvm", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        vm = scopes.track(CalendarViewModel(Repository(db, UserPrefs(store), ApiKeyVault(store, { it }, { it }, Clock.systemUTC())), today))
    }

    @After
    fun tearDown() = runBlocking {
        scopes.close() // cancel VM and DataStore collectors before the database goes away
        db.close()
        file.delete()
        Dispatchers.resetMain()
    }

    private suspend fun awaitState(predicate: (CalendarUiState) -> Boolean) = withTimeout(10_000) { vm.state.first(predicate) }

    private fun profile(firstDay: String) = runBlocking {
        db.rawDao().upsert(RawResponse("PROFILE", "-", firstDay.toByteArray(), Instant.EPOCH))
    }

    @Test
    fun october2026MondayStart() = runBlocking {
        val s = awaitState { it.month == YearMonth.of(2026, 10) }
        assertEquals(DayOfWeek.MONDAY, s.firstDayOfWeek)
        assertEquals(listOf(null, null, null), s.cells.take(3))
        assertEquals(LocalDate.of(2026, 10, 1), s.cells[3]?.date)
        assertEquals(31, s.cells.count { it != null })
        assertEquals(today, s.selected)
        assertTrue(s.cells.single { it?.date == today }!!.isToday)
    }

    @Test
    fun firstDayFollowsProfile() = runBlocking {
        profile("""{"firstDayOfWeek":"sunday"}""")
        val s = awaitState { it.firstDayOfWeek == DayOfWeek.SUNDAY }
        assertEquals(4, s.cells.takeWhile { it == null }.size) // Oct 1 is a Thursday
    }

    @Test
    fun firstDayReadsGarminsObjectForm() = runBlocking {
        profile("""{"firstDayOfWeek":{"dayId":1,"dayName":"sunday"}}""")
        awaitState { it.firstDayOfWeek == DayOfWeek.SUNDAY }
        Unit
    }

    @Test
    fun monthRolloverWithAnEarlierDaySelectedMovesSelectionToTheNewMonth() = runBlocking {
        val clock = MutableStateFlow(LocalDate.of(2026, 10, 31))
        val v = scopes.track(CalendarViewModel(Repository(db, UserPrefs(store), ApiKeyVault(store, { it }, { it }, Clock.systemUTC())), clock))
        v.select(LocalDate.of(2026, 10, 12))
        clock.value = LocalDate.of(2026, 11, 1)
        val s = withTimeout(10_000) { v.state.first { it.month == YearMonth.of(2026, 11) } }
        assertEquals(LocalDate.of(2026, 11, 1), s.selected)
    }

    @Test
    fun futureDaysNotSelectable() = runBlocking {
        awaitState { it.month == YearMonth.of(2026, 10) }
        vm.select(today.plusDays(1))
        val s = awaitState { it.month == YearMonth.of(2026, 10) }
        assertEquals(today, s.selected)
        assertTrue(s.cells.single { it?.date == today.plusDays(1) }!!.isFuture)
        vm.select(today.minusDays(4))
        assertEquals(today.minusDays(4), awaitState { it.selected != today }.selected)
    }

    @Test
    fun cellsCarryZoneDotsAndSummary() = runBlocking {
        val d = today.minusDays(1)
        db.dayDao().upsert(DayEntity(d, sleepSeconds = 27_000, sleepNeedMinutes = 480))
        db.scoreDao().upsert(ScoreEntity(d, 77, false, 0.9375, 100.0, 11.4, null, null, 1))
        for (i in 1L..4L) db.activityDao().upsert(activity(i, d, if (i == 1L) "Morning Run" else null))
        vm.select(d)
        val s = awaitState { st -> st.selected == d && st.summary.activityNames.isNotEmpty() }
        val cell = s.cells.single { it?.date == d }!!
        assertEquals(RecoveryZone.GREEN, cell.zone)
        assertEquals(77, cell.recovery)
        assertEquals(3, cell.activityCount) // capped for the dots
        assertEquals(4, cell.activities)
        assertNull(s.cells.single { it?.date == today }!!.zone)
        assertEquals(77, s.summary.recovery)
        assertEquals(94, s.summary.sleepPct)
        assertEquals(11.4, s.summary.strain!!, 0.0)
        assertEquals("Morning Run", s.summary.activityNames.first())
        assertEquals(4, s.summary.activityNames.size)
        assertEquals(77, s.monthAvgRecovery)
        assertEquals(4, s.monthActivities)
        assertEquals(27_000.0, s.monthAvgSleepSec!!, 0.0)
    }

    @Test
    fun monthNavigationKeepsDayAndStopsAtCurrentMonth() = runBlocking {
        // The placeholder state already reads October, and an empty load equals it (StateFlow drops the repeat), so seed a
        // score and wait for it: switching months while October's Room flows are still registering their observers can
        // leave the new month without an emission.
        db.scoreDao().upsert(ScoreEntity(today, 77, false, 0.9375, 100.0, 11.4, null, null, 1))
        awaitState { it.month == YearMonth.of(2026, 10) && it.summary.recovery == 77 }
        vm.nextMonth()
        assertEquals(YearMonth.of(2026, 10), vm.state.value.month)
        vm.prevMonth()
        val s = awaitState { it.month == YearMonth.of(2026, 9) }
        assertEquals(LocalDate.of(2026, 9, 24), s.selected)
    }

    private fun activity(id: Long, day: LocalDate, name: String?) = ActivityEntity(
        id,
        name,
        "running",
        SportFamily.RUN,
        LocalDateTime.of(day, java.time.LocalTime.of(7, 0)),
        day,
        Instant.ofEpochSecond(1_792_000_000L + id),
        3000.0,
        10_000.0,
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
    )
}
