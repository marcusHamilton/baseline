package dev.baseline.ui.activity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.garmin.fit.ExerciseCategory
import dev.baseline.data.Repository
import dev.baseline.data.SportFamily
import dev.baseline.data.SportFamily.OTHER
import dev.baseline.data.SportFamily.RIDE
import dev.baseline.data.SportFamily.RUN
import dev.baseline.data.SportFamily.STRENGTH
import dev.baseline.data.SportFamily.SWIM
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.ActivityStreamEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.LoadSource
import dev.baseline.data.db.StrengthSetEntity
import dev.baseline.data.db.SwimLengthEntity
import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.TaskStatus
import dev.baseline.fit.ActivityStreams
import dev.baseline.fit.FitReader
import dev.baseline.fit.fitFixture
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.prefs.UserPrefs
import dev.baseline.sync.SyncScheduler
import dev.baseline.sync.closeWorkManagerDb
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.testutil.inMemoryDatabase
import dev.baseline.ui.activities.act
import dev.baseline.ui.format.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

private class CountingScheduler(wm: WorkManager) : SyncScheduler(wm, { false }) {
    val syncNows = AtomicInteger()

    override suspend fun syncNow() {
        syncNows.incrementAndGet()
    }
}

private val RUN_FIT = FitReader.read(fitFixture("run")).streams

private fun streamOf(s: ActivityStreams) = ActivityStreamEntity(1, s.pack())

/** Last valid distance of a stream. */
private fun totalM(s: ActivityStreams) = s.distanceM!!
    .filter { it > -1e8f }
    .max()
    .toDouble()

private fun build(
    a: ActivityEntity = act(1),
    stream: ActivityStreamEntity? = streamOf(RUN_FIT),
    score: ActivityScoreEntity? = null,
    lengths: List<SwimLengthEntity> = emptyList(),
    sets: List<StrengthSetEntity> = emptyList(),
    task: TaskStatus? = null,
    imperial: Boolean = false,
) = buildActivityState(a, score, stream, lengths, sets, task, Formatters(imperial))

private inline fun <reified T : Section> ActivityUiState.section() = sections.filterIsInstance<T>().singleOrNull()

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ActivityViewModelTest {
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
        db = inMemoryDatabase(context)
        file = File.createTempFile("activityvm", ".preferences_pb").also { it.delete() }
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

    private fun vm(id: Long = 1) = scopes.track(ActivityViewModel(repo, scheduler, id))

    private suspend fun ActivityViewModel.await(p: (ActivityUiState) -> Boolean) = withTimeout(10_000) { state.first(p) }

    @Test
    fun sectionsPerFamily() {
        val k = SectionKind.entries.associateBy { it.name }

        fun s(vararg n: String) = n.map { k.getValue(it) }
        assertEquals(s("MAP", "HR", "ZONES", "SPLITS", "RUNNING"), sectionsFor(RUN, hasGps = true, hasPower = true))
        assertEquals(s("HR", "ZONES", "SPLITS", "RUNNING"), sectionsFor(RUN, hasGps = false, hasPower = false))
        assertEquals(s("MAP", "RIDE", "HR", "ZONES"), sectionsFor(RIDE, hasGps = true, hasPower = false))
        assertEquals(s("RIDE", "HR", "ZONES"), sectionsFor(RIDE, hasGps = false, hasPower = true))
        assertEquals(s("SWIM", "HR", "ZONES"), sectionsFor(SWIM, hasGps = true, hasPower = false))
        assertEquals(s("STRENGTH", "HR"), sectionsFor(STRENGTH, hasGps = true, hasPower = true))
        assertEquals(s("MAP", "HR", "ZONES"), sectionsFor(OTHER, hasGps = true, hasPower = false))
        assertEquals(s("HR", "ZONES"), sectionsFor(OTHER, hasGps = false, hasPower = false))
    }

    @Test
    fun splitsPerKmFromStream() {
        val km = totalM(RUN_FIT) / 1000
        assertTrue("fixture should leave a remainder", km - floor(km) > 0.01)
        val splits = build().section<Section.Splits>()!!
        assertEquals("km", splits.unit)
        assertEquals(floor(km).toInt() + 1, splits.rows.size)
        assertEquals((1..floor(km).toInt()).map { it.toString() }, splits.rows.dropLast(1).map { it.label })
        assertEquals("%.2f".format(java.util.Locale.US, km - floor(km)), splits.rows.last().label)
        // A run: every full kilometre is between 2:30 and 12:00 per km, and shows an HR and an altitude change.
        assertTrue(splits.rows.dropLast(1).all { it.paceSec in 150.0..720.0 })
        assertTrue(splits.rows.all { it.hr != "—" && it.elev != "—" && it.bar in 0f..1f })
        // The splits add up to the activity's elapsed time.
        val elapsed = (RUN_FIT.epochSec.last() - RUN_FIT.epochSec.first()).toDouble()
        val sum = splits.rows.sumOf { r -> r.paceSec * (if (r === splits.rows.last()) km - floor(km) else 1.0) }
        assertEquals(elapsed, sum, elapsed * 0.05)
    }

    @Test
    fun imperialSplitsPerMile() {
        val miles = totalM(RUN_FIT) / 1609.344
        val splits = build(imperial = true).section<Section.Splits>()!!
        assertEquals("mi", splits.unit)
        assertEquals(floor(miles).toInt() + 1, splits.rows.size)
        // Pace per mile is 1.609x pace per km.
        val metric =
            build()
                .section<Section.Splits>()!!
                .rows
                .first()
                .paceSec
        assertEquals(metric * 1.609344, splits.rows.first().paceSec, metric * 0.25)
        assertEquals(
            "ft",
            build(imperial = true)
                .summary
                .single { it.label.startsWith("elev") }
                .label
                .substringAfterLast(' '),
        )
    }

    @Test
    fun swimPacePer100FromLengths() {
        fun len(i: Int, active: Boolean, sec: Double, stroke: String? = "freestyle", n: Int? = 18) = SwimLengthEntity(
            activityId = 1,
            lengthIndex = i,
            startOffsetSec = i * 40.0,
            active = active,
            durationSec = sec,
            strokeType = if (active) stroke else null,
            strokeCount = if (active) n else null,
        )
        val a = act(1, "lap_swimming", distanceM = 75.0, durationSec = 150.0).copy(poolLengthM = 25.0)
        val state =
            build(
                a,
                stream = null,
                lengths = listOf(len(0, true, 30.0), len(1, true, 31.25, "backstroke", 16), len(2, false, 20.0), len(3, true, 28.75)),
            )
        val swim = state.section<Section.Swim>()!!
        assertEquals(3, swim.lengths.size) // the rest length is not listed
        assertArrayEquals(doubleArrayOf(120.0, 125.0, 115.0), swim.lengths.map { it.paceSec100!! }.toDoubleArray(), 1e-9)
        assertEquals(listOf("2:00", "2:05", "1:55"), swim.lengths.map { it.pace })
        assertEquals(listOf("Freestyle", "Backstroke", "Freestyle"), swim.lengths.map { it.stroke })
        assertEquals(listOf("18", "16", "18"), swim.lengths.map { it.strokes })
        assertEquals(120.0, swim.pacePer100!!, 1e-9) // 90 s over 75 m
        assertTrue(swim.summary.startsWith("25 m pool · 3 lengths"))
        // The tile agrees with the list: 90 s of swimming over 75 m, not the 150 s elapsed (2:00, not 3:20).
        assertEquals("2:00", state.summary.single { it.label.startsWith("avg pace") }.value)
        // Imperial shows the same length per 100 yd: 120 s per 100 m is 109.7 s.
        val yd = build(a, stream = null, lengths = listOf(len(0, true, 30.0)), imperial = true).section<Section.Swim>()!!
        assertEquals("1:50", yd.lengths.single().pace)
        assertTrue(yd.summary.startsWith("27 yd pool"))
    }

    @Test
    fun strengthGroupsSetsByCategory() {
        fun set(i: Int, cat: Int?, reps: Int?, kg: Double?, active: Boolean = true, sec: Double = 40.0) = StrengthSetEntity(
            activityId = 1,
            setIndex = i,
            startOffsetSec = i * 60.0,
            exerciseName = null,
            setType = if (active) "ACTIVE" else "REST",
            category = cat,
            reps = reps,
            weightKg = kg,
            durationSec = sec,
        )
        val bench = ExerciseCategory.BENCH_PRESS
        val squat = ExerciseCategory.SQUAT
        val plank = ExerciseCategory.PLANK
        val sets =
            listOf(
                set(0, bench, 6, 100.0),
                set(1, bench, 6, 100.0, active = false),
                set(2, bench, 6, 100.0),
                set(3, squat, 8, 52.5),
                set(4, bench, 6, 100.0),
                set(5, ExerciseCategory.PULL_UP, 8, null),
                set(6, ExerciseCategory.PULL_UP, 7, 0.0),
                set(7, plank, null, null, sec = 45.0),
                set(8, null, 5, 20.0),
                set(9, 65534, 5, 20.0),
            )
        val ex = build(act(1, "strength_training", distanceM = null), stream = null, sets = sets).section<Section.Strength>()!!.exercises
        assertEquals(listOf("Bench Press", "Squat", "Pull Up", "Plank", "Exercise"), ex.map { it.name })
        assertEquals(listOf("6 × 100", "6 × 100", "6 × 100"), ex[0].sets) // the REST set is not shown
        assertEquals("3 sets · 1,800 kg", ex[0].summary)
        assertEquals(listOf("8 × 52.5"), ex[1].sets)
        assertEquals("1 set · 420 kg", ex[1].summary)
        assertEquals(listOf("8", "7"), ex[2].sets)
        assertEquals("2 sets · bodyweight", ex[2].summary)
        assertEquals(listOf("0:45"), ex[3].sets)
        assertEquals("1 set", ex[3].summary)
        assertEquals(listOf("5 × 20", "5 × 20"), ex[4].sets) // null and unknown categories both land in "Exercise"
        assertEquals("2 sets · 200 kg", ex[4].summary)
        val tiles = build(act(1, "strength_training", distanceM = null), stream = null, sets = sets).summary
        assertEquals("9", tiles.single { it.label == "sets" }.value)
        assertEquals("2,420" to "volume kg", tiles.single { it.label.startsWith("volume") }.let { it.value to it.label })
    }

    @Test
    fun missingValuesShowDash() {
        val none = build(act(1, "running", distanceM = null, durationSec = 0.0), stream = null)
        assertEquals(listOf("—", "—", "—", "—", "—", "—"), none.summary.map { it.value })
        assertEquals("Garmin load —", none.summary.last().sub)
        val ride = build(act(1, "road_biking", distanceM = null, durationSec = 0.0), stream = null).summary
        assertTrue(ride.all { it.value == "—" })
        // A run FIT without altitude, HR or cadence streams still renders with dashes and no NaN.
        val bare =
            ActivityStreams(
                longArrayOf(0, 1, 2, 3),
                intArrayOf(-1, -1, -1, -1),
                floatArrayOf(0f, 400f, 800f, 1200f),
                null,
                null,
                null,
                null,
                null,
                null,
            )
        val state = build(stream = streamOf(bare))
        val splits = state.section<Section.Splits>()!!
        assertTrue(splits.rows.all { it.hr == "—" && it.elev == "—" })
        assertNull(state.section<Section.Hr>())
        val running = state.section<Section.Running>()!!
        assertEquals(Section.Running("—", "—", "—"), running)
        assertTrue(state.sections.none { it.toString().contains("NaN") })
    }

    @Test
    fun runningDynamicsFromTheFixture() {
        val r = build(act(1).copy(avgPower = 268.0)).section<Section.Running>()!!
        // FIT running cadence is per leg; the section shows steps per minute.
        assertTrue(r.cadence, r.cadence.toInt() in 140..220)
        assertEquals("268", r.power)
        assertTrue(r.stride, r.stride.toDouble() in 0.6..2.0)
    }

    @Test
    fun summaryShowsGarminLoadNextToStrain() {
        val a = act(1).copy(avgHr = 152.4, elevationGainM = 86.2, garminLoad = 112.4)
        val tiles = build(a, score = ActivityScoreEntity(1, 112.4, LoadSource.HR, 9.84)).summary
        assertEquals(
            listOf("10.21" to "distance km", "52:14" to "time", "5:07" to "avg pace /km", "152" to "avg HR bpm", "86" to "elev gain m", "9.8" to "strain"),
            tiles.map { it.value to it.label },
        )
        assertEquals("Garmin load 112", tiles.last().sub)
    }

    @Test
    fun downsampleKeepsShapeAndBounds() {
        val values =
            List(5_000) {
                if (it == 1234) {
                    500.0
                } else if (it == 4321) {
                    -300.0
                } else {
                    100.0 + 50 * kotlin.math.sin(it / 40.0)
                }
            }
        val out = downsample(values, 600) { it }
        assertTrue(out.size in 2..600)
        assertEquals(values.first(), out.first(), 0.0)
        assertEquals(values.last(), out.last(), 0.0)
        assertEquals(500.0, out.max(), 0.0) // peaks survive
        assertEquals(-300.0, out.min(), 0.0)
        assertEquals(out, downsample(out, 600) { it }) // already small: untouched
        val idx = List(5_000) { it }
        val kept = downsample(idx, 600) { values[it] }
        assertEquals(kept.sorted(), kept) // still in time order
        assertEquals(kept.distinct(), kept)
        assertEquals(listOf(1.0, 2.0, 3.0), downsample(listOf(1.0, 2.0, 3.0), 600) { it })
    }

    @Test
    fun noStreamQueuesFitOnce() = runBlocking {
        db.activityDao().upsert(act(1))
        val v = vm()
        val s = v.await { !it.loading }
        assertEquals("Detailed data downloading…", s.note)
        withTimeout(10_000) { while (scheduler.syncNows.get() == 0) delay(20) } // the init block queues on its own coroutine
        val tasks = db.taskDao().all()
        assertEquals(1, tasks.size)
        assertTrue(tasks.single().let { it.endpoint == "FIT" && it.key == "1" && it.priority == 0 && it.status == TaskStatus.PENDING })
        assertEquals(1, scheduler.syncNows.get())
        // The stream lands: the sections appear, the note goes away, and nothing is queued again.
        db.activityStreamDao().upsert(streamOf(RUN_FIT))
        val full = v.await { it.note == null && !it.loading }
        assertNotNull(full.sections.firstOrNull { it is Section.Splits })
        assertEquals(1, scheduler.syncNows.get())
        assertEquals(1, db.taskDao().count())
    }

    @Test
    fun streamAlreadyStoredQueuesNothing() = runBlocking {
        db.activityDao().upsert(act(1))
        db.activityStreamDao().upsert(streamOf(RUN_FIT))
        vm().await { !it.loading && it.note == null }
        assertTrue(db.taskDao().all().isEmpty())
        assertEquals(0, scheduler.syncNows.get())
    }

    @Test
    fun noStreamShowsHeaderTilesAndZonesOnly() {
        val a = act(1).copy(zone1Sec = 192.0, zone2Sec = 880.0, zone3Sec = 1325.0, zone4Sec = 647.0, zone5Sec = 90.0)
        val s = build(a, stream = null, task = TaskStatus.PENDING)
        assertEquals("Detailed data downloading…", s.note)
        assertEquals(listOf(SectionKind.ZONES), s.sections.map { it.kind })
        assertEquals("Activity 1", s.header?.title)
        assertEquals("Couldn't read this data", build(a, stream = null, task = TaskStatus.FAILED).note)
        assertEquals("No detailed data for this activity", build(a, stream = null, task = TaskStatus.EMPTY).note)
        assertEquals("Couldn't read this data", build(a, stream = ActivityStreamEntity(1, byteArrayOf(9, 9))).note)
    }

    @Test
    fun emptyFitTaskIsNotRequeued() = runBlocking {
        db.activityDao().upsert(act(1))
        db.taskDao().upsertAll(listOf(SyncTaskEntity("FIT", "1", 7, TaskStatus.EMPTY, 1, null)))
        val v = vm()
        assertEquals(NOTE_NONE, v.await { !it.loading }.note)
        delay(500) // the init block has had time to queue if it were going to
        assertEquals(0, scheduler.syncNows.get())
        assertEquals(
            TaskStatus.EMPTY,
            db
                .taskDao()
                .all()
                .single()
                .status,
        )
    }

    @Test
    fun failedFitTaskIsRequeued() = runBlocking {
        db.activityDao().upsert(act(1))
        db.taskDao().upsertAll(listOf(SyncTaskEntity("FIT", "1", 7, TaskStatus.FAILED, 3, "boom")))
        val v = vm()
        v.await { !it.loading }
        withTimeout(10_000) { while (scheduler.syncNows.get() == 0) delay(20) }
        val t = db.taskDao().all().single()
        assertTrue(t.status == TaskStatus.PENDING && t.priority == 0 && t.attempts == 0)
        assertEquals(1, scheduler.syncNows.get())
    }

    @Test
    fun firstSplitElevationStartsFromTheFirstValidAltitude() {
        val miss = ActivityStreams.MISSING
        val s =
            ActivityStreams(
                longArrayOf(0, 100, 200, 300, 400),
                intArrayOf(150, 150, 150, 150, 150),
                floatArrayOf(0f, 500f, 1000f, 1500f, 2000f),
                null,
                null,
                floatArrayOf(miss, 100f, 110f, 120f, 130f),
                null,
                null,
                null,
            )
        val rows = build(stream = streamOf(s)).section<Section.Splits>()!!.rows
        assertEquals(listOf("+10", "+20"), rows.map { it.elev }) // 100 -> 110 and 110 -> 130, never "—" for the first row
    }

    @Test
    fun rideWithOnlyPowerStillShowsTheRideSection() {
        val s =
            ActivityStreams(
                longArrayOf(0, 1, 2, 3),
                intArrayOf(-1, -1, -1, -1),
                null,
                null,
                intArrayOf(100, 200, 150, 250),
                null,
                null,
                null,
                null,
            )
        val ride = build(act(1, "virtual_ride"), stream = streamOf(s)).section<Section.Ride>()!!
        assertNull(ride.speed)
        assertNotNull(ride.power)
        assertNull(ride.elevation)
        assertNull(
            build(
                act(1, "virtual_ride"),
                stream = streamOf(ActivityStreams(longArrayOf(0, 1), intArrayOf(-1, -1), null, null, null, null, null, null, null)),
            ).section<Section.Ride>(),
        )
    }

    @Test
    fun speedBucketsAreAveragedNotMinMax() {
        val t0 = java.time.Instant.EPOCH
        val pts = List(3_000) { t0.plusSeconds(it.toLong()) to if (it % 2 == 0) 0.0 else 10.0 }
        val out = downsampleMean(pts, 600)
        assertTrue(out.size <= 600)
        assertTrue(out.all { it.second in 4.0..6.0 }) // a 0/10 band reads as ~5, not as the extremes
        assertEquals(pts.take(10), downsampleMean(pts.take(10), 600))
    }
}
