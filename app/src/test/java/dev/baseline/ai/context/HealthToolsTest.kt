package dev.baseline.ai.context

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import dev.baseline.ai.engine.ToolResult
import dev.baseline.data.Repository
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.BestEffortEntity
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.LoadSource
import dev.baseline.data.db.ScoreEntity
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.StrainCalibration
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private const val NOTE = "(truncated — ask for a shorter range)"
private val TEN_FIELDS = listOf("recovery", "hrv", "rhr", "sleep_performance", "sleep_hours", "respiration", "body_battery_wake", "stress", "steps", "strain")

@RunWith(RobolectricTestRunner::class)
class HealthToolsTest {
    private val scopes = ScopeCleanup()
    private val today = LocalDate.of(2026, 10, 31)

    private lateinit var db: AppDatabase
    private lateinit var prefs: UserPrefs
    private lateinit var file: File
    private lateinit var tools: HealthTools

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        file = File.createTempFile("tools", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        prefs = UserPrefs(store)
        val repo = Repository(db, prefs, ApiKeyVault(store, { it }, { it }, Clock.systemUTC()))
        val packs = ContextPacks(db, repo, prefs, MutableStateFlow(today), MutableStateFlow(ZoneId.of("Europe/London")))
        tools = HealthTools(db, packs, prefs, MutableStateFlow(today))
    }

    @After
    fun tearDown() = runBlocking {
        scopes.close()
        db.close()
        file.delete()
        Unit
    }

    private fun at(date: LocalDate, h: Int, m: Int): Instant = date.atTime(LocalTime.of(h, m)).toInstant(ZoneOffset.UTC)

    private suspend fun seed(days: Int) {
        prefs.setStrainCalibration(StrainCalibration(40.0, 1), today)
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            db.dayDao().upsert(
                DayEntity(
                    date = d,
                    restingHr = 54,
                    respirationAvg = 14.04,
                    stressAvg = 30,
                    steps = 9000,
                    bodyBatteryWake = 80,
                    bodyBatteryCharged = 60,
                    sleepSeconds = 27000,
                    deepSeconds = 3600,
                    lightSeconds = 14400,
                    remSeconds = 5400,
                    awakeSeconds = 900,
                    sleepScore = 80,
                    sleepNeedMinutes = 450,
                    sleepStart = at(d.minusDays(1), 23, 0),
                    sleepEnd = at(d, 6, 30),
                    spo2Avg = 95.0,
                    hrvLastNight = 53.0 + i % 3,
                ),
            )
            db.scoreDao().upsert(ScoreEntity(d, 60, false, 1.0, 80.0 + i % 5, 8.4, 14.0, 18.0, 1))
        }
    }

    private suspend fun activity(id: Long, daysAgo: Long, family: SportFamily, typeKey: String, name: String? = "Secret loop") {
        val day = today.minusDays(daysAgo)
        db.activityDao().upsert(
            ActivityEntity(
                id, name, typeKey, family, LocalDateTime.of(day, LocalTime.of(7, 5)), day, at(day, 7, 5),
                3135.0, 10200.0, 151.0, 172.0, 120.0, 900.0, 1500.0, 500.0, 115.0, 120.0, 86.0, null, null, null, null, null, null,
            ),
        )
        db.activityScoreDao().upsert(ActivityScoreEntity(id, 90.0, LoadSource.HR, 9.1))
    }

    private fun call(name: String, args: String): ToolResult = runBlocking { tools.call(name, args) }

    private fun range(days: Int, extra: String = "") = """{"from":"${today.minusDays(days - 1L)}","to":"$today"$extra}"""

    private fun rows(r: ToolResult): JsonArray = Json.parseToJsonElement(r.content).jsonArray

    @Test
    fun definitionsMatchSpec() {
        val defs = tools.definitions.map { it.jsonObject }
        assertTrue(defs.all { it["type"]!!.jsonPrimitive.content == "function" })
        val fns = defs.map { it["function"]!!.jsonObject }
        assertEquals(
            listOf("daily_metrics", "activities", "activity_detail", "sleep_night", "training_load", "records"),
            fns.map {
                it["name"]!!.jsonPrimitive.content
            },
        )
        val daily = fns[0]["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(TEN_FIELDS, daily["fields"]!!.jsonObject["items"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("from", "to"), fns[0]["parameters"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val sports = fns[1]["parameters"]!!.jsonObject["properties"]!!.jsonObject["sport"]!!.jsonObject["enum"]!!.jsonArray
        assertEquals(listOf("run", "ride", "swim", "strength", "other"), sports.map { it.jsonPrimitive.content })
        assertEquals(listOf("id"), fns[2]["parameters"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("date"), fns[3]["parameters"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(emptyList<String>(), fns[5]["parameters"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun dailyMetricsRowsAndLabel() {
        runBlocking { seed(30) }
        val r = call("daily_metrics", range(30, ""","fields":["hrv"]"""))
        assertEquals("Looking up HRV, 2 Oct – 31 Oct", r.statusLabel)
        val rows = rows(r)
        assertEquals(30, rows.size)
        assertEquals(setOf("date", "hrv"), rows[0].jsonObject.keys)
        assertEquals("2026-10-02", rows[0].jsonObject["date"]!!.jsonPrimitive.content)
        assertEquals("2026-10-31", rows[29].jsonObject["date"]!!.jsonPrimitive.content)
        assertEquals(53.0, rows[29].jsonObject["hrv"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun dailyMetricsAllFieldsAndNullsForMissingDays() {
        runBlocking { seed(3) }
        val oldest = today.minusDays(5)
        for (args in listOf("""{"from":"$oldest","to":"$today"}""", """{"from":"$oldest","to":"$today","fields":[]}""")) {
            val r = call("daily_metrics", args)
            val rows = rows(r)
            assertEquals(6, rows.size)
            assertEquals(listOf("date") + TEN_FIELDS, rows[0].jsonObject.keys.toList())
            // The first three days have nothing stored, and say so with null.
            assertTrue(TEN_FIELDS.all { rows[0].jsonObject[it] is JsonNull })
            val last = rows[5].jsonObject
            assertEquals(60, last["recovery"]!!.jsonPrimitive.content.toInt())
            assertEquals(100, last["sleep_performance"]!!.jsonPrimitive.content.toInt())
            assertEquals(7.5, last["sleep_hours"]!!.jsonPrimitive.content.toDouble(), 0.0)
            assertEquals(14.0, last["respiration"]!!.jsonPrimitive.content.toDouble(), 0.0)
            assertEquals(8.4, last["strain"]!!.jsonPrimitive.content.toDouble(), 0.0)
            assertEquals(9000, last["steps"]!!.jsonPrimitive.content.toInt())
        }
        assertTrue(
            call("daily_metrics", """{"from":"$oldest","to":"$today"}""").statusLabel.startsWith("Looking up recovery, HRV, resting HR, sleep, sleep hours,"),
        )
    }

    @Test
    fun rejectsOverLongRange() {
        val r = call("daily_metrics", range(401))
        assertEquals("""{"error":"range is longer than 400 days"}""", r.content)
        assertEquals("Looking up …", r.statusLabel)
        assertTrue(call("activities", range(401)).content.contains("longer than 400 days"))
        assertTrue(call("training_load", range(401)).content.contains("longer than 400 days"))
        assertFalse(call("daily_metrics", range(400)).content.contains("error"))
    }

    @Test
    fun rejectsUnknownField() {
        val r = call("daily_metrics", range(5, ""","fields":["hrv","vo2"]"""))
        assertEquals("""{"error":"unknown field: vo2"}""", r.content)
        assertEquals("Looking up …", r.statusLabel)
        assertEquals("""{"error":"fields must be an array of strings"}""", call("daily_metrics", range(5, ""","fields":"hrv"""")).content)
    }

    @Test
    fun rejectsBadDate() {
        assertEquals("""{"error":"from must be an ISO date (yyyy-mm-dd)"}""", call("daily_metrics", """{"from":"yesterday","to":"$today"}""").content)
        assertEquals("""{"error":"from must be an ISO date (yyyy-mm-dd)"}""", call("daily_metrics", """{"to":"$today"}""").content)
        assertEquals("""{"error":"to must be an ISO date (yyyy-mm-dd)"}""", call("activities", """{"from":"$today","to":"2026-13-01"}""").content)
        assertEquals("""{"error":"date must be an ISO date (yyyy-mm-dd)"}""", call("sleep_night", """{"date":"31/10"}""").content)
        assertEquals("""{"error":"to must not be before from"}""", call("training_load", """{"from":"$today","to":"${today.minusDays(1)}"}""").content)
    }

    @Test
    fun rejectsBadArgumentsAndUnknownTools() {
        for (bad in listOf("", "not json", "[1]", "\"x\"", "null")) {
            assertEquals(bad, """{"error":"arguments must be a JSON object"}""", call("records", bad).content)
        }
        val r = call("delete_everything", "{}")
        assertEquals("Looking up …", r.statusLabel)
        assertEquals("""{"error":"unknown tool"}""", r.content)
        assertEquals("""{"error":"sport must be one of run, ride, swim, strength, other"}""", call("records", """{"sport":"golf"}""").content)
    }

    @Test
    fun activitiesFilterBySport() {
        runBlocking {
            activity(1, 3, SportFamily.RUN, "running")
            activity(2, 2, SportFamily.RIDE, "road_biking")
            activity(3, 1, SportFamily.OTHER, "yoga")
        }
        val all = call("activities", range(10))
        assertEquals("Looking up activities, ${today.minusDays(9).let { "22 Oct" }} – 31 Oct", all.statusLabel)
        assertEquals(listOf("run", "ride", "other"), rows(all).map { it.jsonObject["sport"]!!.jsonPrimitive.content })
        val run = rows(all)[0].jsonObject
        assertEquals(1, run["id"]!!.jsonPrimitive.content.toInt())
        assertEquals("2026-10-28T07:05", run["start"]!!.jsonPrimitive.content)
        assertEquals(52.3, run["duration_min"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(10.2, run["distance_km"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(151, run["avg_hr"]!!.jsonPrimitive.content.toInt())
        assertEquals(9.1, run["strain"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("yoga", rows(all)[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(all.content.contains("Secret"))

        val rides = call("activities", range(10, ""","sport":"ride""""))
        assertEquals("Looking up rides, 22 Oct – 31 Oct", rides.statusLabel)
        assertEquals(listOf(2L), rows(rides).map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() })
        assertEquals("[]", call("activities", range(10, ""","sport":"swim"""")).content)
    }

    @Test
    fun activityDetailPackAndUnknownId() {
        runBlocking { activity(7, 1, SportFamily.RUN, "running") }
        val r = call("activity_detail", """{"id":7}""")
        assertEquals("Looking up an activity", r.statusLabel)
        assertTrue(r.content, r.content.startsWith("Run, Fri 30 Oct 07:05 (running)"))
        assertTrue(r.content.contains("Average HR 151 bpm"))
        assertFalse(r.content.contains("Secret"))
        // A string id is accepted too.
        assertEquals(r.content, call("activity_detail", """{"id":"7"}""").content)

        val missing = call("activity_detail", """{"id":999}""")
        assertEquals("""{"error":"no activity with id 999"}""", missing.content)
        assertEquals("Looking up an activity", missing.statusLabel)
        assertEquals("""{"error":"id must be an integer"}""", call("activity_detail", "{}").content)
        assertEquals("""{"error":"id must be an integer"}""", call("activity_detail", """{"id":1.5}""").content)
    }

    @Test
    fun sleepNightIsByWakeDate() {
        runBlocking { seed(40) }
        val r = call("sleep_night", """{"date":"2026-10-31"}""")
        assertEquals("Looking up sleep, night of 30 Oct", r.statusLabel)
        assertTrue(r.content, r.content.startsWith("Night of Fri 30 Oct, ending Sat 31 Oct."))
        val none = call("sleep_night", """{"date":"2026-01-01"}""")
        assertEquals("Looking up sleep, night of 31 Dec", none.statusLabel)
        assertTrue(none.content, none.content.startsWith("""{"error":"no sleep recorded"""))
    }

    @Test
    fun trainingLoadRows() {
        runBlocking { seed(120) }
        val r = call("training_load", range(5))
        assertEquals("Looking up training load, 27 Oct – 31 Oct", r.statusLabel)
        val rows = rows(r)
        assertEquals(5, rows.size)
        val last = rows[4].jsonObject
        assertEquals(listOf("date", "fitness", "fatigue", "form_pct", "state"), last.keys.toList())
        assertEquals("2026-10-31", last["date"]!!.jsonPrimitive.content)
        assertTrue(last["fitness"]!!.jsonPrimitive.content.toDouble() > 0)
        assertTrue(last["state"]!!.jsonPrimitive.content in setOf("productive", "maintaining", "fresh", "overreaching", "detraining"))
        // Before the first stored day there is nothing to read.
        val early = call("training_load", """{"from":"2026-01-01","to":"2026-01-02"}""")
        assertTrue(rows(early).all { row -> row.jsonObject.filterKeys { it != "date" }.values.all { it is JsonNull } })
    }

    @Test
    fun recordsBySport() {
        runBlocking {
            db.bestEffortDao().insertAll(
                listOf(
                    BestEffortEntity(SportFamily.RUN, "5000m", 1351.0, 11, LocalDate.of(2026, 9, 2)),
                    BestEffortEntity(SportFamily.RUN, "1000m", 241.0, 12, LocalDate.of(2026, 9, 3)),
                    BestEffortEntity(SportFamily.RIDE, "power_1200s", 245.0, 13, LocalDate.of(2026, 8, 1)),
                    BestEffortEntity(SportFamily.RIDE, "40000m", 4980.0, 14, LocalDate.of(2026, 8, 2)),
                ),
            )
        }
        val run = call("records", """{"sport":"run"}""")
        assertEquals("Looking up records", run.statusLabel)
        val rr = rows(run).map { it.jsonObject }
        assertEquals(listOf(1000, 5000), rr.map { it["distance_m"]!!.jsonPrimitive.content.toInt() })
        assertEquals("22:31", rr[1]["time"]!!.jsonPrimitive.content)
        assertEquals("2026-09-02", rr[1]["date"]!!.jsonPrimitive.content)
        assertEquals(11, rr[1]["activity_id"]!!.jsonPrimitive.content.toInt())

        val all = rows(call("records", "{}")).map { it.jsonObject }
        assertEquals(listOf("run", "run", "ride", "ride"), all.map { it["sport"]!!.jsonPrimitive.content })
        assertEquals("1:23:00", all[2]["time"]!!.jsonPrimitive.content)
        assertEquals(1200, all[3]["power_duration_s"]!!.jsonPrimitive.content.toInt())
        assertEquals(245, all[3]["watts"]!!.jsonPrimitive.content.toInt())
        assertEquals("[]", call("records", """{"sport":"swim"}""").content)
    }

    @Test
    fun truncatesAtCap() {
        runBlocking { seed(400) }
        val r = call("daily_metrics", range(400))
        assertTrue(r.content.length.toString(), r.content.length <= 32_000)
        assertTrue(r.content.endsWith(NOTE))
        // Cut at a row boundary: everything before the note is a whole JSON array.
        val body = r.content.removeSuffix("\n$NOTE")
        val parsed = Json.parseToJsonElement(body).jsonArray
        assertTrue(parsed.size in 100 until 400)
        assertTrue(parsed.all { (it as JsonObject).keys.size == 11 })
        // A shorter range comes back whole.
        assertFalse(call("daily_metrics", range(30)).content.contains(NOTE))
    }

    @Test
    fun noRowsAfterToday() {
        runBlocking { seed(5) }
        val r = call("daily_metrics", """{"from":"${today.minusDays(1)}","to":"${today.plusDays(30)}","fields":["steps"]}""")
        assertEquals(2, rows(r).size)
        assertEquals(JsonPrimitive(9000), rows(r)[1].jsonObject["steps"])
    }
}
