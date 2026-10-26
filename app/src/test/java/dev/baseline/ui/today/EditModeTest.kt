package dev.baseline.ui.today

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.baseline.data.Repository
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.data.db.TaskCounts
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.KeyStatus
import dev.baseline.prefs.UserPrefs
import dev.baseline.sync.SyncScheduler
import dev.baseline.sync.closeWorkManagerDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditModeTest {
    private val today = LocalDate.of(2026, 10, 24)
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private lateinit var prefs: UserPrefs
    private lateinit var vm: TodayViewModel
    private lateinit var storeScope: CoroutineScope
    private val store0 = ViewModelStore() // cleared first in tearDown so no Room flow outlives the database

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context) { AppDatabase_Impl() }.allowMainThreadQueries().build()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File.createTempFile("editmode", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = storeScope) { file }
        prefs = UserPrefs(store)
        val vault = ApiKeyVault(store, { it }, { it }, Clock.systemUTC())
        vm =
            ViewModelProvider.create(
                store0,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = TodayViewModel(
                        Repository(db, prefs, vault),
                        SyncScheduler(WorkManager.getInstance(context)) { false },
                        prefs,
                        flowOf(TaskCounts(0, 0)),
                        flowOf(false),
                        flowOf(KeyStatus.Invalid),
                        flowOf(true),
                        today,
                    ) as T
                },
            )[TodayViewModel::class.java]
    }

    @After
    fun tearDown() = runBlocking {
        vm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() // before clear(): clear() cancels the same scope, this waits for it
        store0.clear()
        storeScope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        closeWorkManagerDb(context)
        file.delete()
        Dispatchers.resetMain()
    }

    private suspend fun awaitState(predicate: (TodayUiState) -> Boolean): TodayUiState = withTimeout(10_000) { vm.state.first(predicate) }

    @Test
    fun moveRowPersistsOrder() = runBlocking {
        vm.toggleEdit()
        val before = awaitState { it.editing && it.rows.size == 7 }
        assertEquals("steps", before.rows[6].id)

        vm.moveRow(6, 0)

        val order = withTimeout(10_000) { prefs.baselineOrder.first { it.firstOrNull() == "steps" } }
        assertEquals(listOf("steps", "hrv", "rhr", "sleep", "resp", "bb", "stress"), order)
        assertEquals(order, awaitState { it.rows.firstOrNull()?.id == "steps" }.rows.map { it.id })
    }

    @Test
    fun movingRowsKeepsHiddenOnesHidden() = runBlocking {
        vm.toggleHidden("bb")
        withTimeout(10_000) { prefs.hiddenRows.first { "bb" in it } }
        vm.toggleEdit()
        awaitState { it.editing && it.rows.size == 7 }

        vm.moveRow(6, 0)

        val order = withTimeout(10_000) { prefs.baselineOrder.first { it.firstOrNull() == "steps" } }
        assertEquals(listOf("steps", "hrv", "rhr", "sleep", "resp", "bb", "stress"), order)
        assertEquals(setOf("bb"), prefs.hiddenRows.first())
        val rows = awaitState { it.rows.firstOrNull()?.id == "steps" }.rows
        assertTrue(rows.single { it.id == "bb" }.hidden)
    }

    @Test
    fun hiddenRowStillVisibleInEditMode() = runBlocking {
        vm.toggleHidden("steps")
        withTimeout(10_000) { prefs.hiddenRows.first { "steps" in it } }

        val normal = awaitState { s -> !s.loading && s.rows.none { it.id == "steps" } }
        assertFalse(normal.editing)
        assertEquals(6, normal.rows.size)

        vm.toggleEdit()
        val editing = awaitState { it.editing && it.rows.any { r -> r.id == "steps" } }
        assertEquals(7, editing.rows.size)
        assertTrue(editing.rows.single { it.id == "steps" }.hidden)
        assertTrue(editing.rows.filter { it.id != "steps" }.none { it.hidden })

        vm.toggleHidden("steps") // unhide from edit mode
        assertFalse(awaitState { s -> s.rows.any { it.id == "steps" && !it.hidden } }.rows.single { it.id == "steps" }.hidden)

        vm.toggleEdit()
        assertEquals(7, awaitState { !it.editing && it.rows.size == 7 }.rows.size)
    }
}
