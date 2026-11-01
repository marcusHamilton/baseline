package dev.baseline.ai.context

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.Repository
import dev.baseline.data.SportFamily
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.parse.fixture
import dev.baseline.fit.FitIngest
import dev.baseline.fit.fitFixture
import dev.baseline.fit.zipOf
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.prefs.UserPrefs
import dev.baseline.records.RecordsRefresher
import dev.baseline.scores.ScorePipeline
import dev.baseline.sync.RawIngest
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private val COORDINATES = Regex("-?\\d{1,3}\\.\\d{4,}\\s*,\\s*-?\\d{1,3}\\.\\d{4,}")
private val LOCATION_WORDS = Regex("(?i)\\b(lat|lon|lng|latitude|longitude|polyline)\\b")

/** JSON keys whose values identify a person, a device or a typed name. */
private val IDENTIFYING_KEYS =
    Regex(
        "(?i)^(activityName|locationName|tripName|displayName|userName|fullName|ownerDisplayName|ownerFullName|activityUUID|uuid|" +
            "deviceId|ownerId|userId|userProfilePk|profileId|.*serial.*)$",
    )

/** Why a text must not be sent to an engine, or null when it is clean. */
private fun leak(text: String, forbidden: Collection<String>): String? {
    COORDINATES.find(text)?.let { return "coordinates: ${it.value}" }
    LOCATION_WORDS.find(text)?.let { return "location word: ${it.value}" }
    for (value in forbidden) {
        val found =
            if (value.all(Char::isDigit)) {
                Regex("(?<!\\d)${Regex.escape(value)}(?!\\d)").containsMatchIn(text)
            } else {
                text.contains(value, ignoreCase = true)
            }
        if (found) return "identifier: $value"
    }
    return null
}

@RunWith(RobolectricTestRunner::class)
class PrivacyScanTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = ScopeCleanup()
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private lateinit var packs: ContextPacks
    private lateinit var tools: HealthTools
    private val today = LocalDate.of(2026, 10, 24)

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        file = File.createTempFile("privacy", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        val prefs = UserPrefs(store)
        val ingest = RawIngest(db, FitIngest(tmp.root, db)::ingest)
        runBlocking {
            for ((e, key, f) in listOf(
                Triple(GarminEndpoint.DAILY_SUMMARIES, "history", "dailysummaries_history.json"),
                Triple(GarminEndpoint.HRV_RANGE, "history", "hrv_history.json"),
                Triple(GarminEndpoint.SLEEP_SUMMARIES, "history", "sleep_summaries_history.json"),
                Triple(GarminEndpoint.BODY_BATTERY, "history", "bodybattery_history.json"),
                Triple(GarminEndpoint.STRESS, "history", "stress_history.json"),
                Triple(GarminEndpoint.STEPS, "history", "steps_history.json"),
                Triple(GarminEndpoint.ACTIVITY_PAGE, "0", "activities_all.json"),
                Triple(GarminEndpoint.HR_ZONES, "-", "hr_zones.json"),
                Triple(GarminEndpoint.PROFILE, "-", "profile.json"),
                Triple(GarminEndpoint.SLEEP_DAY, "2026-10-23", "sleep_day.json"),
                Triple(GarminEndpoint.HEART_RATE_DAY, "2026-10-23", "heartrate_day.json"),
                Triple(GarminEndpoint.HRV_DAY, "2026-10-23", "hrv_day.json"),
                Triple(GarminEndpoint.SPO2_DAY, "2026-10-23", "spo2_day.json"),
            )) {
                ingest.apply(e, key, fixture(f).toString().toByteArray())
            }
            // One FIT file per fixture, on the first activities of the matching sport.
            val all = db.activityDao().list().sortedBy { it.id }
            val picks = mapOf("run" to SportFamily.RUN, "road_ride" to SportFamily.RIDE, "swim" to SportFamily.SWIM, "strength" to SportFamily.STRENGTH)
            for ((fit, family) in picks) ingest.apply(GarminEndpoint.FIT, all.first { it.sportFamily == family }.id.toString(), zipOf("a.fit", fitFixture(fit)))
            ingest.apply(GarminEndpoint.FIT, all.filter { it.sportFamily == SportFamily.RIDE }[1].id.toString(), zipOf("a.fit", fitFixture("indoor_ride")))
            ScorePipeline(db, prefs, Clock.fixed(today.atTime(8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)).recompute(LocalDate.of(2025, 8, 30))
            RecordsRefresher(db).refresh()
        }
        val repo = Repository(db, prefs, ApiKeyVault(store, { it }, { it }, Clock.systemUTC()))
        packs = ContextPacks(db, repo, prefs, MutableStateFlow(today), MutableStateFlow(ZoneId.of("Europe/London")))
        tools = HealthTools(db, packs, prefs, MutableStateFlow(today))
    }

    @After
    fun tearDown() = runBlocking {
        scopes.close()
        db.close()
        file.delete()
        Unit
    }

    /** Every identifying value in the fixture JSON, read out of the files so none is written in this test. */
    private fun forbiddenValues(): Set<String> {
        val found = linkedSetOf<String>()

        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject ->
                    e.forEach { (k, v) ->
                        if (v is JsonPrimitive && v !is JsonNull &&
                            IDENTIFYING_KEYS.matches(k)
                        ) {
                            v.content.trim().takeIf { it.length >= 4 && it != "None" }?.let(found::add)
                        }
                        walk(v)
                    }

                is JsonArray -> e.forEach(::walk)

                else -> Unit
            }
        }
        for (name in listOf(
            "activities_all",
            "dailysummaries_history",
            "sleep_day",
            "sleep_summaries_history",
            "hrv_day",
            "heartrate_day",
            "spo2_day",
            "profile",
        )) {
            walk(fixture("$name.json"))
        }
        return found
    }

    @Test
    fun scannerFlagsWhatItShouldFlag() {
        assertTrue(leak("start 38.46842, -98.58371 end", emptyList()) != null)
        assertTrue(leak("the Polyline of the route", emptyList()) != null)
        assertTrue(leak("lat 12", emptyList()) != null)
        assertTrue(leak("device 100000000 here", listOf("100000000")) != null)
        assertTrue(leak("1100000000 steps", listOf("100000000")) == null)
        assertTrue(leak("Morning RUN by the lake", listOf("morning run by the lake")) != null)
        assertTrue(leak("Recovery 71% on 24.5 km", listOf("Secret")) == null)
    }

    @Test
    fun noPackCarriesAnIdentifierOrALocation() = runBlocking {
        val forbidden = forbiddenValues().toMutableSet()
        val activities = db.activityDao().list()
        activities.mapNotNullTo(forbidden) { it.name?.takeIf(String::isNotBlank) }
        // The scan is only worth something if the fixtures really carry these fields.
        assertTrue("activity names", activities.count { !it.name.isNullOrBlank() } > 50)
        assertTrue("identifiers: $forbidden", forbidden.size > 100)
        assertTrue("fixture ids", forbidden.any { id -> id.all(Char::isDigit) })

        val scanned = mutableListOf<Pair<String, String>>()
        val scopes =
            buildList {
                add(AskScope.General)
                for (back in 0L..60L) add(AskScope.Today(today.minusDays(back)))
                for (back in 0L..60L) add(AskScope.Sleep(today.minusDays(back)))
                for (days in listOf(7, 30, 90)) add(AskScope.Trends(days))
                activities.forEach { add(AskScope.Activity(it.id, it.sportFamily)) }
            }
        for (scope in scopes) {
            for (size in PackSize.entries) scanned += "$scope $size" to packs.build(scope, size).text
        }
        for (a in activities) {
            for (size in PackSize.entries) packs.activityBody(a.id, size)?.let { scanned += "body ${a.id} $size" to it }
        }
        for (back in 0L..60L) {
            for (size in PackSize.entries) packs.sleepBody(today.minusDays(back), size)?.let { scanned += "sleep body $back $size" to it }
        }

        val leaks = scanned.mapNotNull { (label, text) -> leak(text, forbidden)?.let { "$label: $it" } }
        assertEquals(emptyList<String>(), leaks)
        // The packs were real: the FIT-backed activities have laps, and the days have numbers.
        assertTrue(scanned.any { "- lap" in it.second })
        assertTrue(scanned.any { (label, text) -> label.startsWith("Sleep(") && "Sleep performance" in text })
        assertFalse(scanned.any { it.second.contains("NaN") || it.second.contains("Infinity") || it.second.contains("null") })
        scanned.filter { it.first.endsWith("COMPACT") }.forEach { (label, text) -> assertTrue("$label ${estimateTokens(text)}", estimateTokens(text) <= 1200) }
    }

    @Test
    fun noToolResultCarriesAnIdentifierOrALocation() = runBlocking {
        val forbidden = forbiddenValues().toMutableSet()
        val activities = db.activityDao().list()
        activities.mapNotNullTo(forbidden) { it.name?.takeIf(String::isNotBlank) }
        assertTrue("identifiers: ${forbidden.size}", forbidden.size > 100)

        val scanned = mutableListOf<Pair<String, String>>()

        suspend fun scan(name: String, args: String) {
            scanned += "$name $args" to tools.call(name, args).content
        }
        // The fixtures span about a year up to `today`: two 400-day windows cover them, then shorter ones.
        val windows = listOf(400, 200, 30).map { days -> """"from":"${today.minusDays(days - 1L)}","to":"$today"""" }
        for (w in windows) {
            scan("daily_metrics", "{$w}")
            scan("activities", "{$w}")
            scan("training_load", "{$w}")
        }
        scan("daily_metrics", """{${windows[0]},"fields":["hrv","strain"]}""")
        for (sport in listOf("run", "ride", "swim", "strength", "other")) {
            scan("activities", """{${windows[0]},"sport":"$sport"}""")
            scan("records", """{"sport":"$sport"}""")
        }
        scan("records", "{}")
        for (a in activities) scan("activity_detail", """{"id":${a.id}}""")
        for (back in 0L..60L) scan("sleep_night", """{"date":"${today.minusDays(back)}"}""")

        val leaks = scanned.mapNotNull { (label, text) -> leak(text, forbidden)?.let { "$label: $it" } }
        assertEquals(emptyList<String>(), leaks)
        // The tools returned real rows.
        fun content(prefix: String) = scanned.filter { it.first.startsWith(prefix) }.map { it.second }
        assertTrue(content("daily_metrics").any { "\"hrv\":" in it && "\"recovery\":null" !in it })
        assertTrue(content("activities").any { "\"sport\":\"run\"" in it })
        assertTrue(content("training_load").any { "\"state\":\"" in it })
        assertTrue(content("records").any { "\"distance_m\":" in it })
        assertTrue(content("activity_detail").any { "- lap" in it })
        assertTrue(content("sleep_night").any { "Sleep performance" in it })
        assertFalse(scanned.any { it.second.contains("NaN") || it.second.contains("Infinity") })
        // Only a night without sleep may answer with an error.
        assertEquals(emptyList<String>(), scanned.filter { it.second.startsWith("""{"error"""") && !it.first.startsWith("sleep_night") }.map { it.first })
        assertTrue(scanned.all { it.second.length <= 32_000 })
    }
}
