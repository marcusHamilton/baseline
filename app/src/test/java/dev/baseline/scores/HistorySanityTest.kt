package dev.baseline.scores

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.baseline.data.parse.fixture
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.prefs.UserPrefs
import dev.baseline.sync.RawIngest
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.ceil

/** Spec §7 sanity bounds over the user's real 14-month history (no streams, no per-day HR: every activity is GARMIN_SCALED, k = 1). */
@RunWith(RobolectricTestRunner::class)
class HistorySanityTest {
    private fun nearestRank(sorted: List<Double>, p: Double) = sorted[ceil(p * sorted.size).toInt() - 1]

    @Test
    fun fullHistoryIsSane() = runBlocking {
        val db = inMemoryDatabase()
        try {
            val file = File.createTempFile("prefs", ".preferences_pb").also { it.delete() }
            val prefs = UserPrefs(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { file })
            val ingest = RawIngest(db)
            // Items carry their own dates, so the range keys are arbitrary.
            for ((e, key, f) in listOf(
                Triple(GarminEndpoint.DAILY_SUMMARIES, "history", "dailysummaries_history.json"),
                Triple(GarminEndpoint.HRV_RANGE, "history", "hrv_history.json"),
                Triple(GarminEndpoint.SLEEP_SUMMARIES, "history", "sleep_summaries_history.json"),
                Triple(GarminEndpoint.ACTIVITY_PAGE, "0", "activities_all.json"),
                Triple(GarminEndpoint.HR_ZONES, "-", "hr_zones.json"),
            )) {
                ingest.apply(e, key, fixture(f).toString().toByteArray())
            }

            val from = LocalDate.of(2025, 8, 30)
            val today = LocalDate.of(2026, 10, 24)
            val clock = Clock.fixed(today.atTime(8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
            ScorePipeline(db, prefs, clock).recompute(from)

            val rows = db.scoreDao().range(from, today).first()
            assertEquals(from.until(today, java.time.temporal.ChronoUnit.DAYS) + 1, rows.size.toLong())
            val strain = rows.map { it.strain }.sorted()
            val recovery = rows.mapNotNull { it.recovery }.sorted()
            val tau = prefs.strainCalibration.first()!!.tau
            val dist =
                "tau=%.2f strain min/p10/p50/p90/max=%s recovery(n=%d) min/p10/p50/p90/max=%s".format(
                    tau,
                    listOf(strain.first(), nearestRank(strain, .1), nearestRank(strain, .5), nearestRank(strain, .9), strain.last()),
                    recovery.size,
                    recovery
                        .takeIf {
                            it.isNotEmpty()
                        }?.let {
                            listOf(
                                it.first(),
                                nearestRank(it.map(Int::toDouble), .1),
                                nearestRank(it.map(Int::toDouble), .5),
                                nearestRank(it.map(Int::toDouble), .9),
                                it.last(),
                            )
                        },
                )

            rows.forEach { s ->
                listOfNotNull(s.sleepPerformance, s.dayLoad, s.strain, s.strainTargetLow, s.strainTargetHigh)
                    .forEach { assertFalse("NaN/inf in $s", it.isNaN() || it.isInfinite()) }
            }
            db
                .activityScoreDao()
                .all()
                .first()
                .forEach { assertFalse(it.load.isNaN() || it.strain.isNaN()) }
            assertEquals(dist, 74.61, tau, 0.01)
            assertTrue(dist, nearestRank(strain, 0.9) in 16.5..17.5)
            assertTrue(dist, recovery.all { it in 0..100 })
            assertTrue(dist, nearestRank(recovery.map(Int::toDouble), 0.5) in 40.0..80.0)
            assertTrue(dist, recovery.size >= 100)
        } finally {
            db.close()
        }
    }
}
