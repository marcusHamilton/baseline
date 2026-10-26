package dev.baseline.ui.today

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo
import dev.baseline.data.db.TaskCounts
import dev.baseline.garmin.KeyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class SyncStatusTest {
    private fun info(
        state: WorkInfo.State,
        attempts: Int = 0,
        network: NetworkType = NetworkType.NOT_REQUIRED,
        tags: Set<String> = emptySet(),
        periodic: Boolean = false,
    ) = WorkInfo(
        UUID.randomUUID(),
        state,
        tags,
        runAttemptCount = attempts,
        constraints = Constraints(requiredNetworkType = network),
        periodicityInfo = if (periodic) WorkInfo.PeriodicityInfo(3_600_000, 300_000) else null,
    )

    private val ok = KeyStatus.Ok(LocalDate.of(2027, 1, 1), 90)
    private val running = listOf(info(WorkInfo.State.RUNNING))

    @Test
    fun runningShowsProgressOnlyWhileBackfillIsPending() {
        assertEquals("Syncing… 340/1180", syncStatus(running, TaskCounts(340, 1180), true, ok))
        assertEquals("Syncing…", syncStatus(running, TaskCounts(1180, 1195), false, ok))
    }

    @Test
    fun successRetryAndNothing() {
        assertEquals("Up to date", syncStatus(listOf(info(WorkInfo.State.SUCCEEDED)), TaskCounts(5, 5), false, ok))
        val retrying = listOf(info(WorkInfo.State.ENQUEUED, attempts = 1))
        assertEquals("Retrying shortly", syncStatus(retrying, TaskCounts(5, 5), false, ok, online = true))
        assertEquals("Retrying shortly", syncStatus(listOf(info(WorkInfo.State.FAILED)), TaskCounts(5, 5), false, ok, online = true))
        assertEquals("Offline, will retry", syncStatus(retrying, TaskCounts(5, 5), false, ok, online = false))
        assertNull(syncStatus(emptyList(), TaskCounts(0, 0), false, ok))
        assertNull(syncStatus(listOf(info(WorkInfo.State.ENQUEUED)), TaskCounts(5, 5), false, ok))
    }

    @Test
    fun succeededPartialRunDuringBackfillIsNotUpToDate() {
        assertEquals("Syncing… 340/1180", syncStatus(listOf(info(WorkInfo.State.SUCCEEDED)), TaskCounts(340, 1180), true, ok))
    }

    @Test
    fun backfillHeldForWifiSaysSo() {
        val held = listOf(info(WorkInfo.State.SUCCEEDED), info(WorkInfo.State.ENQUEUED, network = NetworkType.UNMETERED))
        assertEquals("Waiting for Wi-Fi", syncStatus(held, TaskCounts(340, 1180), true, ok))
        // Running wins; no backfill pending or an any-network request is not "waiting for Wi-Fi".
        assertEquals("Syncing… 340/1180", syncStatus(held + running, TaskCounts(340, 1180), true, ok))
        assertEquals("Up to date", syncStatus(held, TaskCounts(5, 5), false, ok))
        assertEquals("Syncing… 340/1180", syncStatus(listOf(info(WorkInfo.State.SUCCEEDED), info(WorkInfo.State.ENQUEUED)), TaskCounts(340, 1180), true, ok))
    }

    @Test
    fun waitingForWifiIgnoresPeriodicAndUserRequests() {
        val done = info(WorkInfo.State.SUCCEEDED)
        val periodic = info(WorkInfo.State.ENQUEUED, network = NetworkType.UNMETERED, periodic = true)
        assertEquals("Syncing… 340/1180", syncStatus(listOf(done, periodic), TaskCounts(340, 1180), true, ok))
        val wifi = info(WorkInfo.State.ENQUEUED, network = NetworkType.UNMETERED)
        val userItem = info(WorkInfo.State.ENQUEUED, network = NetworkType.CONNECTED, tags = setOf("user"))
        assertEquals("Syncing… 340/1180", syncStatus(listOf(done, wifi, userItem), TaskCounts(340, 1180), true, ok))
        assertEquals("Waiting for Wi-Fi", syncStatus(listOf(done, wifi, periodic), TaskCounts(340, 1180), true, ok))
    }

    @Test
    fun keyProblemWinsOverEverything() {
        val msg = "Garmin key problem: check Settings"
        assertEquals(msg, syncStatus(running, TaskCounts(1, 2), true, KeyStatus.Invalid))
        assertEquals(msg, syncStatus(emptyList(), TaskCounts(0, 0), false, KeyStatus.Expired(LocalDate.of(2026, 1, 1))))
        assertNull(syncStatus(emptyList(), TaskCounts(0, 0), false, null))
    }
}
