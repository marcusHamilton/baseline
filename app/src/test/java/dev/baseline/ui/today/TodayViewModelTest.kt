package dev.baseline.ui.today

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.baseline.data.Repository
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.BodyBatteryDayEntity
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.HrPacking
import dev.baseline.data.db.LoadSource
import dev.baseline.data.db.RawResponse
import dev.baseline.data.db.ScoreEntity
import dev.baseline.data.db.TaskCounts
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.KeyStatus
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.Contributor
import dev.baseline.scores.FormState
import dev.baseline.scores.HrPoint
import dev.baseline.sync.SyncScheduler
import dev.baseline.sync.closeWorkManagerDb
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TodayViewModelTest {
    private val today = LocalDate.of(2026, 10, 24)
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private lateinit var prefs: UserPrefs
    private lateinit var vm: TodayViewModel
    private lateinit var repo: Repository
    private lateinit var scheduler: SyncScheduler

    private val scopes = ScopeCleanup()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = inMemoryDatabase(context)
        file = File.createTempFile("todayvm", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        prefs = UserPrefs(store)
        val vault = ApiKeyVault(store, { it }, { it }, Clock.systemUTC())
        repo = Repository(db, prefs, vault)
        scheduler = SyncScheduler(WorkManager.getInstance(context)) { false }
        vm = scopes.track(
            TodayViewModel(
                repo,
                scheduler,
                prefs,
                flowOf(TaskCounts(0, 0)),
                flowOf(false),
                flowOf(KeyStatus.Invalid),
                flowOf(true),
                today,
            ),
        )
    }

    @After
    fun tearDown() = runBlocking {
        scopes.close() // cancel VM and DataStore collectors before the database goes away
        db.close()
        closeWorkManagerDb(context)
        file.delete()
        Dispatchers.resetMain()
    }

    private suspend fun awaitState(predicate: (TodayUiState) -> Boolean): TodayUiState = withTimeout(10_000) { vm.state.first(predicate) }

    private fun day(date: LocalDate, hrv: Double? = 55.0, rhr: Int? = 49, sleepSec: Int? = 27_000, need: Int? = 480) = DayEntity(
        date, restingHr = rhr, respirationAvg = 13.0, stressAvg = 28, steps = 9_000, bodyBatteryWake = 74, sleepSeconds = sleepSec,
        sleepNeedMinutes = need, hrvLastNight = hrv,
    )

    /** 30 steady nights before [today], with slight variation so the SDs are not at their floors. */
    private fun seedHistory() = runBlocking {
        for (i in 1..30) db.dayDao().upsert(day(today.minusDays(i.toLong()), hrv = 55.0 + i % 3, rhr = 49 + i % 2))
    }

    @Test
    fun rowsFollowSavedOrderAndHideHidden() = runBlocking {
        prefs.setBaselineOrder(listOf("steps", "hrv"))
        prefs.setHiddenRows(setOf("steps"))
        val s = awaitState { st -> st.rows.isNotEmpty() && st.rows.none { it.id == "steps" } }
        assertEquals(listOf("hrv", "rhr", "sleep", "resp", "bb", "stress"), s.rows.map { it.id })
    }

    @Test
    fun savedOrderLeadsAndUnlistedIdsAreAppendedInDefaultOrder() = runBlocking {
        prefs.setBaselineOrder(listOf("stress", "hrv"))
        val s = awaitState { st -> st.rows.firstOrNull()?.id == "stress" }
        assertEquals(listOf("stress", "hrv", "rhr", "sleep", "resp", "bb", "steps"), s.rows.map { it.id })
    }

    @Test
    fun cannotGoPastToday() = runBlocking {
        val loaded = { s: TodayUiState -> s.rows.isNotEmpty() }
        vm.nextDay()
        assertEquals(today, awaitState { loaded(it) && it.date == today }.date)
        vm.prevDay()
        assertEquals(today.minusDays(1), awaitState { loaded(it) && it.date == today.minusDays(1) }.date)
        assertTrue(!vm.state.value.isToday)
        vm.nextDay()
        vm.nextDay()
        assertEquals(today, awaitState { loaded(it) && it.date == today }.date)
        vm.setDate(today.plusDays(5))
        assertEquals(today, awaitState { loaded(it) && it.date == today }.date)
    }

    private fun liveVm(flow: kotlinx.coroutines.flow.StateFlow<LocalDate>) = scopes.track(
        TodayViewModel(
            repo,
            scheduler,
            prefs,
            flowOf(TaskCounts(0, 0)),
            flowOf(false),
            flowOf(KeyStatus.Invalid),
            flowOf(true),
            flow,
        ),
    )

    @Test
    fun movesFromTheOldTodayToTheNewOneWhenTheDateAdvances() = runBlocking {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(today)
        val live = liveVm(flow)
        withTimeout(10_000) { live.state.first { !it.loading && it.date == today && it.isToday } }
        flow.value = today.plusDays(1)
        val s = withTimeout(10_000) { live.state.first { it.date == today.plusDays(1) } }
        assertTrue(s.isToday)
    }

    @Test
    fun aPastDayStaysButNextDayReachesTheNewToday() = runBlocking {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(today)
        val live = liveVm(flow)
        live.setDate(today.minusDays(2))
        withTimeout(10_000) { live.state.first { it.date == today.minusDays(2) } }
        flow.value = today.plusDays(1)
        val stayed = withTimeout(10_000) { live.state.first { it.date == today.minusDays(2) && !it.isToday } }
        assertEquals(today.minusDays(2), stayed.date)
        val rolled = withTimeout(10_000) { live.state.first { it.date == today.minusDays(2) && it.today == today.plusDays(1) } }
        assertTrue(!rolled.isToday) // the screen words "Synced ..." from this today, not from the clock
        repeat(3) { live.nextDay() }
        val s = withTimeout(10_000) { live.state.first { it.date == today.plusDays(1) } }
        assertTrue(s.isToday)
        live.nextDay()
        assertEquals(today.plusDays(1), withTimeout(10_000) { live.state.first { it.date == today.plusDays(1) } }.date)
    }

    @Test
    fun bodyBatteryLatestIsLastCurveSample() = runBlocking {
        val withCurve = today.minusDays(1)
        val t0 = 1_792_738_800L
        db.bodyBatteryDayDao().upsert(BodyBatteryDayEntity(withCurve, HrPacking.pack(listOf(HrPoint(t0, 22), HrPoint(t0 + 180, 60), HrPoint(t0 + 540, 83)))))
        db.dayDao().upsert(day(withCurve).copy(bodyBatteryCharged = 61))

        vm.setDate(withCurve)
        val s = awaitState { it.date == withCurve && it.bodyBattery.curve.isNotEmpty() }
        assertEquals(83, s.bodyBattery.latest)
        assertEquals(listOf(22, 60, 83), s.bodyBattery.curve.map { it.second })
        assertEquals(Instant.ofEpochSecond(t0 + 540), s.bodyBattery.curve.last().first)
        assertEquals(61, s.bodyBattery.charged)
        assertNull(s.bodyBattery.drained)

        vm.setDate(today) // no curve stored for today
        val empty = awaitState { it.date == today }
        assertNull(empty.bodyBattery.latest)
        assertTrue(empty.bodyBattery.curve.isEmpty())
    }

    @Test
    fun toggleBodyBatteryExpandsAndCollapses() = runBlocking {
        assertTrue(!awaitState { it.rows.isNotEmpty() }.bodyBattery.expanded)
        vm.toggleBodyBattery()
        assertTrue(awaitState { it.bodyBattery.expanded }.bodyBattery.expanded)
        vm.toggleBodyBattery()
        assertTrue(!awaitState { !it.bodyBattery.expanded }.bodyBattery.expanded)
    }

    @Test
    fun noRecoveryShowsReason() = runBlocking {
        // Nothing synced: no baseline yet.
        db.dayDao().upsert(day(today))
        val calibrating = awaitState { it.recoveryReason != null }
        assertNull(calibrating.recovery)
        assertEquals("calibrating — need 7 nights", calibrating.recoveryReason)

        // Baseline exists but the night has no sleep.
        seedHistory()
        db.dayDao().upsert(day(today, sleepSec = null))
        val noSleep = awaitState { it.recoveryReason == "no sleep recorded" }
        assertNull(noSleep.recovery)
        assertNull(noSleep.sleepPct)

        db.dayDao().upsert(day(today))
        val ok = awaitState { it.recovery != null }
        assertNull(ok.recoveryReason)
    }

    @Test
    fun recoveryAndStrainComeFromTheComputedResultAndScoreRow() = runBlocking {
        seedHistory()
        db.dayDao().upsert(day(today, hrv = 70.0))
        db.scoreDao().upsert(ScoreEntity(today, 77, false, 0.93, 120.0, 11.4, 14.0, 18.0, 1))
        val s = awaitState { it.recovery != null && it.strain != null }
        assertEquals(93, s.sleepPct)
        assertEquals(11.4, s.strain!!, 0.0)
        assertEquals(14.0..18.0, s.strainTarget)
        assertEquals("hrv", s.recovery!!.top.first().metric)
    }

    @Test
    fun rowsShowValueBaselineAndDirectionWithVerdict() = runBlocking {
        for (i in 1..30) db.dayDao().upsert(day(today.minusDays(i.toLong())))
        db.dayDao().upsert(day(today, hrv = 58.0, rhr = 47).copy(steps = 6_214))
        val rows = awaitState { st -> st.rows.firstOrNull { it.id == "hrv" }?.value == "58" }.rows.associateBy { it.id }
        rows.getValue("hrv").run {
            assertEquals("55 ms", baseline)
            assertEquals("▲ 5%", delta)
            assertEquals(true, better)
        }
        rows.getValue("rhr").run {
            assertEquals("49 bpm", baseline)
            assertEquals("▼ 2", delta)
            assertEquals(true, better)
        }
        rows.getValue("sleep").run {
            assertEquals("7h 30m", value)
            assertEquals("7h 30m", baseline)
            assertEquals("same", delta)
            assertNull(better)
        }
        rows.getValue("steps").run {
            assertEquals("6,214", value)
            assertEquals("9,000", baseline)
            assertEquals("so far", delta)
            assertNull(better)
        }
    }

    private fun seed(hrv: (Int) -> Double, rhr: (Int) -> Int) = runBlocking {
        for (i in 1..30) db.dayDao().upsert(day(today.minusDays(i.toLong()), hrv = hrv(i), rhr = rhr(i)))
    }

    /** The insight and the row must print the same number, however the rounding falls. */
    private fun assertRowMatchesInsight(s: TodayUiState, metric: String, rowId: String, pattern: Regex): String {
        val phrase = insightSentence(s.recovery!!.top)
        val fromInsight = pattern.find(phrase)?.groupValues?.get(1) ?: error("no $metric phrase in: $phrase")
        val delta = s.rows.first { it.id == rowId }.delta!!
        assertEquals("$metric row vs insight ($phrase)", fromInsight, Regex("(\\d+)").find(delta)!!.groupValues[1])
        return fromInsight
    }

    @Test
    fun hrvRowAndInsightAgreeOnThePercentage() = runBlocking {
        // Exact delta 98 / 90.4 - 1 = 8.4%: it must show 8 in both places, though the displayed baseline (90 ms) alone would imply 9.
        seed({ 90.4 }, { 49 })
        db.dayDao().upsert(day(today, hrv = 98.0))
        val s = awaitState { it.recovery != null && it.rows.firstOrNull { r -> r.id == "hrv" }?.value == "98" }
        assertEquals("8", assertRowMatchesInsight(s, "hrv", "hrv", Regex("HRV is (\\d+)% above")))
        assertEquals("▲ 8%", s.rows.first { it.id == "hrv" }.delta)
        assertEquals("90 ms", s.rows.first { it.id == "hrv" }.baseline)
        assertEquals("better", s.rows.first { it.id == "hrv" }.verdict)
    }

    @Test
    fun hrvRowAndInsightAgreeAcrossAHalfBoundary() = runBlocking {
        seed({ 100.0 }, { 49 })
        db.dayDao().upsert(day(today, hrv = 105.5)) // exact delta is 5.5 (give or take float noise)
        val s = awaitState { it.recovery != null && it.rows.firstOrNull { r -> r.id == "hrv" }?.value == "106" }
        assertTrue(assertRowMatchesInsight(s, "hrv", "hrv", Regex("HRV is (\\d+)% above")) in setOf("5", "6"))
    }

    @Test
    fun rhrRowAndInsightAgreeAcrossAHalfBoundary() = runBlocking {
        seed({ 55.0 }, { if (it % 2 == 0) 49 else 50 }) // mean 49.5
        db.dayDao().upsert(day(today, rhr = 47)) // exactly 2.5 below
        val s = awaitState { it.recovery != null && it.rows.firstOrNull { r -> r.id == "rhr" }?.value == "47" }
        assertEquals("3", assertRowMatchesInsight(s, "rhr", "rhr", Regex("(?i)resting HR is (\\d+) bpm below")))
        assertEquals("▼ 3", s.rows.first { it.id == "rhr" }.delta)
        assertEquals("better", s.rows.first { it.id == "rhr" }.verdict)
    }

    @Test
    fun withoutABaselineRowsFallBackToThePlainMean() = runBlocking {
        for (i in 1..3) db.dayDao().upsert(day(today.minusDays(i.toLong()), hrv = 50.0 + i * 10, rhr = 50))
        db.dayDao().upsert(day(today, hrv = 70.0, rhr = 50))
        val s = awaitState { st -> st.rows.firstOrNull { it.id == "hrv" }?.value == "70" }
        assertEquals("calibrating — need 7 nights", s.recoveryReason)
        assertEquals("70 ms", s.rows.first { it.id == "hrv" }.baseline) // plain mean of 60, 70, 80
    }

    @Test
    fun respirationBelowBaselineIsNormalAndAboveIsWorse() = runBlocking {
        for (i in 1..30) db.dayDao().upsert(day(today.minusDays(i.toLong())))
        db.dayDao().upsert(day(today).copy(respirationAvg = 12.0))
        val low = awaitState { st -> st.rows.firstOrNull { it.id == "resp" }?.value == "12.0" }.rows.first { it.id == "resp" }
        assertEquals("▼ 1.0", low.delta)
        assertEquals("normal", low.verdict)
        assertNull(low.better)
        db.dayDao().upsert(day(today).copy(respirationAvg = 14.5))
        val high = awaitState { st -> st.rows.firstOrNull { it.id == "resp" }?.value == "14.5" }.rows.first { it.id == "resp" }
        assertEquals("▲ 1.5", high.delta)
        assertEquals("worse", high.verdict)
        assertEquals(false, high.better)
    }

    @Test
    fun zeroStepDaysDoNotLowerTheStepsBaseline() = runBlocking {
        for (i in 1..10) db.dayDao().upsert(day(today.minusDays(i.toLong())).copy(steps = 10_000))
        for (i in 11..20) db.dayDao().upsert(day(today.minusDays(i.toLong())).copy(steps = 0))
        db.dayDao().upsert(day(today.minusDays(1)).copy(steps = 8_000))
        val s = awaitState { st -> st.rows.firstOrNull { it.id == "steps" }?.baseline == "9,800" }
        assertEquals("9,800", s.rows.first { it.id == "steps" }.baseline) // mean of ten non-zero days: 9 x 10,000 and 8,000
    }

    @Test
    fun stateIsLoadingUntilTheFirstEmissionAndSyncedComesFromPrefs() = runBlocking {
        assertTrue(vm.state.value.loading)
        val at = Instant.parse("2026-10-22T14:41:00Z")
        prefs.setLastSyncedAt(at)
        val s = awaitState { !it.loading && it.lastSynced == at }
        assertTrue(!s.loading)
    }

    @Test
    fun missingValuesShowDashNotNaN() = runBlocking {
        val s = awaitState { it.rows.isNotEmpty() }
        assertEquals(
            listOf("HRV (overnight)", "Resting heart rate", "Sleep", "Respiration (sleep)", "Body Battery at wake", "Avg stress", "Steps"),
            s.rows.map { it.label },
        )
        assertTrue(s.rows.all { it.value == "—" && it.baseline == null && it.delta == null })
        assertTrue(s.rows.none { "NaN" in it.value })
    }

    @Test
    fun activitiesCarryMetaAndStrain() = runBlocking {
        val start = LocalDateTime.of(2026, 10, 24, 7, 5)
        db.activityDao().upsert(
            ActivityEntity(
                9, "Morning Run", "running", SportFamily.RUN, start, today, Instant.ofEpochSecond(1_792_738_800),
                3134.0, 10_210.0, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            ),
        )
        db.activityScoreDao().upsert(ActivityScoreEntity(9, 80.0, LoadSource.HR, 9.8))
        val metric = awaitState { it.activities.isNotEmpty() && it.activities.single().strain != null }.activities.single()
        assertEquals("7:05 AM · 10.21 km · 52:14", metric.meta)
        assertEquals(9.8, metric.strain!!, 0.0)
        assertEquals(SportFamily.RUN, metric.family)

        db.rawDao().upsert(RawResponse("PROFILE", "-", """{"measurementSystem":"statute_us"}""".toByteArray(), Instant.ofEpochSecond(1)))
        assertEquals("7:05 AM · 6.34 mi · 52:14", awaitState { "mi" in it.activities.single().meta }.activities.single().meta)
    }

    @Test
    fun insightTextFromContributors() {
        assertEquals(
            "HRV is 6% above your 30-day baseline and resting HR is 2 bpm below.",
            insightSentence(listOf(Contributor("hrv", 1.0, 6.2), Contributor("rhr", 1.0, -2.0))),
        )
        assertEquals(
            "Sleep was 12 points above target and breathing rate is 0.8 brpm higher.",
            insightSentence(listOf(Contributor("sleep", 1.0, 12.4), Contributor("resp", -1.0, 0.8))),
        )
        assertEquals(
            "HRV is 18% below your 30-day baseline and resting HR is 5 bpm above.",
            insightSentence(listOf(Contributor("hrv", -2.0, -18.0), Contributor("rhr", -1.0, 5.0))),
        )
        assertEquals("Breathing rate is 0.3 brpm lower.", insightSentence(listOf(Contributor("resp", 0.0, -0.3))))
        assertEquals("HRV is at your 30-day baseline.", insightSentence(listOf(Contributor("hrv", 0.0, 0.2))))
        assertEquals("", insightSentence(emptyList()))
    }

    @Test
    fun zoneTitles() {
        assertEquals(
            listOf("Recovered", "Maintaining", "Under-recovered"),
            dev.baseline.scores.RecoveryZone.entries.map(::zoneTitle),
        )
    }

    private fun scoreRow(d: LocalDate, load: Double) = ScoreEntity(d, null, false, null, load, 0.0, null, null, 1)

    private fun loadScores(first: LocalDate, days: Int, load: (Int) -> Double) = (0 until days).map { scoreRow(first.plusDays(it.toLong()), load(it)) }

    @Test
    fun trainingLoadForAProductiveDay() {
        val first = today.minusDays(209)
        val ui = buildTrainingLoadUi(today, loadScores(first, 210) { if (it < 200) 100.0 else 140.0 }, first, DayOfWeek.MONDAY, 100.0, false)!!
        assertEquals(FormState.PRODUCTIVE, ui.day.state)
        assertNotNull(ui.budget)
        assertFalse(ui.expanded)
    }

    @Test
    fun noTrainingLoadBeforeTheFirstScore() {
        assertNull(buildTrainingLoadUi(today, loadScores(today.plusDays(5), 1) { 100.0 }, today.plusDays(5), DayOfWeek.MONDAY, 100.0, false))
    }

    @Test
    fun calibratingHasNoBudget() {
        val first = today.minusDays(10)
        val ui = buildTrainingLoadUi(today, loadScores(first, 11) { 100.0 }, first, DayOfWeek.MONDAY, 100.0, false)!!
        assertEquals(FormState.CALIBRATING, ui.day.state)
        assertEquals(32, ui.day.calibratingDaysLeft)
        assertNull(ui.budget)
    }

    @Test
    fun pastDayUsesThatDatesWeek() {
        val wed = today.with(TemporalAdjusters.previous(DayOfWeek.WEDNESDAY))
        val first = wed.minusDays(200)
        val ui = buildTrainingLoadUi(wed, loadScores(first, 201) { 100.0 }, first, DayOfWeek.MONDAY, 100.0, false)!!
        assertEquals(wed.minusDays(2), ui.budget!!.weekStart)
        assertEquals(4, ui.budget!!.daysLeft)
    }

    @Test
    fun toggleTrainingLoadFlipsExpandedAndFollowsTheDate() = runBlocking {
        for (i in 0..60) {
            val d = today.minusDays(i.toLong())
            db.dayDao().upsert(day(d))
            db.scoreDao().upsert(scoreRow(d, 100.0))
        }
        assertFalse(awaitState { it.trainingLoad != null }.trainingLoad!!.expanded)
        vm.toggleTrainingLoad()
        assertTrue(awaitState { it.trainingLoad?.expanded == true }.trainingLoad!!.expanded)
        vm.prevDay()
        assertEquals(today.minusDays(1), awaitState { it.trainingLoad?.day?.date == today.minusDays(1) }.trainingLoad!!.day.date)
    }
}
