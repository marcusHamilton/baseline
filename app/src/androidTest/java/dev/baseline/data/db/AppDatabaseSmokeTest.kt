package dev.baseline.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.baseline.data.SportFamily
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Exercises the real production path: file-backed DB from AppDatabase.create on a device. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "smoke_test.db" // never the user's baseline.db
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(name)
        db = AppDatabase.create(context, name)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun reUpsertKeepsChildrenAndDeleteCascades() = runBlocking {
        val day = LocalDate.of(2026, 10, 23)
        val activity = ActivityEntity(
            7, "Run", "running", SportFamily.RUN, LocalDateTime.of(2026, 10, 23, 7, 0), day,
            Instant.ofEpochSecond(1_761_202_800), 3600.0, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
        )
        db.activityDao().upsert(activity)
        db.activityStreamDao().upsert(ActivityStreamEntity(7, byteArrayOf(1)))
        db.activityScoreDao().upsert(ActivityScoreEntity(7, 50.0, LoadSource.HR, 8.0))
        db.lapDao().insertAll(
            listOf(
                LapEntity(
                    activityId = 7, lapIndex = 0, startOffsetSec = null, durationSec = 60.0, distanceM = null,
                    avgHr = null, maxHr = null, avgSpeed = null, avgPower = null,
                ),
            ),
        )

        db.activityDao().upsert(activity.copy(name = "Renamed"))

        assertEquals("Renamed", db.activityDao().byDay(day).single().name)
        assertEquals(1, db.lapDao().forActivity(7).size)
        assertNotNull(db.activityStreamDao().get(7))
        assertNotNull(db.activityScoreDao().get(7))

        db.activityDao().delete(listOf(7))

        assertEquals(0, db.lapDao().forActivity(7).size)
        assertNull(db.activityStreamDao().get(7))
        assertNull(db.activityScoreDao().get(7))
    }
}
