package dev.baseline.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.baseline.AppContainer
import dev.baseline.R
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.data.db.SyncTaskEntity
import dev.baseline.data.db.TaskStatus
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminResult
import dev.baseline.sync.SyncEngine
import dev.baseline.sync.SyncScheduler
import dev.baseline.sync.SyncWorker
import dev.baseline.sync.SyncWorkerDeps
import dev.baseline.testutil.ScopeCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private class MutableClock(var now: Instant, private val zoneId: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now
}

private class LifecycleTestContainer(context: Context, private val store: DataStore<Preferences>, testClock: Clock) : AppContainer(context, testClock) {
    override val dataStore: DataStore<Preferences> get() = store
    override val scheduler = SyncScheduler(WorkManager.getInstance(context), { false })
    override val apiKeyVault by lazy { ApiKeyVault(store, { it }, { it }, clock, dateTicker.today) }
    override val db: AppDatabase by lazy {
        Room.inMemoryDatabaseBuilder<AppDatabase>(context) { AppDatabase_Impl() }.allowMainThreadQueries().build()
    }
    override val syncEngine by lazy { SyncEngine({ _: GarminEndpoint, _: String -> GarminResult.Empty }, db, clock) {} }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WidgetLifecycleTest {
    private val scopes = ScopeCleanup()
    private lateinit var ctx: Context
    private lateinit var file: File
    private lateinit var mutableClock: MutableClock
    private lateinit var container: LifecycleTestContainer

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        file = File.createTempFile("widgetlifecycle", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        mutableClock = MutableClock(Instant.parse("2026-10-26T23:50:00Z"), ZoneOffset.UTC)
        container = LifecycleTestContainer(ctx, store, mutableClock)
        WidgetUpdater.testContainerOverride = container
        runBlocking {
            container.db.taskDao().insertIgnore(
                listOf(SyncTaskEntity(GarminEndpoint.STEPS.name, "2026-10-17..2026-10-23", 4, TaskStatus.PENDING, 0, null)),
            )
        }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        WidgetUpdater.testContainerOverride = null
        scopes.close()
        container.close()
        container.db.close()
        dev.baseline.sync.closeWorkManagerDb(ctx)
        file.delete()
    }

    private suspend fun runSyncWorker(
        fetch: suspend (GarminEndpoint, String) -> GarminResult,
        events: MutableList<String>,
        updateWidgets: suspend (Boolean) -> Unit,
    ): Result {
        val engine = SyncEngine(fetch, container.db, mutableClock) {}
        val deps =
            SyncWorkerDeps(
                engine = engine,
                clock = mutableClock,
                afterSync = { events += "afterSync" },
                markSynced = { events += "markSynced" },
                reschedule = { events += "reschedule" },
                continueSoon = { events += "continueSoon" },
                updateWidgets = updateWidgets,
            )
        val factory =
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    SyncWorker(appContext, workerParameters, deps)
            }
        return TestListenableWorkerBuilder<SyncWorker>(ctx).setWorkerFactory(factory).build().doWork()
    }

    @Test
    fun syncWorkerNotifiesWidgetsAtStartAndAfterCompletionAndSwallowsWidgetErrors() = runBlocking {
        val events = mutableListOf<String>()
        val result =
            runSyncWorker(
                fetch = { _, _ -> GarminResult.Empty },
                events = events,
                updateWidgets = { syncing -> events += "widgets:$syncing" },
            )
        assertEquals(Result.success(), result)
        assertEquals(
            listOf("widgets:true", "afterSync", "markSynced", "reschedule", "widgets:false"),
            events,
        )

        val offlineEvents = mutableListOf<String>()
        container.db.taskDao().insertIgnore(
            listOf(SyncTaskEntity(GarminEndpoint.SLEEP_DAY.name, "2026-10-26", 1, TaskStatus.PENDING, 0, null)),
        )
        val retryResult =
            runSyncWorker(
                fetch = { _, _ -> throw IOException("offline") },
                events = offlineEvents,
                updateWidgets = { syncing -> offlineEvents += "widgets:$syncing" },
            )
        assertEquals(Result.retry(), retryResult)
        assertEquals(listOf("widgets:true", "afterSync", "widgets:false"), offlineEvents)

        val throwingWidgetResult =
            runSyncWorker(
                fetch = { _, _ -> GarminResult.Empty },
                events = mutableListOf(),
                updateWidgets = { error("launcher dead") },
            )
        assertEquals(Result.success(), throwingWidgetResult)
    }

    @Test
    fun placedWidgetsRefreshOnRowReorderAndMidnightRollover(): Unit = runBlocking {
        container.apiKeyVault.save("k", 365)
        val manager = AppWidgetManager.getInstance(ctx)
        val shadowMgr = Shadows.shadowOf(manager)
        val vitalsId = shadowMgr.createWidget(VitalsWidgetProvider::class.java, R.layout.widget_vitals)
        val readinessId = shadowMgr.createWidget(ReadinessWidgetProvider::class.java, R.layout.widget_readiness_expanded)
        manager.updateAppWidgetOptions(
            readinessId,
            Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280) },
        )

        val collectorJob =
            launch(Dispatchers.Default) {
                container.widgetRefreshSignals.collect {
                    container.widgetUpdater.updateAll()
                }
            }
        try {
            container.userPrefs.setBaselineOrder(listOf("steps", "hrv", "rhr", "sleep", "resp", "bb", "stress"))
            withTimeout(5_000) {
                while (
                    shadowMgr.getViewFor(vitalsId)?.findViewById<TextView>(R.id.vital_label_1)?.text?.toString() != "Steps"
                ) {
                    yield()
                }
            }
            val vitalsLabel = shadowMgr.getViewFor(vitalsId).findViewById<TextView>(R.id.vital_label_1).text.toString()
            assertEquals("Steps", vitalsLabel)

            mutableClock.now = Instant.parse("2026-10-27T00:05:00Z")
            container.dateTicker.refresh()
            withTimeout(5_000) {
                while (
                    shadowMgr.getViewFor(readinessId)?.findViewById<TextView>(R.id.widget_header_title)?.text?.toString() !=
                    "TODAY · TUE OCT 27"
                ) {
                    yield()
                }
            }
            val header = shadowMgr.getViewFor(readinessId).findViewById<TextView>(R.id.widget_header_title).text.toString()
            assertTrue(header.contains("TUE OCT 27"))

            // Concurrent updateAll bursts from providers + signals + sync must serialize cleanly.
            (1..8).map { i -> async(Dispatchers.Default) { container.widgetUpdater.updateAll(forceSyncing = i % 2 == 0) } }.awaitAll()
        } finally {
            collectorJob.cancel()
        }
    }
}
