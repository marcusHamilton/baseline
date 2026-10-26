package dev.baseline.records

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.SportFamily
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.ui.activities.act
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordsRefresherTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder<AppDatabase>(ApplicationProvider.getApplicationContext()) { AppDatabase_Impl() }
                .allowMainThreadQueries()
                .build()
    }

    @After fun tearDown() = db.close()

    @Test fun recordsRebuiltAfterActivityDeleted() = runBlocking {
        db.activityDao().upsert(act(1).copy(fastestSplitsJson = """{"fastestSplit_5000":1400.0}"""))
        db.activityDao().upsert(act(2).copy(fastestSplitsJson = """{"fastestSplit_5000":1500.0}"""))
        val refresher = RecordsRefresher(db)
        refresher.refresh()
        assertEquals(
            1L,
            db
                .bestEffortDao()
                .forSport(SportFamily.RUN)
                .single()
                .activityId,
        )

        db.activityDao().delete(listOf(1L)) // removed on Garmin
        refresher.refresh()
        val r = db.bestEffortDao().forSport(SportFamily.RUN).single()
        assertEquals(2L, r.activityId)
        assertEquals(1500.0, r.valueSec, 0.0)

        db.activityDao().delete(listOf(2L))
        refresher.refresh()
        assertEquals(emptyList<Any>(), db.bestEffortDao().forSport(SportFamily.RUN))
    }
}
