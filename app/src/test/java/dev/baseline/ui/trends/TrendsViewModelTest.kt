package dev.baseline.ui.trends

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.Repository
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.FormState
import dev.baseline.scores.TrainingLoad
import dev.baseline.scores.TrainingLoadDay
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.testutil.inMemoryDatabase
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrendsViewModelTest {
    private val today = LocalDate.of(2026, 10, 24)
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private lateinit var vm: TrendsViewModel

    private val scopes = ScopeCleanup()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = inMemoryDatabase(context)
        file = File.createTempFile("trendsvm", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        vm = scopes.track(TrendsViewModel(Repository(db, UserPrefs(store), ApiKeyVault(store, { it }, { it }, Clock.systemUTC())), today))
    }

    @After
    fun tearDown() = runBlocking {
        scopes.close() // cancel VM and DataStore collectors before the database goes away
        db.close()
        file.delete()
        Dispatchers.resetMain()
    }

    private suspend fun awaitState(predicate: (TrendsUiState) -> Boolean) = withTimeout(10_000) { vm.state.first(predicate) }

    @Test fun setRangeReWiresTheFlowAndWidensTheWindow() = runBlocking {
        db.dayDao().upsert(DayEntity(today.minusDays(6), restingHr = 50))
        db.dayDao().upsert(DayEntity(today.minusDays(7), restingHr = 44))
        awaitState { it.range == TrendRange.D30 }
        vm.setRange(TrendRange.D7)
        val d7 = awaitState { it.range == TrendRange.D7 && it.rhr.any { r -> r.second != null } }
        assertEquals(7, d7.rhr.size)
        assertEquals(50, d7.rhr.first { it.first == today.minusDays(6) }.second)
        assertEquals(null, d7.rhr.firstOrNull { it.first == today.minusDays(7) })
        vm.setRange(TrendRange.D30)
        val d30 = awaitState { it.range == TrendRange.D30 && it.rhr.count { r -> r.second != null } == 2 }
        assertEquals(30, d30.rhr.size)
        assertEquals(44, d30.rhr.first { it.first == today.minusDays(7) }.second)
    }

    @Test fun rangeEndFollowsTheTodayFlow() = runBlocking {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(today)
        val store: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                scope = scopes.storeScope,
            ) { File.createTempFile("trendsvm2", ".preferences_pb").also { it.delete() } }
        val live = scopes.track(TrendsViewModel(Repository(db, UserPrefs(store), ApiKeyVault(store, { it }, { it }, Clock.systemUTC())), flow))
        withTimeout(10_000) { live.state.first { it.rhr.last().first == today } }
        flow.value = today.plusDays(1)
        val s = withTimeout(10_000) { live.state.first { it.rhr.last().first == today.plusDays(1) } }
        assertEquals(30, s.rhr.size)
        assertEquals(today.minusDays(28), s.rhr.first().first)
    }

    @Test fun headersShowLatestValueWithReference() {
        val s =
            build(
                TrendRange.D7,
                listOf(
                    DayEntity(today, restingHr = 47, hrvLastNight = 58.0, hrvWeeklyAvg = 55.0),
                    DayEntity(today.minusDays(1), restingHr = 51, hrvLastNight = 62.0, hrvWeeklyAvg = 54.0),
                    DayEntity(today.minusDays(2), restingHr = 49, hrvLastNight = 60.0),
                ),
            )
        assertEquals("58 ms · baseline 55", s.hrvHeader())
        assertEquals("47 bpm · avg 49", s.rhrHeader())
    }

    @Test fun latestSkipsNullDays_baselineFallsBackToBandMidpointThenAverage() {
        val band =
            build(
                TrendRange.D7,
                listOf(DayEntity(today.minusDays(1), restingHr = 52, hrvLastNight = 60.0, hrvBalancedLow = 50.0, hrvBalancedUpper = 60.0), DayEntity(today)),
            )
        assertEquals("60 ms · baseline 55", band.hrvHeader())
        assertEquals("52 bpm · avg 52", band.rhrHeader())
        val none = build(TrendRange.D7, listOf(DayEntity(today.minusDays(3), hrvLastNight = 40.0), DayEntity(today.minusDays(2), hrvLastNight = 50.0)))
        assertEquals("50 ms · avg 45", none.hrvHeader())
    }

    @Test fun headersWithNoDataShowDashes() {
        val s = build(TrendRange.D30)
        assertEquals(TrendLatest(null, null, null), s.latest)
        assertEquals("— · avg —", s.hrvHeader())
        assertEquals("— · avg —", s.rhrHeader())
    }

    private fun score(d: LocalDate, recovery: Int?) = ScoreEntity(d, recovery, false, null, 0.0, 0.0, null, null, 1)

    private fun build(range: TrendRange, days: List<DayEntity> = emptyList(), scores: List<ScoreEntity> = emptyList()) =
        buildTrendsState(range, today, days, scores)

    @Test fun rangeSelectsDays() {
        listOf(TrendRange.D7 to 7, TrendRange.D30 to 30, TrendRange.D90 to 90).forEach { (r, n) ->
            val s = build(r)
            assertEquals(n, s.recovery.size)
            assertEquals(today, s.recovery.last().first)
            assertEquals(today.minusDays(n - 1L), s.recovery.first().first)
            assertEquals(n, s.hrv.size)
            assertEquals(n, s.rhr.size)
            assertEquals(n, s.sleepVsNeed.size)
        }
        assertEquals(TrendRange.D30, TrendRange.Default)
    }

    @Test fun missingDaysAreNullNotZero() {
        val s = build(TrendRange.D7, listOf(DayEntity(today, restingHr = 48)), listOf(score(today.minusDays(1), 70)))
        assertEquals(listOf(null, null, null, null, null, 70, null), s.recovery.map { it.second })
        assertEquals(48, s.rhr.last().second)
        assertEquals(List(6) { null }, s.rhr.dropLast(1).map { it.second })
        assertEquals(Triple(today.minusDays(3), null, null), s.sleepVsNeed[3])
        assertNull(s.hrv.last().second)
    }

    @Test fun averagesIgnoreNulls() {
        val s =
            build(
                TrendRange.D7,
                listOf(
                    DayEntity(today, restingHr = 50, hrvLastNight = 60.4, sleepSeconds = 7 * 3600),
                    DayEntity(today.minusDays(1), restingHr = 46, hrvLastNight = 56.0, sleepSeconds = 8 * 3600),
                    DayEntity(today.minusDays(2)),
                ),
                listOf(score(today, 80), score(today.minusDays(1), 41), score(today.minusDays(2), null)),
            )
        assertEquals(61, s.averages.recovery) // (80 + 41) / 2 = 60.5, rounded half up
        assertEquals(58, s.averages.hrv) // (60 + 56) / 2, each rounded first
        assertEquals(48, s.averages.rhr)
        assertEquals(Duration.ofMinutes(450), s.averages.sleep)
        val empty = build(TrendRange.D7).averages
        assertEquals(TrendAverages(null, null, null, null), empty)
    }

    @Test fun hrvBandOnlyWhenBothBoundsPresent() {
        val s =
            build(
                TrendRange.D7,
                listOf(
                    DayEntity(today, hrvBalancedLow = 48.4, hrvBalancedUpper = 62.6),
                    DayEntity(today.minusDays(1), hrvBalancedLow = 48.0),
                    DayEntity(today.minusDays(2), hrvBalancedUpper = 62.0),
                ),
            )
        assertEquals(listOf(today to 48..63), s.hrvBand)
    }

    @Test fun sleepVsNeedPairsDurations() {
        val s =
            build(
                TrendRange.D7,
                listOf(DayEntity(today, sleepSeconds = 26_400, sleepNeedMinutes = 480), DayEntity(today.minusDays(1), sleepSeconds = 25_000)),
            )
        assertEquals(Triple(today, Duration.ofSeconds(26_400), Duration.ofMinutes(480)), s.sleepVsNeed.last())
        assertEquals(Triple(today.minusDays(1), Duration.ofSeconds(25_000), null), s.sleepVsNeed[5])
    }

    private fun loadScore(d: LocalDate, load: Double, recovery: Int? = null) = ScoreEntity(d, recovery, false, null, load, 0.0, null, null, 1)

    @Test fun warmUpDoesNotLeakIntoExistingSeries() {
        val from = today.minusDays(29)
        val inRange = (0L..29).map { loadScore(from.plusDays(it), 100.0, 60 + (it % 5).toInt()) }
        val warmUp = (1L..TrainingLoad.WARM_UP_DAYS).map { loadScore(from.minusDays(it), 100.0, 10) }
        val s = buildTrendsState(TrendRange.D30, today, emptyList(), warmUp + inRange, from.minusDays(TrainingLoad.WARM_UP_DAYS), 100.0)
        assertEquals(30, s.recovery.size)
        assertEquals(from, s.recovery.first().first)
        assertEquals(buildTrendsState(TrendRange.D30, today, emptyList(), inRange).averages, s.averages)
    }

    @Test fun trainingLoadCoversTheRange() {
        val first = today.minusDays(200)
        val s = buildTrendsState(TrendRange.D30, today, emptyList(), (0L..200).map { loadScore(first.plusDays(it), 100.0) }, first, 100.0)
        assertEquals(30, s.trainingLoad.size)
        assertEquals(today, s.trainingLoad.last()!!.date)
        assertEquals(FormState.MAINTAINING, s.trainingLoad.last()!!.state)
    }

    @Test fun daysBeforeTheFirstScoreAreGaps() {
        val first = today.minusDays(10)
        val s = buildTrendsState(TrendRange.D30, today, emptyList(), (0L..10).map { loadScore(first.plusDays(it), 100.0) }, first, 100.0)
        assertTrue(s.trainingLoad.take(19).all { it == null })
        assertEquals(first, s.trainingLoad[19]!!.date)
    }

    @Test fun trainingLoadHeaderAndDescription() {
        val dates = (0L..29).map { today.minusDays(29 - it) }
        val series = dates.mapIndexed { i, d ->
            val last = i == 29
            TrainingLoadDay(
                d,
                if (last) 64.0 else 52.0,
                if (last) 76.0 else 40.0,
                if (last) -18 else 23,
                if (last) FormState.PRODUCTIVE else FormState.DETRAINING,
                null,
            )
        }
        val s = buildTrendsState(TrendRange.D30, today, emptyList(), emptyList()).copy(trainingLoad = series)
        assertEquals("Productive · form \u221218%", s.trainingLoadHeader())
        assertEquals(
            "Training load over the last 30 days. Fitness rose from 52 to 64, fatigue from 40 to 76. Now Productive, form minus 18 percent.",
            s.trainingLoadDescription(),
        )
        val flat = s.copy(trainingLoad = series.map { it.copy(fitness = 64.2, fatigue = 30.0) })
        assertTrue(flat.trainingLoadDescription(), flat.trainingLoadDescription().contains("Fitness held at 64, fatigue held at 30."))
        val cal = s.copy(trainingLoad = series.map { it.copy(formPct = null, state = FormState.CALIBRATING, calibratingDaysLeft = 3) })
        assertEquals("Calibrating", cal.trainingLoadHeader())
        assertEquals("\u2014", s.copy(trainingLoad = List(30) { null }).trainingLoadHeader())
    }

    @Test fun lineScaleIgnoresAnEmptySecondary() {
        assertEquals(40f..80f, lineScale(listOf(50, null, 80), emptyList(), listOf(null, 40..60, null), null))
        assertEquals(10f..80f, lineScale(listOf(50, null, 80), listOf(10, 20, null), emptyList(), null))
    }
}
