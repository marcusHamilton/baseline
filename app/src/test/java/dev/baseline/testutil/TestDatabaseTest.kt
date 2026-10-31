package dev.baseline.testutil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TestDatabaseTest {
    private val day = LocalDate.of(2026, 10, 24)

    /**
     * What TrendsViewModelTest.rangeEndFollowsTheTodayFlow hit in CI: flows subscribe to a fresh database and are
     * cancelled almost at once (a `flatMapLatest` switch). With Room's builder alone about a third of these rounds left
     * the open database without its tables.
     */
    @Test
    fun cancellingTheFirstQueriesNeverLosesTheSchema() = runBlocking {
        var lost = 0
        repeat(100) { round ->
            val db = inMemoryDatabase()
            val readers = listOf(
                launch(Dispatchers.IO) { db.dayDao().range(day.minusDays(29), day).collect {} },
                launch(Dispatchers.IO) { db.scoreDao().range(day.minusDays(29), day).collect {} },
            )
            delay(round % 8L)
            readers.forEach { it.cancel() }
            readers.forEach { it.join() }
            runCatching { db.dayDao().range(day.minusDays(28), day.plusDays(1)).first() }
                .onFailure { if (it.message?.contains("no such table") == true) lost++ else throw it }
            db.close()
        }
        assertEquals("rounds whose database lost its schema", 0, lost)
    }
}
