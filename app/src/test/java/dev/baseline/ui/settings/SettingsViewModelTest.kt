package dev.baseline.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.core.app.ApplicationProvider
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminResult
import dev.baseline.garmin.KeyStatus
import dev.baseline.prefs.BanisterSet
import dev.baseline.prefs.SyncInterval
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.HistoryRecalculator
import dev.baseline.sync.SyncScheduler
import dev.baseline.sync.closeWorkManagerDb
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

private class FakeScheduler(wm: WorkManager) : SyncScheduler(wm, { false }) {
    val syncNows = AtomicInteger()
    val applied = mutableListOf<SyncInterval>()
    override suspend fun syncNow() { syncNows.incrementAndGet() }
    override suspend fun apply(interval: SyncInterval) { applied += interval }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var file: File
    private lateinit var prefs: UserPrefs
    private lateinit var vault: ApiKeyVault
    private lateinit var scheduler: FakeScheduler
    private var testResult: GarminResult = GarminResult.Ok(ByteArray(0), null)
    private val recalcs = AtomicInteger()
    private val requestedCount = AtomicInteger()
    @Volatile private var banisterAtRecalc: BanisterSet? = null
    private lateinit var vm: SettingsViewModel
    private lateinit var recalculator: HistoryRecalculator
    private lateinit var storeScope: CoroutineScope
    private lateinit var recalcScope: CoroutineScope
    private var clearedTokens = 0
    private val requeues = AtomicInteger()
    private var syncNowsAtRequeue = -1
    private var requeueGate = CompletableDeferred<Unit>().also { it.complete(Unit) }
    private var inRecalc: suspend () -> Unit = {}

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        file = File.createTempFile("settingsvm", ".preferences_pb").also { it.delete() }
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        recalcScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = storeScope) { file }
        prefs = UserPrefs(store)
        vault = ApiKeyVault(store, { it }, { it }, clock)
        scheduler = FakeScheduler(WorkManager.getInstance(context))
        recalculator = HistoryRecalculator(recalcScope, prefs) {
            banisterAtRecalc = prefs.banister.first(); recalcs.incrementAndGet(); inRecalc()
        }
        vm = SettingsViewModel(
            vault, prefs, scheduler,
            testKey = { _, onValid -> testResult.also { if (it is GarminResult.Ok || it == GarminResult.Empty) onValid() } },
            clearToken = { clearedTokens++ },
            recalculator = recalculator,
            coverage = flowOf(HistoryCoverage(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 8, 30))),
            imperial = flowOf(false),
            failedCount = flowOf(3),
            requeueFailed = { syncNowsAtRequeue = scheduler.syncNows.get(); requeues.incrementAndGet(); requeueGate.await() },
        )
    }

    @After
    fun tearDown() = runBlocking {
        vm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
        recalcScope.coroutineContext[Job]!!.cancelAndJoin()
        storeScope.coroutineContext[Job]!!.cancelAndJoin()
        closeWorkManagerDb(ApplicationProvider.getApplicationContext())
        file.delete()
        Dispatchers.resetMain()
    }

    private suspend fun idle() = withTimeout(10_000) {
        recalculator.running.first { !it }
        prefs.recalcPending.first { !it }
    }

    private suspend fun status() = withTimeout(10_000) { vault.status.first() }
    private suspend fun settled() = withTimeout(10_000) { vm.entry.first { it is KeyEntryState.Saved || it is KeyEntryState.Error } }

    @Test
    fun retryFailedRequeuesThenSyncsNow() = runBlocking {
        requeueGate = CompletableDeferred()
        assertEquals(3, withTimeout(10_000) { vm.state.first { it.failedCount == 3 } }.failedCount)
        vm.retryFailed()
        assertTrue(withTimeout(10_000) { vm.state.first { it.retrying } }.retrying)
        vm.retryFailed() // second tap while running
        assertEquals(1, requeues.get())
        assertEquals(0, scheduler.syncNows.get()) // still re-queuing
        requeueGate.complete(Unit)
        withTimeout(10_000) { while (scheduler.syncNows.get() == 0) kotlinx.coroutines.yield() }
        assertEquals(1, requeues.get())
        assertEquals(0, syncNowsAtRequeue) // re-queued before the sync was requested
        assertFalse(withTimeout(10_000) { vm.state.first { !it.retrying } }.retrying)
    }

    @Test
    fun leavingSettingsMidRetryStillSyncs() = runBlocking {
        requeueGate = CompletableDeferred()
        vm.retryFailed()
        assertEquals(1, requeues.get())
        vm.viewModelScope.coroutineContext[Job]!!.cancel() // the screen is gone
        requeueGate.complete(Unit)
        withTimeout(10_000) { while (scheduler.syncNows.get() == 0) kotlinx.coroutines.yield() }
        assertEquals(1, scheduler.syncNows.get())
    }

    @Test
    fun changingBanisterTriggersRecalc() = runBlocking {
        vm.setBanister(BanisterSet.FEMALE_FITTED).join()
        withTimeout(10_000) { while (recalcs.get() == 0) kotlinx.coroutines.yield() }
        idle()
        assertEquals(1, recalcs.get())
        assertEquals(BanisterSet.FEMALE_FITTED, banisterAtRecalc) // saved before the history is re-scored
        assertEquals(BanisterSet.FEMALE_FITTED, prefs.banister.first())

        vm.setBanister(BanisterSet.FEMALE_FITTED).join() // unchanged: nothing to redo
        idle()
        assertEquals(1, recalcs.get())
        assertFalse(prefs.recalcPending.first())
    }

    @Test
    fun quickTapsEndOnTheLastChoice() = runBlocking {
        vm.setBanister(BanisterSet.FEMALE_FITTED).join()
        idle()
        val jobs = listOf(BanisterSet.MALE_FITTED, BanisterSet.FEMALE_FITTED, BanisterSet.MALE_FITTED).map { vm.setBanister(it) }
        jobs.joinAll()
        idle()
        assertEquals(BanisterSet.MALE_FITTED, prefs.banister.first())
        assertEquals(BanisterSet.MALE_FITTED, banisterAtRecalc) // the last run saw the last choice
    }

    @Test
    fun noRequestIsLostAtTheEndOfARun() = runBlocking {
        val seenAtRunStart = AtomicInteger()
        val flaky = HistoryRecalculator(recalcScope, prefs) { seenAtRunStart.set(requestedCount.get()); kotlinx.coroutines.yield() }
        repeat(300) {
            requestedCount.incrementAndGet()
            flaky.request()
            if (it % 3 == 0) kotlinx.coroutines.yield()
        }
        withTimeout(10_000) { flaky.running.first { !it } }
        withTimeout(10_000) { prefs.recalcPending.first { !it } }
        assertEquals(300, seenAtRunStart.get()) // the last run began after the last request
    }

    @Test
    fun redoIfPendingRunsOnlyWhenCutShortAndIdle() = runBlocking {
        assertFalse(recalculator.redoIfPending()) // nothing pending
        prefs.setRecalcPending(true)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        inRecalc = { if (recalcs.get() == 1) { started.complete(Unit); gate.await() } }

        assertTrue(recalculator.redoIfPending())
        withTimeout(10_000) { started.await() }
        assertFalse(recalculator.redoIfPending()) // already running
        recalculator.request() // a Settings request mid-redo must survive the redo's own clearing
        gate.complete(Unit)
        idle()
        assertEquals(2, recalcs.get())
        assertFalse(prefs.recalcPending.first())
    }

    @Test
    fun requestsDuringARunCoalesceIntoOneMoreRun() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        inRecalc = { if (recalcs.get() == 1) { started.complete(Unit); gate.await() } }

        vm.recalculateHistory().join()
        withTimeout(10_000) { started.await() }
        assertTrue(prefs.recalcPending.first()) // set while the run is in flight
        listOf(vm.recalculateHistory(), vm.recalculateHistory(), vm.recalculateHistory()).joinAll()
        assertTrue(vm.state.first { it.recalculating }.recalculating)
        gate.complete(Unit)
        idle()

        assertEquals(2, recalcs.get()) // the three mid-run requests became exactly one more run
        assertFalse(prefs.recalcPending.first())
    }

    @Test
    fun recalculateHistoryRunsAndClearsBusyFlag() = runBlocking {
        vm.recalculateHistory()
        withTimeout(10_000) { vm.state.first { !it.recalculating && recalcs.get() == 1 } }
        assertEquals(1, recalcs.get())
    }

    @Test
    fun setIntervalSavesAndReschedules() = runBlocking {
        vm.setInterval(SyncInterval.THREE_HOURS)
        withTimeout(10_000) { while (scheduler.applied.isEmpty()) kotlinx.coroutines.yield() }
        assertEquals(listOf(SyncInterval.THREE_HOURS), scheduler.applied)
        assertEquals(SyncInterval.THREE_HOURS, prefs.syncInterval.first())
    }

    @Test
    fun replaceKeyResumesSync() = runBlocking {
        vault.save("old", 30)
        vault.markInvalid()
        assertEquals(KeyStatus.Invalid, status())

        vm.replaceKey("  new  ", 365)

        assertEquals(KeyEntryState.Saved, settled())
        assertEquals(KeyStatus.Ok(LocalDate.of(2027, 10, 24), 365), status())
        assertEquals("new", vault.key())
        assertEquals(1, scheduler.syncNows.get())
        assertEquals(1, clearedTokens) // the next sync mints with the new key
    }

    @Test
    fun failedReplaceKeepsTheOldKeyAndDoesNotSync() = runBlocking {
        vault.save("old", 30)
        vault.markInvalid()
        testResult = GarminResult.KeyInvalid

        vm.replaceKey("bad", 365)

        val s = settled()
        assertEquals(KeyEntryState.Error("Garmin didn't accept this key. Check you copied all of it."), s)
        assertEquals("old", vault.key())
        assertEquals(KeyStatus.Invalid, status()) // as it was before the attempt
        assertEquals(0, scheduler.syncNows.get())
        assertEquals(0, clearedTokens) // the stored key and its token are untouched
        assertNull((vm.entry.value as? KeyEntryState.Saved))
    }
}
