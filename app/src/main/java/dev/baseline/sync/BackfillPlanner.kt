package dev.baseline.sync

import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.TaskStatus
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminEndpoint.*
import java.time.LocalDate

/** Earliest Garmin data; the engine stops paging older when windows return Empty. */
val HISTORY_START: LocalDate = LocalDate.of(2025, 8, 30)

/**
 * Turns a date span into the sync task list (spec §5.1). Priority is the §5.1 order; within a priority
 * `TaskDao.nextPending` takes the greatest key first, so per-day tasks run newest first.
 * HRV_DAY/SPO2_DAY are lazy and ACTIVITY_RANGE/FIT are added by the engine, so none are planned here.
 */
object BackfillPlanner {
    private val rangePriorities = listOf(
        DAILY_SUMMARIES to 2,
        HRV_RANGE to 2,
        SLEEP_SUMMARIES to 3,
        BODY_BATTERY to 3,
        STRESS to 4,
        STEPS to 4,
    )

    /** Both [start] and [end] inclusive. */
    fun plan(start: LocalDate, end: LocalDate): List<SyncTaskEntity> {
        require(!start.isAfter(end)) { "start $start is after end $end" }
        fun task(e: GarminEndpoint, key: String, priority: Int) = SyncTaskEntity(e.name, key, priority, TaskStatus.PENDING, 0, null)

        val tasks = mutableListOf(task(PROFILE, "-", 1), task(HR_ZONES, "-", 1), task(ACTIVITY_PAGE, "0", 5))
        for ((e, priority) in rangePriorities) {
            var windowEnd = end
            while (!windowEnd.isBefore(start)) {
                val windowStart = maxOf(start, windowEnd.minusDays(e.maxDays - 1L))
                tasks += task(e, "$windowStart..$windowEnd", priority)
                windowEnd = windowStart.minusDays(1)
            }
        }
        var day = end
        while (!day.isBefore(start)) {
            tasks += task(SLEEP_DAY, day.toString(), 6)
            tasks += task(HEART_RATE_DAY, day.toString(), 6)
            day = day.minusDays(1)
        }
        return tasks
    }
}
