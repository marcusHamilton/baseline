package dev.baseline.scores

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.prefs.UserPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AfterSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var prefs: UserPrefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val oct24 = LocalDate.of(2026, 10, 24)
    private val redos = AtomicInteger()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(ApplicationProvider.getApplicationContext()) { AppDatabase_Impl() }
            .allowMainThreadQueries()
            .build()
        val file = File.createTempFile("prefs", ".preferences_pb").also { it.delete() }
        prefs = UserPrefs(PreferenceDataStoreFactory.create(scope = storeScope) { file })
    }

    @After
    fun tearDown() {
        scope.cancel()
        storeScope.cancel()
        db.close()
    }

    private fun afterSync(recalculate: suspend () -> Unit = { redos.incrementAndGet() }): Pair<AfterSync, HistoryRecalculator> {
        val clock = Clock.fixed(oct24.atTime(8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        val recalculator = HistoryRecalculator(scope, prefs, recalculate)
        return AfterSync(ScorePipeline(db, prefs, clock), recalculator, { true }, { oct24 }) to recalculator
    }

    private suspend fun scoredDays() = (20..24).count { db.scoreDao().get(LocalDate.of(2026, 10, it)) != null }

    @Test
    fun pendingRedoRunsAndSkipsTheNormalRecompute() = runBlocking {
        prefs.setRecalcPending(true)
        val (after, _) = afterSync()
        after(LocalDate.of(2026, 10, 22))
        assertEquals(1, redos.get())
        assertEquals(0, scoredDays())
        assertEquals(false, prefs.recalcPending.first())
    }

    @Test
    fun nothingPendingRecomputesFromTheChangedDay() = runBlocking {
        val (after, _) = afterSync()
        after(LocalDate.of(2026, 10, 22))
        assertEquals(0, redos.get())
        assertNull(db.scoreDao().get(LocalDate.of(2026, 10, 21)))
        assertEquals(3, scoredDays())
    }

    @Test
    fun nothingChangedRecomputesToday() = runBlocking {
        val (after, _) = afterSync()
        after(null)
        assertEquals(1, scoredDays())
        assertNotNull(db.scoreDao().get(oct24))
    }

    @Test
    fun aRunAlreadyInFlightIsNotRedoneAndTheRecomputeRuns() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val (after, recalculator) = afterSync { release.await() }
        recalculator.request() // sets pending and starts the (blocked) run
        try {
            after(LocalDate.of(2026, 10, 23))
            assertEquals(2, scoredDays())
        } finally {
            release.complete(Unit)
        }
    }
}
