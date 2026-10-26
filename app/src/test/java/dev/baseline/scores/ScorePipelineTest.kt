package dev.baseline.scores

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.HrDayEntity
import dev.baseline.data.db.HrPacking
import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.TaskStatus
import dev.baseline.data.db.ActivityStreamEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.LoadSource
import dev.baseline.data.db.ScoreEntity
import dev.baseline.fit.FitReader
import dev.baseline.fit.fitFixture
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.prefs.BanisterSet
import dev.baseline.prefs.UserPrefs
import dev.baseline.sync.RawIngest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class ScorePipelineTest {
    private lateinit var db: AppDatabase
    private lateinit var prefs: UserPrefs
    private val oct24 = LocalDate.of(2026, 10, 24)
    private val oct20 = LocalDate.of(2026, 10, 20)

    private fun clockAt(d: LocalDate) = Clock.fixed(d.atTime(8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private fun pipeline(today: LocalDate = oct24) = ScorePipeline(db, prefs, clockAt(today))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(ApplicationProvider.getApplicationContext()) { AppDatabase_Impl() }
            .allowMainThreadQueries()
            .build()
        val file = File.createTempFile("prefs", ".preferences_pb").also { it.delete() }
        prefs = UserPrefs(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { file })
    }

    @After
    fun tearDown() = db.close()

    private fun activity(id: Long, day: LocalDate, family: SportFamily = SportFamily.RUN, garmin: Double? = 20.0, duration: Double = 600.0) =
        ActivityEntity(
            id, "a$id", "running", family, LocalDateTime.of(day, java.time.LocalTime.of(7, 0)), day,
            day.atTime(14, 0).toInstant(ZoneOffset.UTC), duration, null, null, null, null, null, null, null, null,
            garmin, null, null, null, null, null, null, null,
        )

    private suspend fun zones(max: Int = 205, rest: Int = 44) = RawIngest(db).apply(
        GarminEndpoint.HR_ZONES, "-",
        """[{"sport":"DEFAULT","maxHeartRateUsed":$max,"restingHeartRateUsed":$rest,"zone1Floor":103}]""".toByteArray(),
    )

    /** Stores the strength FIT stream under activity [id]; returns the FIT's duration. */
    private suspend fun withStrengthStream(id: Long, day: LocalDate, family: SportFamily, garmin: Double?): Double {
        val fit = FitReader.read(fitFixture("strength"))
        db.activityDao().upsert(activity(id, day, family, garmin, fit.durationSec))
        db.activityStreamDao().upsert(ActivityStreamEntity(id, fit.streams.pack()))
        return fit.durationSec
    }

    private fun assertNoNaN(s: ScoreEntity) {
        listOfNotNull(s.sleepPerformance, s.dayLoad, s.strain, s.strainTargetLow, s.strainTargetHigh)
            .forEach { assertFalse("NaN in $s", it.isNaN()) }
    }

    @Test
    fun aFailingRecordsRefreshDoesNotFailTheRecompute() = runBlocking {
        db.dayDao().upsert(DayEntity(oct24))
        ScorePipeline(db, prefs, clockAt(oct24)) { error("boom") }.recompute(oct24)
        assertNotNull(db.scoreDao().get(oct24))
    }

    @Test
    fun cancellationStillPropagatesFromTheRecordsRefresh() {
        db.dayDao().let { runBlocking { it.upsert(DayEntity(oct24)) } }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { ScorePipeline(db, prefs, clockAt(oct24)) { throw kotlinx.coroutines.CancellationException("stop") }.recompute(oct24) }
        }
    }

    @Test
    fun recomputeFillsBestEfforts() = runBlocking {
        db.dayDao().upsert(DayEntity(oct20))
        db.activityDao().upsert(activity(1, oct20).copy(fastestSplitsJson = """{"fastestSplit_5000":1500.0}"""))
        pipeline().recompute(oct20)
        val r = db.bestEffortDao().forSport(SportFamily.RUN).single()
        assertEquals("5000m", r.effort); assertEquals(1500.0, r.valueSec, 0.0); assertEquals(1L, r.activityId)
    }

    @Test
    fun dayWithNoDataScoresZeroStrainNoRecovery() = runBlocking {
        db.dayDao().upsert(DayEntity(oct24))
        pipeline().recompute(oct24)
        val s = db.scoreDao().get(oct24)!!
        assertEquals(0.0, s.strain, 0.0)
        assertEquals(0.0, s.dayLoad, 0.0)
        assertNull(s.recovery)
        assertNull(s.sleepPerformance)
        assertNull(s.strainTargetLow)
        assertNull(s.strainTargetHigh)
        assertFalse(s.recoveryPartial)
        assertEquals("unstored all-zero calibration", 0, s.calibrationVersion)
        assertNull(prefs.strainCalibration.first())
        assertNoNaN(s)
    }

    @Test
    fun pastDaysKeepCalibrationVersion() = runBlocking {
        db.dayDao().upsert(DayEntity(oct20)); db.dayDao().upsert(DayEntity(oct24))
        db.activityDao().upsert(activity(1, oct20, garmin = 100.0))
        val p = pipeline()
        p.recalibrateIfDue(oct24, true)
        p.recompute(oct20)
        assertEquals(1, db.scoreDao().get(oct20)!!.calibrationVersion)
        val tau1 = prefs.strainCalibration.first()!!.tau

        // A new month recalibrates; recomputing only the newest day leaves older rows alone.
        val nov1 = LocalDate.of(2026, 11, 1)
        db.dayDao().upsert(DayEntity(nov1))
        db.activityDao().upsert(activity(2, oct24, garmin = 400.0))
        val later = pipeline(nov1)
        later.recalibrateIfDue(nov1, true)
        assertEquals(2, prefs.strainCalibration.first()!!.version)
        assertTrue(prefs.strainCalibration.first()!!.tau > tau1)
        later.recompute(nov1)
        assertEquals(1, db.scoreDao().get(oct20)!!.calibrationVersion)
        assertEquals(1, db.scoreDao().get(oct24)!!.calibrationVersion)
        assertEquals(2, db.scoreDao().get(nov1)!!.calibrationVersion)

        // Same month and already calibrated: nothing changes. Recalculate history re-scores everything.
        later.recalibrateIfDue(nov1.plusDays(1), true)
        assertEquals(2, prefs.strainCalibration.first()!!.version)
        later.recalculateHistory()
        assertEquals(2, db.scoreDao().get(oct20)!!.calibrationVersion)
    }

    @Test
    fun recalibratesOncePerMonthOnTheFirstSyncOfIt() = runBlocking {
        db.dayDao().upsert(DayEntity(oct20))
        db.activityDao().upsert(activity(1, oct20))
        val p = pipeline()
        p.recalibrateIfDue(oct24.minusDays(2), true) // none stored yet: v1
        p.recalibrateIfDue(oct24, true)              // same month: no-op
        assertEquals(1, prefs.strainCalibration.first()!!.version)
        val nov2 = LocalDate.of(2026, 11, 2)         // first sync of November, not the 1st
        p.recalibrateIfDue(nov2, true)
        assertEquals(2, prefs.strainCalibration.first()!!.version)
        p.recalibrateIfDue(nov2.plusDays(5), true)
        assertEquals(2, prefs.strainCalibration.first()!!.version)
    }

    @Test
    fun scalingUsesGlobalMedianForFamilyWithoutHr() = runBlocking {
        zones()
        withStrengthStream(1, oct20, SportFamily.RUN, 20.0)   // ratio 6.5786 / 20
        withStrengthStream(2, oct20, SportFamily.RIDE, 40.0)  // ratio 6.5786 / 40
        db.activityDao().upsert(activity(3, oct24, SportFamily.SWIM, 100.0)) // no stream, no samples of its own
        pipeline().recompute(oct20)
        val globalMedian = (6.5786 / 20 + 6.5786 / 40) / 2
        val swim = db.activityScoreDao().get(3)!!
        assertEquals(LoadSource.GARMIN_SCALED, swim.loadSource)
        assertEquals(100.0 * globalMedian, swim.load, 1e-3)
        assertEquals(6.5786, db.activityScoreDao().get(1)!!.load, 1e-4)
        assertEquals(100.0 * globalMedian, db.scoreDao().get(oct24)!!.dayLoad, 1e-3)
        db.scoreDao().range(oct20, oct24).first().forEach(::assertNoNaN)
    }

    @Test
    fun invalidZonesFallBackToGarminScaled() = runBlocking {
        zones(max = 44, rest = 44)
        withStrengthStream(1, oct20, SportFamily.STRENGTH, 20.0)
        db.dayDao().upsert(DayEntity(oct20))
        pipeline().recompute(oct20)
        val a = db.activityScoreDao().get(1)!!
        assertEquals(LoadSource.GARMIN_SCALED, a.loadSource)
        assertEquals(20.0, a.load, 1e-9)
        assertNull(db.activityDao().get(1)!!.hrCoverage)
        assertEquals(20.0, db.scoreDao().get(oct20)!!.dayLoad, 1e-9)
    }

    @Test
    fun missingZonesAlsoFallBack() = runBlocking {
        withStrengthStream(1, oct20, SportFamily.STRENGTH, 20.0)
        pipeline().recompute(oct20)
        assertEquals(LoadSource.GARMIN_SCALED, db.activityScoreDao().get(1)!!.loadSource)
    }

    @Test
    fun activityStreamGivesHrPathAndWritesCoverage() = runBlocking {
        zones()
        prefs.setStrainCalibration(StrainCalibration(10.0, 1), oct20)
        withStrengthStream(1, oct20, SportFamily.STRENGTH, 2.88)
        db.activityDao().upsert(db.activityDao().get(1)!!.copy(fitPath = "fit/1.fit", poolLengthM = 25.0))
        val before = db.activityDao().get(1)!!
        pipeline().recompute(oct20)
        val a = db.activityScoreDao().get(1)!!
        assertEquals(LoadSource.HR, a.loadSource)
        assertEquals(6.5786, a.load, 1e-4)
        assertEquals(10.1, a.strain, 1e-9) // 21 * (1 - exp(-6.5786 / 10)) = 10.12
        val after = db.activityDao().get(1)!!
        assertEquals(0.9803, after.hrCoverage!!, 1e-4)
        assertEquals("only hrCoverage changes", before.copy(hrCoverage = after.hrCoverage), after)
        val s = db.scoreDao().get(oct20)!!
        assertEquals(6.5786, s.dayLoad, 1e-4)
        assertEquals(10.1, s.strain, 1e-9)
        assertNoNaN(s)
    }

    @Test
    fun streamArrivingForAnOlderActivityIsPickedUpByTheNextRecompute() = runBlocking {
        zones()
        db.activityDao().upsert(activity(1, oct20, SportFamily.STRENGTH, 2.88))
        val p = pipeline()
        p.recompute(oct20)
        assertEquals(LoadSource.GARMIN_SCALED, db.activityScoreDao().get(1)!!.loadSource)
        withStrengthStream(1, oct20, SportFamily.STRENGTH, 2.88)
        p.recompute(oct24) // what the worker passes when only a FIT arrived
        assertEquals(LoadSource.HR, db.activityScoreDao().get(1)!!.loadSource)
        assertEquals(6.5786, db.scoreDao().get(oct20)!!.dayLoad, 1e-4)
    }

    @Test
    fun provisionalCalibrationIsReplacedWhenBackfillCompletes() = runBlocking {
        db.dayDao().upsert(DayEntity(oct20)); db.dayDao().upsert(DayEntity(oct24))
        db.activityDao().upsert(activity(1, oct20, garmin = 100.0))
        val p = pipeline()
        p.recalibrateIfDue(oct24, backfillDone = false)
        val provisional = prefs.strainCalibration.first()!!
        assertTrue(provisional.provisional)
        assertEquals(1, provisional.version)
        p.recompute(oct20)
        val old = db.scoreDao().range(oct20, oct24).first()
        assertEquals(setOf(1), old.map { it.calibrationVersion }.toSet())

        db.activityDao().upsert(activity(2, oct24, garmin = 300.0)) // the rest of the backfill arrives
        p.recalibrateIfDue(oct24, backfillDone = true)
        val cal = prefs.strainCalibration.first()!!
        assertFalse(cal.provisional)
        assertEquals(2, cal.version)
        assertTrue("tau changed", cal.tau > provisional.tau * 2)
        val rows = db.scoreDao().range(oct20, oct24).first()
        assertEquals(5, rows.size)
        assertEquals(setOf(2), rows.map { it.calibrationVersion }.toSet())
        assertTrue("re-versioned row rescored", rows.first { it.date == oct20 }.strain < old.first { it.date == oct20 }.strain)
    }

    @Test
    fun provisionalStaysProvisionalUntilFitStageIsDone() = runBlocking {
        db.dayDao().upsert(DayEntity(oct20))
        db.activityDao().upsert(activity(1, oct20, garmin = 100.0))
        fun task(e: String, k: String, prio: Int, st: TaskStatus) = SyncTaskEntity(e, k, prio, st, 0, null)
        db.taskDao().insertIgnore(listOf(task("STEPS", "a", 5, TaskStatus.DONE), task("FIT", "1", 7, TaskStatus.PENDING)))
        assertFalse("network constraint only looks at priorities 1..6", db.taskDao().backfillPending())
        assertTrue(db.taskDao().calibrationPending())

        val p = pipeline()
        p.recalibrateIfDue(oct24, backfillDone = !db.taskDao().calibrationPending())
        assertTrue(prefs.strainCalibration.first()!!.provisional)
        // A later sync with the FIT task still pending neither finalises nor loses the flag.
        p.recalibrateIfDue(oct24, backfillDone = !db.taskDao().calibrationPending())
        assertEquals(StrainCalibration(prefs.strainCalibration.first()!!.tau, 1, true), prefs.strainCalibration.first())

        db.taskDao().update(task("FIT", "1", 7, TaskStatus.DONE))
        assertFalse(db.taskDao().calibrationPending())
        p.recalibrateIfDue(oct24, backfillDone = !db.taskDao().calibrationPending())
        val cal = prefs.strainCalibration.first()!!
        assertFalse(cal.provisional)
        assertEquals(2, cal.version)
    }

    @Test
    fun finalCalibrationIsNeverDowngradedToProvisional() = runBlocking {
        db.dayDao().upsert(DayEntity(oct20))
        db.activityDao().upsert(activity(1, oct20))
        val p = pipeline()
        p.recalibrateIfDue(oct20, backfillDone = true)
        p.recalibrateIfDue(LocalDate.of(2026, 11, 2), backfillDone = false) // new month, FIT stage reopened
        val cal = prefs.strainCalibration.first()!!
        assertEquals(2, cal.version)
        assertFalse(cal.provisional)
    }

    private fun f(bpm: Int) = ((bpm - 44) / 161.0).let { r -> r * 0.64 * Math.exp(1.92 * r) }
    private fun hrDay(day: LocalDate, vararg points: Pair<LocalTime, Int>) = HrDayEntity(
        day, HrPacking.pack(points.map { (t, bpm) -> HrPoint(day.atTime(t).toEpochSecond(ZoneOffset.UTC), bpm) }),
    )

    @Test
    fun nonActivityLoadExcludesWindowsAndSamplesBelowZone1Floor() = runBlocking {
        zones()
        val oct19 = oct20.minusDays(1)
        db.dayDao().upsert(DayEntity(oct20))
        db.activityDao().upsert(activity(1, oct20, garmin = 20.0))                        // 14:00..14:10 GMT
        db.activityDao().upsert(activity(2, oct19, garmin = 0.0).copy(                   // 23:50 .. 00:20 next day
            startGmt = oct19.atTime(23, 50).toInstant(ZoneOffset.UTC), durationSec = 1800.0))
        db.hrDayDao().upsert(hrDay(
            oct20,
            LocalTime.of(0, 10) to 170,   // inside yesterday's activity that runs past midnight: excluded
            LocalTime.of(10, 0) to 120,   // counts
            LocalTime.of(10, 2) to 102,   // below zone1Floor 103: ignored
            LocalTime.of(10, 4) to 170,   // counts
            LocalTime.of(14, 5) to 150,   // inside today's activity: excluded
            LocalTime.of(14, 10) to 160,  // window end is inclusive: excluded
        ))
        val p = pipeline()
        p.recompute(oct20)
        val expectedNonActivity = 2.0 * (f(120) + f(170))
        assertEquals(20.0 + expectedNonActivity, db.scoreDao().get(oct20)!!.dayLoad, 1e-9)
        assertEquals(20.0, db.activityScoreDao().get(1)!!.load, 1e-9) // activity scores don't include it

        zones(max = 44, rest = 44) // invalid zones: no non-activity load at all
        p.recompute(oct20)
        assertEquals(20.0, db.scoreDao().get(oct20)!!.dayLoad, 1e-9)
    }

    @Test
    fun activityScoreRowIsDeletedWhenTheActivityNoLongerResolves() = runBlocking {
        db.activityDao().upsert(activity(1, oct20, garmin = null)) // neither stream nor Garmin load
        db.activityScoreDao().upsert(ActivityScoreEntity(1, 5.0, LoadSource.GARMIN_SCALED, 3.0))
        pipeline().recompute(oct20)
        assertNull(db.activityScoreDao().get(1))
    }

    @Test
    fun finalCalibrationNotReplacedMidMonth() = runBlocking {
        db.dayDao().upsert(DayEntity(oct20))
        db.activityDao().upsert(activity(1, oct20))
        val p = pipeline()
        p.recalibrateIfDue(oct20, backfillDone = true)
        val first = prefs.strainCalibration.first()!!
        assertFalse(first.provisional)
        p.recalibrateIfDue(oct24, backfillDone = true)
        p.recalibrateIfDue(oct24, backfillDone = false)
        assertEquals(first, prefs.strainCalibration.first())
    }

    @Test
    fun recalculateHistoryOnAnEmptyDbScoresToday() = runBlocking {
        pipeline().recalculateHistory()
        val today = db.scoreDao().get(oct24)
        assertNotNull(today)
        assertNull(today!!.recovery)
        assertNull(db.scoreDao().get(oct24.minusDays(1)))
    }

    @Test
    fun switchingBanisterSetRefitsTheScaleSoTheP90DayStaysAt17() = runBlocking {
        zones()
        val days = (0 until 10).map { oct24.minusDays(it.toLong()) }
        days.forEachIndexed { i, d ->
            db.dayDao().upsert(DayEntity(d))
            withStrengthStream(i + 1L, d, SportFamily.RUN, garmin = null)
        }
        val p = pipeline()
        p.recalibrateAndRescore(true)
        suspend fun p90Strain(): Double {
            val loads = days.map { db.scoreDao().get(it)!!.dayLoad }
            val cal = prefs.strainCalibration.first()!!
            return Strain.of(loads.sorted()[8], cal)
        }
        val cal1 = prefs.strainCalibration.first()!!
        assertEquals(1, cal1.version)
        assertEquals(17.0, p90Strain(), 0.11)

        prefs.setBanister(BanisterSet.FEMALE_FITTED)
        p.recalibrateAndRescore(true)
        val cal2 = prefs.strainCalibration.first()!!
        assertEquals(2, cal2.version)
        assertTrue(cal2.tau != cal1.tau)
        days.forEach { assertEquals(2, db.scoreDao().get(it)!!.calibrationVersion) }
        assertEquals(17.0, p90Strain(), 0.11)
    }

    @Test
    fun recalibrateAndRescoreOnAnEmptyDbStoresNoCalibration() = runBlocking {
        pipeline().recalibrateAndRescore(true)
        assertNull(prefs.strainCalibration.first())
        assertNotNull(db.scoreDao().get(oct24))
    }

    @Test
    fun recalibrateAndRescoreNeverDowngradesAFinalCalibration() = runBlocking {
        db.dayDao().upsert(DayEntity(oct20))
        db.activityDao().upsert(activity(1, oct20))
        val p = pipeline()
        p.recalibrateIfDue(oct20, backfillDone = true)
        p.recalibrateAndRescore(backfillDone = false) // a new activity's FIT is still pending
        val cal = prefs.strainCalibration.first()!!
        assertEquals(2, cal.version)
        assertFalse(cal.provisional)
    }
}
