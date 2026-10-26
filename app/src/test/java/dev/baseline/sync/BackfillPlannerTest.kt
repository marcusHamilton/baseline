package dev.baseline.sync

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.data.db.TaskStatus
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminEndpoint.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
class BackfillPlannerTest {
    private val start = HISTORY_START
    private val end = LocalDate.of(2026, 10, 23)
    private val rangeEndpoints = listOf(DAILY_SUMMARIES, HRV_RANGE, SLEEP_SUMMARIES, BODY_BATTERY, STRESS, STEPS)
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(ApplicationProvider.getApplicationContext()) { AppDatabase_Impl() }
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun window(key: String) = key.split("..").let { LocalDate.parse(it[0]) to LocalDate.parse(it[1]) }

    private fun days(key: String) = window(key).let { (a, b) -> ChronoUnit.DAYS.between(a, b) + 1 }

    @Test
    fun windowsNeverExceedMax() {
        BackfillPlanner.plan(start, end).filter { GarminEndpoint.valueOf(it.endpoint) in rangeEndpoints }.forEach {
            assertEquals(true, days(it.key) <= GarminEndpoint.valueOf(it.endpoint).maxDays)
        }
    }

    @Test
    fun windowsTileWithoutGapsOrOverlap() {
        val plan = BackfillPlanner.plan(start, end)
        for (e in rangeEndpoints) {
            val windows = plan.filter { it.endpoint == e.name }.map { window(it.key) }.sortedBy { it.first }
            assertEquals(start, windows.first().first)
            assertEquals(end, windows.last().second)
            windows.zipWithNext().forEach { (a, b) -> assertEquals(a.second.plusDays(1), b.first) }
        }
    }

    @Test
    fun taskCountsMatchSpec() {
        val plan = BackfillPlanner.plan(start, end)
        val counts = plan.groupingBy { it.endpoint }.eachCount()
        val expected = mapOf(
            DAILY_SUMMARIES to 3, HRV_RANGE to 3, SLEEP_SUMMARIES to 15, BODY_BATTERY to 15, STRESS to 60, STEPS to 60,
            SLEEP_DAY to 420, HEART_RATE_DAY to 420, ACTIVITY_PAGE to 1, PROFILE to 1, HR_ZONES to 1,
        ).mapKeys { it.key.name }
        assertEquals(expected, counts)
        assertEquals(999, plan.size)
        assertEquals(true, plan.all { it.status == TaskStatus.PENDING && it.attempts == 0 && it.lastError == null })
    }

    @Test
    fun perDayTasksAreNewestFirstWithinPriority() = runBlocking {
        val dao = db.taskDao()
        val plan = BackfillPlanner.plan(start, end)
        dao.insertIgnore(plan)
        plan.filter { it.priority <= 5 }.forEach { dao.update(it.copy(status = TaskStatus.DONE)) }
        val next = dao.nextPending()!!
        assertEquals(6, next.priority)
        assertEquals("2026-10-23", next.key)
    }

    @Test
    fun startEqualsEnd() {
        val day = LocalDate.of(2026, 10, 23)
        val plan = BackfillPlanner.plan(day, day)
        for (e in rangeEndpoints) {
            assertEquals(listOf("$day..$day"), plan.filter { it.endpoint == e.name }.map { it.key })
        }
        assertEquals(1, plan.count { it.endpoint == SLEEP_DAY.name })
    }
}
