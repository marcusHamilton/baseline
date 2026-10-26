package dev.baseline.ui.activities

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.Repository
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.data.db.LoadSource
import dev.baseline.data.db.RawResponse
import dev.baseline.data.db.StrengthSetEntity
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.prefs.UserPrefs
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.ui.format.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal val TODAY: LocalDate = LocalDate.of(2026, 10, 24) // a Saturday

internal fun act(
    id: Long,
    typeKey: String = "running",
    day: LocalDate = TODAY,
    time: LocalTime = LocalTime.of(7, 5),
    durationSec: Double = 3134.0,
    distanceM: Double? = 10_210.0,
    avgPower: Double? = null,
    name: String? = "Activity $id",
) = ActivityEntity(
    id, name, typeKey, SportFamily.of(typeKey), LocalDateTime.of(day, time), day, Instant.EPOCH.plusSeconds(id), durationSec,
    distanceM, avgHr = null, maxHr = null, zone1Sec = null, zone2Sec = null, zone3Sec = null, zone4Sec = null, zone5Sec = null,
    garminLoad = null, elevationGainM = null, avgPower = avgPower, normPower = null, fastestSplitsJson = null, fitPath = null,
    hrCoverage = null, poolLengthM = null,
)

internal fun build(
    activities: List<ActivityEntity>,
    filter: SportFamily? = null,
    strains: Map<Long, Double> = emptyMap(),
    sets: Map<Long, Int> = emptyMap(),
    firstDay: DayOfWeek = DayOfWeek.MONDAY,
    imperial: Boolean = false,
) = buildActivitiesState(filter, activities, strains, sets, firstDay, TODAY, Formatters(imperial))

private fun ActivitiesUiState.metaOf(id: Long) = days.flatMap { it.items }.single { it.id == id }.meta

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ActivitiesViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private lateinit var vm: ActivitiesViewModel

    private val scopes = ScopeCleanup()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context) { AppDatabase_Impl() }.allowMainThreadQueries().build()
        file = File.createTempFile("actvm", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        vm = scopes.track(ActivitiesViewModel(Repository(db, UserPrefs(store), ApiKeyVault(store, { it }, { it }, Clock.systemUTC())), TODAY))
    }

    @After
    fun tearDown() = runBlocking {
        scopes.close() // cancel VM and DataStore collectors before the database goes away
        db.close()
        file.delete()
        Dispatchers.resetMain()
    }

    private suspend fun awaitState(predicate: (ActivitiesUiState) -> Boolean) = withTimeout(10_000) { vm.state.first(predicate) }

    @Test
    fun groupsByLocalDayNewestFirst() {
        val yesterday = TODAY.minusDays(1)
        val s = build(listOf(
            act(1, day = yesterday, time = LocalTime.of(18, 0)),
            act(2, time = LocalTime.of(7, 0)),
            act(3, day = yesterday, time = LocalTime.of(8, 0)),
            act(4, time = LocalTime.of(17, 30)),
            act(5, day = TODAY.minusDays(5)),
        ))
        assertEquals(listOf(TODAY, yesterday, TODAY.minusDays(5)), s.days.map { it.date })
        assertEquals(listOf(4L, 2L), s.days[0].items.map { it.id })
        assertEquals(listOf(1L, 3L), s.days[1].items.map { it.id })
    }

    @Test
    fun filterBySport() {
        val all = listOf(act(1), act(2, "road_biking"), act(3, "strength_training"), act(4, "lap_swimming"), act(5, "hiking"), act(6, "treadmill_running"))
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), build(all).days.flatMap { it.items }.map { it.id }.toSet())
        assertEquals(setOf(1L, 6L), build(all, SportFamily.RUN).days.flatMap { it.items }.map { it.id }.toSet())
        assertEquals(listOf(5L), build(all, SportFamily.OTHER).days.flatMap { it.items }.map { it.id })
        assertEquals(SportFamily.RIDE, build(all, SportFamily.RIDE).filter)
        assertEquals(emptyList<DayGroup>(), build(all.take(1), SportFamily.SWIM).days)
    }

    @Test
    fun metaFormatsByFamily() {
        val s = build(
            listOf(
                act(1),
                act(2, "road_biking", durationSec = 5660.0, distanceM = 42_600.0, avgPower = 196.4),
                act(3, "lap_swimming", durationSec = 2468.0, distanceM = 2000.0),
                act(4, "strength_training", durationSec = 2760.0, distanceM = null),
                act(5, "hiking", durationSec = 7960.0, distanceM = 7500.0),
                act(6, "yoga", durationSec = 1800.0, distanceM = 0.0),
                act(7, "running", durationSec = 3930.0, distanceM = 12_000.0),
            ),
            sets = mapOf(4L to 18),
        )
        assertEquals("10.21 km · 52:14 · 5:07 /km", s.metaOf(1))
        assertEquals("42.6 km · 1:34:20 · 27.1 km/h · 196 W avg", s.metaOf(2))
        assertEquals("2,000 m · 41:08 · 2:03 /100m", s.metaOf(3))
        assertEquals("46 min · 18 sets", s.metaOf(4))
        assertEquals("7.50 km · 2:12:40", s.metaOf(5))
        assertEquals("30:00", s.metaOf(6))
        assertEquals("12.00 km · 1:05:30 · 5:28 /km", s.metaOf(7))
    }

    @Test
    fun imperialFollowsProfile() = runBlocking {
        db.activityDao().upsert(act(1))
        db.activityDao().upsert(act(2, "lap_swimming", durationSec = 2468.0, distanceM = 2000.0))
        assertEquals("10.21 km · 52:14 · 5:07 /km", awaitState { it.days.isNotEmpty() }.metaOf(1))
        db.rawDao().upsert(RawResponse("PROFILE", "-", """{"measurementSystem":"statute_us"}""".toByteArray(), Instant.EPOCH))
        val s = awaitState { it.imperial }
        assertEquals("6.34 mi · 52:14 · 8:14 /mi", s.metaOf(1))
        assertEquals("2,187 yd · 41:08 · 1:53 /100yd", s.metaOf(2))
        assertEquals("6.34 mi · 52:14 · 8:14 /mi", build(listOf(act(1)), imperial = true).metaOf(1))
    }

    @Test
    fun strengthMetaCountsActiveSetsOnly() = runBlocking {
        db.activityDao().upsert(act(1, "strength_training", durationSec = 2760.0, distanceM = null))
        db.activityDao().upsert(act(2, "strength_training", durationSec = 1800.0, distanceM = null))
        db.strengthSetDao().insertAll(
            (0 until 5).map { i ->
                StrengthSetEntity(activityId = 1, setIndex = i, startOffsetSec = null, exerciseName = null, setType = if (i % 2 == 0) "ACTIVE" else "REST", category = null, reps = null, weightKg = null, durationSec = null)
            },
        )
        val s = awaitState { st -> st.days.flatMap { it.items }.any { it.id == 1L && it.meta.contains("sets") } }
        assertEquals("46 min · 3 sets", s.metaOf(1))
        assertEquals("30 min", s.metaOf(2)) // no FIT yet: no sets, no separator
        assertEquals("46 min · 1 set", build(listOf(act(3, "strength_training", durationSec = 2760.0, distanceM = null)), sets = mapOf(3L to 1)).metaOf(3))
    }

    @Test
    fun weekSummaryRespectsFirstDayOfWeek() {
        val sunday = LocalDate.of(2026, 10, 18)
        val list = listOf(
            act(1, day = sunday, durationSec = 3600.0, distanceM = 10_000.0),
            act(2, day = TODAY, durationSec = 1800.0, distanceM = null),
            act(3, day = LocalDate.of(2026, 10, 17), durationSec = 999.0, distanceM = 99_000.0),
        )
        val strains = mapOf(1L to 10.0, 2L to 6.0, 3L to 20.0)
        val monday = build(list, strains = strains, firstDay = DayOfWeek.MONDAY).week
        assertEquals(WeekSummary(1, Duration.ofSeconds(1800), 0.0, 6.0), monday)
        val sun = build(list, strains = strains, firstDay = DayOfWeek.SUNDAY).week
        assertEquals(WeekSummary(2, Duration.ofSeconds(5400), 10.0, 8.0), sun)
        val none = build(emptyList()).week
        assertEquals(WeekSummary(0, Duration.ZERO, 0.0, null), none)
        assertNull(build(listOf(act(9)), strains = emptyMap()).week.avgStrain)
        // The summary is for the week, whatever chip is selected.
        assertEquals(2, build(list, filter = SportFamily.SWIM, firstDay = DayOfWeek.SUNDAY).week.count)
    }

    @Test
    fun missingPartsAreOmitted() {
        val f = Formatters(imperial = false)
        assertEquals("52:14", f.meta(SportFamily.RUN, null, 3134.0, null, null))
        assertEquals("52:14", f.meta(SportFamily.RUN, 0.0, 3134.0, null, null))
        assertEquals("10.21 km", f.meta(SportFamily.RUN, 10_210.0, 0.0, null, null))
        assertEquals("10.21 km", f.meta(SportFamily.RUN, 10_210.0, Double.NaN, null, null))
        assertEquals("", f.meta(SportFamily.RUN, Double.NaN, null, null, null))
        assertEquals("42.6 km · 1:34:20 · 27.1 km/h", f.meta(SportFamily.RIDE, 42_600.0, 5660.0, 0.0, null))
        assertEquals("1:34:20 · 196 W avg", f.meta(SportFamily.RIDE, null, 5660.0, 196.0, null))
        assertEquals("2,000 m", f.meta(SportFamily.SWIM, 2000.0, null, null, null))
        assertEquals("46 min", f.meta(SportFamily.STRENGTH, null, 2760.0, null, 0))
        assertEquals("18 sets", f.meta(SportFamily.STRENGTH, null, null, null, 18))
        assertEquals("", f.meta(SportFamily.OTHER, 0.0, null, null, null))
    }

    @Test
    fun filterLivesInTheViewModel() = runBlocking {
        db.activityDao().upsert(act(1))
        db.activityDao().upsert(act(2, "road_biking"))
        db.activityScoreDao().upsert(ActivityScoreEntity(1, 100.0, LoadSource.HR, 9.8))
        assertEquals(2, awaitState { it.days.sumOf { d -> d.items.size } == 2 }.days[0].items.size)
        assertEquals(9.8, vm.state.value.days[0].items.single { it.id == 1L }.strain!!, 0.0)
        vm.setFilter(SportFamily.RIDE)
        assertEquals(listOf(2L), awaitState { it.filter == SportFamily.RIDE }.days.flatMap { it.items }.map { it.id })
        vm.setFilter(null)
        assertEquals(2, awaitState { it.filter == null }.days.sumOf { it.items.size })
    }
}
