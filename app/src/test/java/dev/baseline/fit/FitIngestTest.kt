package dev.baseline.fit

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class FitIngestTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var ingest: FitIngest

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder<AppDatabase>(ApplicationProvider.getApplicationContext()) { AppDatabase_Impl() }
                .allowMainThreadQueries()
                .build()
        ingest = FitIngest(tmp.root, db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun activity(id: Long, pool: Double? = null) = db.activityDao().upsert(
        ActivityEntity(
            id,
            "x",
            "running",
            SportFamily.RUN,
            LocalDateTime.of(2026, 10, 23, 7, 0),
            LocalDate.of(2026, 10, 23),
            Instant.ofEpochSecond(1_761_202_800),
            3600.0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            pool,
        ),
    )

    @Test
    fun storesFileStreamAndLapsAndIsIdempotent() = runBlocking {
        activity(5)
        val zip = zipOf("5.fit", fitFixture("run"))
        ingest.ingest(5, zip)
        ingest.ingest(5, zip)

        val file = File(tmp.root, "fit/5.fit")
        assertTrue(file.exists())
        assertEquals("fit/5.fit", db.activityDao().get(5)!!.fitPath)
        assertEquals(1655, ActivityStreams.unpack(db.activityStreamDao().get(5)!!.packed).epochSec.size)
        assertEquals(6, db.lapDao().forActivity(5).size)
        assertNull(db.activityDao().get(5)!!.poolLengthM)
    }

    @Test
    fun swimSetsPoolLengthOnlyWhenUnset() = runBlocking {
        activity(6)
        activity(7, pool = 50.0)
        ingest.ingest(6, zipOf("a.fit", fitFixture("swim")))
        ingest.ingest(7, zipOf("a.fit", fitFixture("swim")))
        assertNotNull(db.activityDao().get(6)!!.poolLengthM)
        assertEquals(50.0, db.activityDao().get(7)!!.poolLengthM!!, 0.0)
        assertEquals(36, db.swimLengthDao().forActivity(6).size)
    }

    @Test
    fun strengthSetsStored() = runBlocking {
        activity(8)
        ingest.ingest(8, zipOf("a.fit", fitFixture("strength")))
        assertEquals(
            7,
            db
                .strengthSetDao()
                .forActivity(8)
                .single()
                .reps,
        )
    }

    @Test
    fun missingActivityRowIsSkipped() = runBlocking {
        ingest.ingest(99, zipOf("a.fit", fitFixture("run")))
        assertNull(db.activityStreamDao().get(99))
    }

    @Test
    fun corruptBytesThrowIllegalArgument() {
        runBlocking { activity(9) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ingest.ingest(9, byteArrayOf(1, 2, 3)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ingest.ingest(9, zipOf("a.fit", ByteArray(64) { 7 })) } }
        assertNull(runBlocking { db.activityStreamDao().get(9) })
    }
}
