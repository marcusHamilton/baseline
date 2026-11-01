package dev.baseline.ai.context

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.Repository
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.LapEntity
import dev.baseline.data.db.LoadSource
import dev.baseline.data.db.RawResponse
import dev.baseline.data.db.ScoreEntity
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.StrainCalibration
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

private val ROW = Regex("^[A-Z][a-z]{2} \\d{1,2} [A-Z][a-z]{2}: ", RegexOption.MULTILINE)

@RunWith(RobolectricTestRunner::class)
class ContextPacksTest {
    private val scopes = ScopeCleanup()
    private val today = LocalDate.of(2026, 11, 1)
    private val zone = ZoneId.of("Europe/London")

    private lateinit var db: AppDatabase
    private lateinit var prefs: UserPrefs
    private lateinit var file: File
    private lateinit var packs: ContextPacks

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        file = File.createTempFile("packs", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        prefs = UserPrefs(store)
        val repo = Repository(db, prefs, ApiKeyVault(store, { it }, { it }, Clock.systemUTC()))
        packs = ContextPacks(db, repo, prefs, MutableStateFlow(today), MutableStateFlow(zone))
    }

    @After
    fun tearDown() = runBlocking {
        scopes.close()
        db.close()
        file.delete()
        Unit
    }

    private fun at(date: LocalDate, h: Int, m: Int): Instant = date.atTime(LocalTime.of(h, m)).toInstant(ZoneOffset.UTC)

    /** 120 days up to [today]; every day before today is identical, so the baselines are exact (HRV 53 ms, RHR 54 bpm). */
    private suspend fun seed(days: Int = 120) {
        prefs.setStrainCalibration(StrainCalibration(40.0, 1), today)
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            val now = i == 0
            db.dayDao().upsert(
                DayEntity(
                    date = d,
                    restingHr = if (now) 52 else 54,
                    respirationAvg = 14.0,
                    stressAvg = 30,
                    steps = if (now) 4200 else 9000,
                    stepGoal = 10000,
                    bodyBatteryWake = 80,
                    bodyBatteryCharged = 60,
                    sleepSeconds = if (now) 25200 else 27000,
                    deepSeconds = if (now) 6000 else 3600,
                    lightSeconds = 14400,
                    remSeconds = 5400,
                    awakeSeconds = 900,
                    sleepScore = 80,
                    sleepNeedMinutes = 450,
                    sleepStart = at(d.minusDays(1), 23, 0),
                    sleepEnd = at(d, 6, 30),
                    spo2Avg = 95.0,
                    hrvLastNight = if (now) 58.0 else 53.0,
                ),
            )
            db.scoreDao().upsert(
                ScoreEntity(d, if (now) 71 else 60, false, if (now) 25200 / 27000.0 else 1.0, 80.0 + i % 5, 8.4, 14.0, 18.0, 1),
            )
        }
    }

    private suspend fun run(id: Long, daysAgo: Long, hour: Int = 7, minute: Int = 5, name: String? = null, distance: Double = 10200.0) {
        val day = today.minusDays(daysAgo)
        db.activityDao().upsert(
            ActivityEntity(
                id, name, "running", SportFamily.RUN, LocalDateTime.of(day, LocalTime.of(hour, minute)), day, at(day, hour, minute),
                3135.0, distance, 151.0, 172.0, 120.0, 900.0, 1500.0, 500.0, 115.0, 120.0, 86.0, null, null, null, null, null, null,
            ),
        )
        db.activityScoreDao().upsert(ActivityScoreEntity(id, 90.0, LoadSource.HR, 9.1))
    }

    private fun laps(activityId: Long, count: Int) = List(count) {
        LapEntity(
            activityId = activityId,
            lapIndex = it,
            startOffsetSec = it * 60.0,
            durationSec = 60.0,
            distanceM = 200.0,
            avgHr = 150.0 + it,
            maxHr = 170.0,
            avgSpeed = 3.3,
            avgPower = null,
        )
    }

    private fun text(scope: AskScope, size: PackSize) = runBlocking { packs.build(scope, size).text }

    @Test
    fun todayPackHeaderAndBody() {
        runBlocking { seed() }
        val t = text(AskScope.Today(today), PackSize.FULL)
        assertTrue(t, t.startsWith("Today is Sun 1 Nov 2026 (Europe/London). Units: metric.\nScope: today, Sun 1 Nov."))
        assertTrue(t, "Glossary: Recovery 0–100% (green ≥67, amber 34–66, red ≤33)" in t)
        assertTrue(t, "Recovery 71% (green)" in t)
        assertTrue(t, "HRV 58 ms vs 30-day baseline 53 ms (+9%)" in t)
        assertTrue(t, "Resting HR 52 bpm vs 30-day baseline 54 bpm (−2 bpm)" in t)
        assertTrue(t, "This week: " in t && "% of normal" in t)
        assertTrue(t, "Strain 8.4" in t && "target 14.0–18.0" in t)
    }

    @Test
    fun weekLineMatchesTheTodayScreen() {
        runBlocking { seed() }
        val t = text(AskScope.General, PackSize.COMPACT)
        assertTrue(t, Regex("This week: \\d+% of normal").containsMatchIn(t))
    }

    @Test
    fun missingValuesSayNoData() {
        runBlocking {
            seed()
            db.dayDao().upsert(db.dayDao().get(today)!!.copy(restingHr = null, respirationAvg = null))
        }
        for (size in PackSize.entries) {
            val t = text(AskScope.Today(today), size)
            assertTrue(t, "Resting HR no data" in t)
            assertTrue(t, "Respiration no data" in t)
            assertFalse(t, "null" in t)
        }
    }

    @Test
    fun imperialUnits() {
        runBlocking {
            seed()
            run(900, 0)
            db.rawDao().upsert(RawResponse("PROFILE", "-", """{"measurementSystem":"statute_us"}""".toByteArray(), Instant.EPOCH))
        }
        val t = text(AskScope.Activity(900, SportFamily.RUN), PackSize.FULL)
        assertTrue(t, "Units: imperial." in t)
        assertTrue(t, "6.34 mi" in t)
        assertTrue(t, "min/mi" in t)
        assertFalse(t, " km" in t)
    }

    @Test
    fun activityPackHasNoName() {
        runBlocking {
            seed()
            run(900, 0, name = "Secret Lane loop")
            run(901, 2, name = "Secret Lane loop")
        }
        for (size in PackSize.entries) {
            val t = text(AskScope.Activity(900, SportFamily.RUN), size)
            assertTrue(t, "Run, Sun 1 Nov 07:05" in t)
            assertFalse(t, "Secret Lane" in t)
            val today = text(AskScope.Today(today), size)
            assertTrue(today, "Run, Sun 1 Nov 07:05" in today)
            assertFalse(today, "Secret Lane" in today)
        }
    }

    @Test
    fun activityPackShape() {
        runBlocking {
            seed()
            for (i in 0L..11L) run(900 + i, i * 2)
            db.lapDao().insertAll(laps(900, 45))
        }
        val compact = text(AskScope.Activity(900, SportFamily.RUN), PackSize.COMPACT)
        val full = text(AskScope.Activity(900, SportFamily.RUN), PackSize.FULL)
        for (t in listOf(compact, full)) {
            assertTrue(t, "Average HR 151 bpm, max 172 bpm" in t)
            assertTrue(t, "Baseline strain 9.1" in t)
            assertTrue(t, "Garmin training load 120" in t)
            assertEquals(t, 20, t.lines().count { it.startsWith("- lap") })
        }
        assertEquals(3, compact.lines().count { it.startsWith("- prev ") })
        assertEquals(10, full.lines().count { it.startsWith("- prev ") })
    }

    @Test
    fun sleepPack() {
        runBlocking { seed() }
        val full = text(AskScope.Sleep(today), PackSize.FULL)
        val compact = text(AskScope.Sleep(today), PackSize.COMPACT)
        assertTrue(full, full.contains("Scope: sleep, night of Sat 31 Oct.\n"))
        assertTrue(full, "Sleep performance 93%" in full)
        assertTrue(full, "7h 0m asleep vs need 7h 30m" in full)
        assertTrue(full, "Bed 23:00, wake 06:30" in full)
        assertTrue(full, "Garmin score 80" in full)
        assertEquals(7, compact.lines().count { it.startsWith("- night of ") })
        assertEquals(30, full.lines().count { it.startsWith("- night of ") })
    }

    @Test
    fun sleepScopeReadsTheDayEntityAtItsDate() {
        runBlocking {
            seed()
            // 8h 20m on 28 Oct (the night of 27 Oct) and nothing special on the days either side.
            db.dayDao().upsert(db.dayDao().get(today.minusDays(4))!!.copy(sleepSeconds = 30000))
        }
        val t = text(AskScope.Sleep(today.minusDays(4)), PackSize.COMPACT)
        assertTrue(t, "Scope: sleep, night of Tue 27 Oct.\n" in t)
        assertTrue(t, "8h 20m asleep" in t)
        assertTrue(t, "Night of Tue 27 Oct, ending Wed 28 Oct." in t)
        assertFalse(t, "8h 20m asleep" in text(AskScope.Sleep(today.minusDays(3)), PackSize.COMPACT))
    }

    @Test
    fun compactStaysUnderBudget() {
        runBlocking {
            seed()
            for (i in 0L..11L) run(900 + i, i * 2)
            db.lapDao().insertAll(laps(900, 45))
        }
        val all =
            listOf(
                AskScope.Today(today),
                AskScope.General,
                AskScope.Sleep(today),
                AskScope.Activity(900, SportFamily.RUN),
                AskScope.Trends(7),
                AskScope.Trends(30),
                AskScope.Trends(90),
            )
        for (scope in all) {
            val t = text(scope, PackSize.COMPACT)
            assertTrue("$scope ${estimateTokens(t)} tokens\n$t", estimateTokens(t) <= 1200)
        }
    }

    @Test
    fun fullTrendsHasEveryDay() {
        runBlocking { seed() }
        val full = text(AskScope.Trends(90), PackSize.FULL)
        val compact = text(AskScope.Trends(90), PackSize.COMPACT)
        assertEquals(90, ROW.findAll(full).count())
        assertEquals(14, ROW.findAll(compact).count())
        assertEquals(8, compact.lines().count { " mean " in it })
        assertTrue(compact, "Scope: trends, last 90 days" in compact)
        assertEquals(7, ROW.findAll(text(AskScope.Trends(7), PackSize.COMPACT)).count())
    }

    @Test
    fun hasDataFalseWithoutMainRow() {
        runBlocking { seed(days = 10) }
        assertFalse(runBlocking { packs.build(AskScope.Sleep(today.minusDays(59)), PackSize.COMPACT) }.hasData)
        assertFalse(runBlocking { packs.build(AskScope.Today(today.minusDays(60)), PackSize.COMPACT) }.hasData)
        assertFalse(runBlocking { packs.build(AskScope.Activity(12345, SportFamily.RUN), PackSize.COMPACT) }.hasData)
        assertTrue(runBlocking { packs.build(AskScope.Today(today), PackSize.COMPACT) }.hasData)
        assertTrue(runBlocking { packs.build(AskScope.Sleep(today), PackSize.COMPACT) }.hasData)
        assertNull(runBlocking { packs.sleepBody(today.minusDays(59), PackSize.FULL) })
        assertNull(runBlocking { packs.activityBody(12345, PackSize.FULL) })
        runBlocking { db.dayDao().upsert(DayEntity(today.minusDays(60))) } // a row without sleep is not a night either
        assertFalse(runBlocking { packs.build(AskScope.Sleep(today.minusDays(60)), PackSize.COMPACT) }.hasData)
    }

    @Test
    fun factsFilled() {
        runBlocking { seed() }
        val facts = runBlocking { packs.build(AskScope.Today(today), PackSize.COMPACT) }.facts
        assertEquals(71, facts.recovery)
        assertEquals(80, facts.sleepScore)
        val sleep = runBlocking { packs.build(AskScope.Sleep(today), PackSize.COMPACT) }.facts
        assertEquals("deep" to true, sleep.stageFurthest)
        assertEquals(80, sleep.sleepScore)
        assertEquals("week", runBlocking { packs.build(AskScope.Trends(7), PackSize.COMPACT) }.facts.rangeLabel)
        assertEquals("month", runBlocking { packs.build(AskScope.Trends(30), PackSize.COMPACT) }.facts.rangeLabel)
        assertEquals("3 months", runBlocking { packs.build(AskScope.Trends(90), PackSize.COMPACT) }.facts.rangeLabel)
    }

    @Test
    fun generalIsTodaysPack() {
        runBlocking { seed() }
        assertEquals(text(AskScope.Today(today), PackSize.FULL), text(AskScope.General, PackSize.FULL))
    }

    @Test
    fun resultsDoNotDependOnTheJvmTimeZone() {
        runBlocking {
            seed()
            run(900, 0)
        }
        val scopes = listOf(AskScope.Today(today), AskScope.Sleep(today), AskScope.Activity(900, SportFamily.RUN), AskScope.Trends(30))
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val la = scopes.map { text(it, PackSize.FULL) }
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val tokyo = scopes.map { text(it, PackSize.FULL) }
            assertEquals(la, tokyo)
            assertTrue(tokyo[0], tokyo[0].startsWith("Today is Sun 1 Nov 2026 (Europe/London)."))
            assertTrue(tokyo[1], "Bed 23:00, wake 06:30" in tokyo[1])
            assertTrue(tokyo[2], "Run, Sun 1 Nov 07:05" in tokyo[2])
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
